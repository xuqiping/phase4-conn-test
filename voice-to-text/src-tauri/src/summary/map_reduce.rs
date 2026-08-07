//! map-reduce 编排（Step 8, FR-107/109）：aligned.json → 切段 → 分段总结（map）→
//! 全局合并大纲（reduce）→ 草稿落盘。
//!
//! 容错四件套落地位置：
//! - 可解释：每条要点带 ts_ms + frame_ref 回链课件帧；
//! - 可纠错：`regenerate` 单段或全局重生成；
//! - 可撤销：结果只写 summary_draft.json（草稿 + 版本历史，见 mod.rs）；
//! - 可降级：任一段 LLM 失败 → 该段本地抽取式兜底（local_fallback=true），
//!   全断网时整个草稿仍可产出（fallback=true），转写+课件帧本就留在本地。
//!
//! 并发：plan 允许「默认串行或低并发」。当前实现**串行** + 段间 300ms 礼貌间隔
//!（最保守的限流姿态；cfg.concurrency 字段保留给后续按需放开）。

use super::cloud_api::{chat_blocking, ChatMessage};
use super::prompt;
use super::{save_new_draft, SegmentSummary, SummaryConfig, SummaryDraft, SummaryPoint};
use crate::align::AlignedUnit;
use serde::Deserialize;
use std::path::Path;

/// 分段输入：一段 ≤ max_segment_chars 的材料。
#[derive(Debug, Clone)]
pub struct SegmentInput {
    pub segment_id: usize,
    pub start_ms: i64,
    pub end_ms: i64,
    pub parts: Vec<SegmentPart>,
}

/// 段内一页/一窗的材料（对应一个 AlignedUnit）。
#[derive(Debug, Clone)]
pub struct SegmentPart {
    pub ts_ms: i64,
    pub frame_ref: Option<String>,
    /// 缩略图（多模态精修附图优先用它，体积小）。
    pub thumb_ref: Option<String>,
    pub ocr_text: Option<String>,
    pub texts: Vec<String>,
}

fn unit_chars(u: &AlignedUnit) -> usize {
    u.ocr_text.as_ref().map(|s| s.chars().count()).unwrap_or(0)
        + u.texts.iter().map(|t| t.chars().count()).sum::<usize>()
}

/// 纯逻辑切段（可测）：按 unit 顺序累积，超限即切。
/// 单个 unit 超限（罕见：一页 PPT 塞满字）→ 独占一段并截断文本到上限。
pub fn build_segments(units: &[AlignedUnit], max_chars: usize) -> Vec<SegmentInput> {
    let mut segments = Vec::new();
    let mut parts: Vec<SegmentPart> = Vec::new();
    let mut cur_chars = 0usize;
    let mut cur_start = 0i64;

    let flush = |parts: &mut Vec<SegmentPart>, segments: &mut Vec<SegmentInput>, start: i64, end: i64| {
        if !parts.is_empty() {
            segments.push(SegmentInput {
                segment_id: segments.len(),
                start_ms: start,
                end_ms: end,
                parts: std::mem::take(parts),
            });
        }
    };

    for u in units {
        let uc = unit_chars(u);
        if !parts.is_empty() && cur_chars + uc > max_chars {
            flush(&mut parts, &mut segments, cur_start, u.start_ms);
            cur_chars = 0;
        }
        if parts.is_empty() {
            cur_start = u.start_ms;
        }
        // 单页超限：截断（保留开头最有信息量的部分）。
        let (ocr, texts) = if uc > max_chars {
            let mut budget = max_chars;
            let o = u.ocr_text.as_ref().map(|s| {
                let take = s.chars().take(budget).collect::<String>();
                budget = budget.saturating_sub(take.chars().count());
                take
            });
            let ts = u
                .texts
                .iter()
                .map(|t| t.chars().take(budget).collect::<String>())
                .take_while(|t| !t.is_empty())
                .collect::<Vec<_>>();
            (o, ts)
        } else {
            (u.ocr_text.clone(), u.texts.clone())
        };
        cur_chars += uc.min(max_chars);
        parts.push(SegmentPart {
            ts_ms: u.start_ms,
            frame_ref: u.orig_path.clone(),
            thumb_ref: u.thumb_path.clone(),
            ocr_text: ocr,
            texts,
        });
    }
    if let Some(first) = parts.first().map(|_| cur_start) {
        let end = units.last().map(|u| u.end_ms).unwrap_or(first);
        flush(&mut parts, &mut segments, first, end);
    }
    segments
}

/// 本地抽取式兜底：每段取前几句作为「要点」，标注 local_fallback（可解释：真实出处时间戳）。
fn local_fallback_points(seg: &SegmentInput) -> Vec<SummaryPoint> {
    let mut points = Vec::new();
    for part in &seg.parts {
        for t in &part.texts {
            let first = t
                .split(|c| matches!(c, '。' | '！' | '？' | '.' | '\n'))
                .next()
                .unwrap_or(t)
                .trim();
            if !first.is_empty() {
                points.push(SummaryPoint {
                    text: first.chars().take(80).collect(),
                    ts_ms: part.ts_ms,
                    frame_ref: part.frame_ref.clone(),
                });
            }
            if points.len() >= 3 {
                return points;
            }
        }
    }
    points
}

/// LLM 分段响应 JSON。
#[derive(Debug, Deserialize)]
struct SegmentResponse {
    #[serde(default)]
    points: Vec<SegmentPointRaw>,
}
#[derive(Debug, Deserialize)]
struct SegmentPointRaw {
    text: String,
    #[serde(default)]
    ts_ms: i64,
}
#[derive(Debug, Deserialize)]
struct MergeResponse {
    #[serde(default)]
    outline: Vec<String>,
}

/// 解析分段输出：提取 JSON → points；ts 截断回段区间；frame_ref 按时间就近回链。
fn parse_segment_response(raw: &str, seg: &SegmentInput) -> Result<Vec<SummaryPoint>, String> {
    let json = prompt::extract_json_object(raw).ok_or("响应中未找到 JSON 对象")?;
    let resp: SegmentResponse = serde_json::from_str(&json).map_err(|e| format!("JSON 解析: {e}"))?;
    if resp.points.is_empty() {
        return Err("points 为空".into());
    }
    let mut out = Vec::new();
    for p in resp.points {
        let text = p.text.trim();
        if text.is_empty() {
            continue;
        }
        let ts = p.ts_ms.clamp(seg.start_ms, seg.end_ms.max(seg.start_ms));
        // 就近回链：ts 之前最后一页。
        let frame_ref = seg
            .parts
            .iter()
            .filter(|part| part.ts_ms <= ts)
            .last()
            .or(seg.parts.first())
            .and_then(|part| part.frame_ref.clone());
        out.push(SummaryPoint {
            text: text.chars().take(200).collect(),
            ts_ms: ts,
            frame_ref,
        });
    }
    if out.is_empty() {
        Err("有效要点为 0".into())
    } else {
        Ok(out)
    }
}

/// 多模态精修：段内前 3 个课件帧缩略图 → base64 JPEG（≤1024px, q80）。
fn load_segment_images(session_dir: &Path, seg: &SegmentInput) -> Vec<String> {
    use base64::Engine;
    let mut imgs = Vec::new();
    for part in &seg.parts {
        if imgs.len() >= 3 {
            break;
        }
        let Some(rel) = part.image_ref() else {
            continue;
        };
        let path = session_dir.join(rel);
        let Ok(bytes) = std::fs::read(&path) else { continue };
        let Ok(img) = image::load_from_memory(&bytes) else { continue };
        let img = if img.width().max(img.height()) > 1024 {
            img.resize(1024, 1024, image::imageops::FilterType::Triangle)
        } else {
            img
        };
        let mut buf = std::io::Cursor::new(Vec::new());
        if img
            .to_rgb8()
            .write_to(&mut buf, image::ImageFormat::Jpeg)
            .is_ok()
        {
            imgs.push(base64::engine::general_purpose::STANDARD.encode(buf.into_inner()));
        }
    }
    imgs
}

impl SegmentPart {
    /// 附图优先级：缩略图 > 原图。
    pub fn image_ref(&self) -> Option<&String> {
        self.thumb_ref.as_ref().or(self.frame_ref.as_ref())
    }
}

/// 对单个段跑 LLM；失败 → 本地兜底（不阻塞整课）。
fn summarize_segment(
    session_dir: &Path,
    cfg: &SummaryConfig,
    api_key: &str,
    seg: &SegmentInput,
    vlm_on: bool,
    trace: &str,
) -> SegmentSummary {
    let empty = seg.parts.iter().all(|p| p.texts.is_empty()
        && p.ocr_text.as_ref().map(|s| s.trim().is_empty()).unwrap_or(true));
    let mut local_fallback = false;
    let mut points = Vec::new();

    if empty {
        log::info!("[summary][{trace}] segment {} 无内容，跳过 LLM", seg.segment_id);
    } else {
        let use_vlm = vlm_on && cfg.vlm_model.is_some();
        let text = prompt::segment_user_text(seg);
        let messages = if use_vlm {
            let imgs = load_segment_images(session_dir, seg);
            vec![
                ChatMessage::system(prompt::SEGMENT_SYSTEM),
                ChatMessage::user_with_images(text, imgs),
            ]
        } else {
            prompt::segment_messages(seg)
        };
        let model = if use_vlm {
            cfg.vlm_model.as_deref().unwrap_or(&cfg.model)
        } else {
            &cfg.model
        };

        // 解析失败重试一次（模型偶尔不遵守 JSON 格式）；网络类失败 chat_blocking 内部已重试。
        let mut attempt = 0;
        loop {
            match chat_blocking(cfg, api_key, model, &messages) {
                Ok(raw) => match parse_segment_response(&raw, seg) {
                    Ok(pts) => {
                        points = pts;
                        break;
                    }
                    Err(e) => {
                        attempt += 1;
                        log::warn!(
                            "[summary][{trace}] segment {} 解析失败({}): {e}",
                            seg.segment_id, attempt
                        );
                        if attempt >= 2 {
                            local_fallback = true;
                            points = local_fallback_points(seg);
                            break;
                        }
                    }
                },
                Err(e) => {
                    // 错误已脱敏（cloud_api::sanitize），可安全进日志。
                    log::error!("[summary][{trace}] segment {} LLM 失败: {e}", seg.segment_id);
                    local_fallback = true;
                    points = local_fallback_points(seg);
                    break;
                }
            }
        }
    }

    SegmentSummary {
        segment_id: seg.segment_id,
        start_ms: seg.start_ms,
        end_ms: seg.end_ms,
        points,
        local_fallback,
    }
}

/// 全课总结主流程（blocking；调用方 spawn_blocking）。写 summary_draft.json。
pub fn run_summary(
    session_dir: &Path,
    cfg: &SummaryConfig,
    api_key: &str,
    vlm_on: bool,
    trace: &str,
) -> Result<SummaryDraft, String> {
    let units = read_aligned(session_dir)?;
    if units.is_empty() {
        return Err("aligned.json 无内容单元，无法总结".into());
    }
    let segments = build_segments(&units, cfg.max_segment_chars);
    log::info!(
        "[summary][{trace}] map-reduce: {} units → {} segments (≤{} 字/段)",
        units.len(),
        segments.len(),
        cfg.max_segment_chars
    );

    let mut seg_summaries = Vec::new();
    for (i, seg) in segments.iter().enumerate() {
        if i > 0 {
            std::thread::sleep(std::time::Duration::from_millis(300)); // 礼貌间隔，限流姿态
        }
        seg_summaries.push(summarize_segment(session_dir, cfg, api_key, seg, vlm_on, trace));
    }

    let outline = run_reduce(cfg, api_key, &seg_summaries, trace);
    let fallback = seg_summaries.iter().any(|s| s.local_fallback);
    let version = super::load_summary_file(session_dir)
        .current
        .map(|d| d.version + 1)
        .unwrap_or(1);
    let draft = SummaryDraft {
        version,
        model: cfg.model.clone(),
        fallback,
        segments: seg_summaries,
        outline,
    };
    save_new_draft(session_dir, draft.clone())?;
    Ok(draft)
}

/// 可纠错：segment_id = Some → 只重生成该段（再跑一次合并）；None → 全部重来。
pub fn regenerate(
    session_dir: &Path,
    cfg: &SummaryConfig,
    api_key: &str,
    segment_id: Option<usize>,
    vlm_on: bool,
    trace: &str,
) -> Result<SummaryDraft, String> {
    let Some(id) = segment_id else {
        return run_summary(session_dir, cfg, api_key, vlm_on, trace);
    };
    let file = super::load_summary_file(session_dir);
    let Some(mut draft) = file.current else {
        return Err("尚无草稿可纠错，请先 summarize".into());
    };
    let units = read_aligned(session_dir)?;
    let segments = build_segments(&units, cfg.max_segment_chars);
    let Some(seg) = segments.iter().find(|s| s.segment_id == id) else {
        return Err(format!("segment_id {id} 不存在（共 {} 段）", segments.len()));
    };
    let new_seg = summarize_segment(session_dir, cfg, api_key, seg, vlm_on, trace);
    match draft.segments.iter_mut().find(|s| s.segment_id == id) {
        Some(slot) => *slot = new_seg,
        None => draft.segments.push(new_seg),
    }
    draft.segments.sort_by_key(|s| s.segment_id);
    draft.outline = run_reduce(cfg, api_key, &draft.segments, trace);
    draft.fallback = draft.segments.iter().any(|s| s.local_fallback);
    draft.version += 1;
    save_new_draft(session_dir, draft.clone())?;
    Ok(draft)
}

/// reduce：分段要点 → 全局大纲。失败不阻塞（outline 空数组）。
fn run_reduce(
    cfg: &SummaryConfig,
    api_key: &str,
    segments: &[SegmentSummary],
    trace: &str,
) -> Vec<String> {
    if segments.iter().all(|s| s.points.is_empty()) {
        return Vec::new();
    }
    let messages = prompt::merge_messages(segments);
    match chat_blocking(cfg, api_key, &cfg.model, &messages) {
        Ok(raw) => match prompt::extract_json_object(&raw)
            .ok_or("no json".to_string())
            .and_then(|j| serde_json::from_str::<MergeResponse>(&j).map_err(|e| e.to_string()))
        {
            Ok(resp) => resp.outline,
            Err(e) => {
                log::warn!("[summary][{trace}] reduce 解析失败: {e}");
                Vec::new()
            }
        },
        Err(e) => {
            log::error!("[summary][{trace}] reduce LLM 失败: {e}");
            Vec::new()
        }
    }
}

fn read_aligned(session_dir: &Path) -> Result<Vec<AlignedUnit>, String> {
    let path = session_dir.join("aligned.json");
    let text = std::fs::read_to_string(&path)
        .map_err(|_| "缺少 aligned.json —— 请先完成录后处理（抽帧/对齐）".to_string())?;
    serde_json::from_str(&text).map_err(|e| format!("aligned.json 解析失败: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn unit(ts: i64, end: i64, ocr: Option<&str>, texts: Vec<&str>) -> AlignedUnit {
        AlignedUnit {
            frame_ts: Some(ts),
            orig_path: Some(format!("frames/page_{ts}.jpg")),
            thumb_path: None,
            ocr_text: ocr.map(|s| s.to_string()),
            start_ms: ts,
            end_ms: end,
            texts: texts.into_iter().map(|s| s.to_string()).collect(),
        }
    }

    #[test]
    fn segments_respect_char_budget() {
        // 每 unit 100 字 ocr，max 250 → 每段最多 2 个 unit。
        let units: Vec<_> = (0..5)
            .map(|i| unit(i * 60_000, (i + 1) * 60_000, Some(&"字".repeat(100)), vec![]))
            .collect();
        let segs = build_segments(&units, 250);
        assert_eq!(segs.len(), 3); // 2+2+1
        assert_eq!(segs[0].parts.len(), 2);
        assert_eq!(segs[2].parts.len(), 1);
        assert_eq!(segs[0].segment_id, 0);
        assert_eq!(segs[1].start_ms, 120_000);
    }

    #[test]
    fn segments_oversize_unit_truncated() {
        let units = vec![unit(0, 60_000, Some(&"甲".repeat(500)), vec!["乙".repeat(300).as_str()])];
        let segs = build_segments(&units, 200);
        assert_eq!(segs.len(), 1);
        let p = &segs[0].parts[0];
        assert_eq!(p.ocr_text.as_ref().unwrap().chars().count(), 200);
        assert!(p.texts.is_empty()); // 预算被 OCR 吃完
    }

    #[test]
    fn empty_input_no_segments() {
        assert!(build_segments(&[], 2000).is_empty());
    }

    #[test]
    fn local_fallback_extracts_first_sentences() {
        let seg = SegmentInput {
            segment_id: 0,
            start_ms: 0,
            end_ms: 10_000,
            parts: vec![SegmentPart {
                ts_ms: 1000,
                frame_ref: Some("f.jpg".into()),
                thumb_ref: None,
                ocr_text: None,
                texts: vec!["第一句。第二句。第三句。".into()],
            }],
        };
        let pts = local_fallback_points(&seg);
        assert_eq!(pts.len(), 1);
        assert_eq!(pts[0].text, "第一句");
        assert_eq!(pts[0].ts_ms, 1000);
        assert_eq!(pts[0].frame_ref.as_deref(), Some("f.jpg"));
    }

    #[test]
    fn parse_response_clamps_ts_and_links_frame() {
        let seg = SegmentInput {
            segment_id: 0,
            start_ms: 10_000,
            end_ms: 20_000,
            parts: vec![
                SegmentPart { ts_ms: 10_000, frame_ref: Some("a.jpg".into()), thumb_ref: None, ocr_text: None, texts: vec![] },
                SegmentPart { ts_ms: 15_000, frame_ref: Some("b.jpg".into()), thumb_ref: None, ocr_text: None, texts: vec![] },
            ],
        };
        // ts 越界（< start）→ 截断到段起点；就近回链 a.jpg。
        let pts = parse_segment_response(
            r#"{"points":[{"text":"要点X","ts_ms":5},{"text":"要点Y","ts_ms":16000}]}"#,
            &seg,
        )
        .unwrap();
        assert_eq!(pts[0].ts_ms, 10_000);
        assert_eq!(pts[0].frame_ref.as_deref(), Some("a.jpg"));
        assert_eq!(pts[1].frame_ref.as_deref(), Some("b.jpg"));
        assert!(parse_segment_response("垃圾输出", &seg).is_err());
        assert!(parse_segment_response(r#"{"points":[]}"#, &seg).is_err());
    }
}
