//! Step 7 (FR-106): 音字帧对齐 —— 转写句 × 课件帧按时间区间归并，
//! 产出 `aligned.json`（每个课件页 + 其展示时间段内的讲解文字），供 Step 8 总结使用。
//!
//! 归并语义（plan「排序归并 O(n+m)」）：
//! - 页区间：frame_i 展示区间为 [frame_ts_i, frame_ts_{i+1})，末页到转写末尾。
//! - 句子归属：**中点规则** —— 句子中点落在哪个页区间就归哪页（翻页跨句只归一页，
//!   保证 O(n+m) 双指针、句不重不漏）。
//! - 降级（plan 三条路径之二）：
//!   1. 无变化帧（frames 空）→ 按 5min 固定窗分段（frame 引用为 None）；
//!   2. 转写缺失（整文件没有或某段没有）→ 对应 unit `texts` 为空 = 「无讲解文字」。
//! - 与 Step 6 的合并策略（plan 备注择一）：align 读 frames.json **当下快照**；
//!   OCR 后回填想要 ocr_text 进 aligned.json → 重跑 align_session 即可（纯逻辑，廉价）。

use crate::screen::scene_detect::FrameEntry;
use serde::{Deserialize, Serialize};
use std::path::Path;

/// 无帧降级的固定窗宽（plan：5min）。
pub const FALLBACK_WINDOW_MS: i64 = 5 * 60 * 1000;

/// transcript.jsonl 一行（与 lib.rs TranscriptLine 同构；独立定义避免反向依赖）。
#[derive(Debug, Clone, Deserialize)]
pub struct TranscriptSeg {
    pub start_ms: i64,
    pub end_ms: i64,
    pub text: String,
}

/// aligned.json 一个单元。`texts` 为空 = 「无讲解文字」（降级标注，见模块文档）。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AlignedUnit {
    /// 课件页时间戳；固定窗降级时为 None。
    pub frame_ts: Option<i64>,
    pub orig_path: Option<String>,
    pub thumb_path: Option<String>,
    pub ocr_text: Option<String>,
    pub start_ms: i64,
    pub end_ms: i64,
    pub texts: Vec<String>,
}

/// 纯逻辑对齐：frames + transcript → aligned_units。O(n+m)，不碰文件系统。
/// 输入可乱序（内部防御性排序）。
pub fn align(frames: &[FrameEntry], transcript: &[TranscriptSeg]) -> Vec<AlignedUnit> {
    let mut segs: Vec<&TranscriptSeg> = transcript.iter().collect();
    segs.sort_by_key(|s| s.start_ms);

    if frames.is_empty() {
        return align_fixed_windows(&segs);
    }

    let mut fs: Vec<&FrameEntry> = frames.iter().collect();
    fs.sort_by_key(|f| f.frame_ts);

    // 页区间右边界：下一页 ts；末页 = 转写最大 end（无转写则等于自身 ts）。
    let last_end = segs.iter().map(|s| s.end_ms).max().unwrap_or(0);
    let boundary = |i: usize| -> i64 {
        if i + 1 < fs.len() {
            fs[i + 1].frame_ts
        } else {
            last_end.max(fs[i].frame_ts)
        }
    };

    let mut units: Vec<AlignedUnit> = fs
        .iter()
        .enumerate()
        .map(|(i, f)| AlignedUnit {
            frame_ts: Some(f.frame_ts),
            orig_path: Some(f.orig_path.clone()),
            thumb_path: Some(f.thumb_path.clone()),
            ocr_text: f.ocr_text.clone(),
            start_ms: f.frame_ts,
            end_ms: boundary(i),
            texts: Vec::new(),
        })
        .collect();

    // 双指针：句子中点落入页区间即归属。
    let mut ui = 0usize;
    for seg in segs {
        let mid = (seg.start_ms + seg.end_ms) / 2;
        while ui + 1 < units.len() && mid >= units[ui].end_ms {
            ui += 1;
        }
        // 中点早于第一页起始的句子（录屏前说话了）归第一页 —— 不丢句。
        units[ui].texts.push(seg.text.clone());
    }
    units
}

/// 降级路径 1：无变化帧 → 5min 固定窗分段，句子按中点归窗。
fn align_fixed_windows(segs: &[&TranscriptSeg]) -> Vec<AlignedUnit> {
    if segs.is_empty() {
        return Vec::new();
    }
    let last_end = segs.iter().map(|s| s.end_ms).max().unwrap_or(0);
    let n_windows = (last_end / FALLBACK_WINDOW_MS + 1) as usize;
    let mut units: Vec<AlignedUnit> = (0..n_windows)
        .map(|i| AlignedUnit {
            frame_ts: None,
            orig_path: None,
            thumb_path: None,
            ocr_text: None,
            start_ms: i as i64 * FALLBACK_WINDOW_MS,
            end_ms: (i as i64 + 1) * FALLBACK_WINDOW_MS,
            texts: Vec::new(),
        })
        .collect();
    for seg in segs {
        let mid = (seg.start_ms + seg.end_ms) / 2;
        let wi = ((mid / FALLBACK_WINDOW_MS) as usize).min(units.len() - 1);
        units[wi].texts.push(seg.text.clone());
    }
    units
}

/// 编排：读 transcript.jsonl + frames.json → align → 写 aligned.json。返回单元数。
/// transcript.jsonl 缺失 → 按空转写降级（全部「无讲解文字」），不报错（O4）。
/// frames.json 缺失 → 按无帧降级（固定窗），不报错。
pub fn align_session(session_dir: &Path, trace: &str) -> Result<usize, String> {
    let transcript = read_transcript(session_dir, trace)?;
    let frames = read_frames(session_dir, trace)?;
    let units = align(&frames, &transcript);
    let json = serde_json::to_string_pretty(&units).map_err(|e| e.to_string())?;
    std::fs::write(session_dir.join("aligned.json"), json)
        .map_err(|e| format!("write aligned.json: {e}"))?;
    log::info!(
        "[align][{trace}] aligned.json: {} units (frames={} segs={})",
        units.len(),
        frames.len(),
        transcript.len()
    );
    Ok(units.len())
}

fn read_transcript(session_dir: &Path, trace: &str) -> Result<Vec<TranscriptSeg>, String> {
    let path = session_dir.join("transcript.jsonl");
    let raw = match std::fs::read_to_string(&path) {
        Ok(r) => r,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            log::warn!("[align][{trace}] 无 transcript.jsonl → 全部单元「无讲解文字」");
            return Ok(Vec::new());
        }
        Err(e) => return Err(format!("read transcript.jsonl: {e}")),
    };
    let mut segs = Vec::new();
    for (i, line) in raw.lines().enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        match serde_json::from_str::<TranscriptSeg>(line) {
            Ok(s) => segs.push(s),
            // 单行损坏只丢该行（O4），不致整课对齐失败。
            Err(e) => log::warn!("[align][{trace}] transcript 第{}行损坏跳过: {e}", i + 1),
        }
    }
    Ok(segs)
}

fn read_frames(session_dir: &Path, trace: &str) -> Result<Vec<FrameEntry>, String> {
    let path = session_dir.join("frames.json");
    let raw = match std::fs::read_to_string(&path) {
        Ok(r) => r,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            log::warn!("[align][{trace}] 无 frames.json → 5min 固定窗降级");
            return Ok(Vec::new());
        }
        Err(e) => return Err(format!("read frames.json: {e}")),
    };
    serde_json::from_str(&raw).map_err(|e| format!("parse frames.json: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(ts: i64) -> FrameEntry {
        FrameEntry {
            frame_ts: ts,
            orig_path: format!("page_{ts}.jpg"),
            thumb_path: format!("thumb_{ts}.jpg"),
            ocr_text: None,
        }
    }

    fn seg(start: i64, end: i64, text: &str) -> TranscriptSeg {
        TranscriptSeg { start_ms: start, end_ms: end, text: text.into() }
    }

    #[test]
    fn basic_grouping_and_intervals() {
        // 3 页 0/60s/120s，4 句 → 各归其页；末页 end = 转写末尾。
        let frames = vec![frame(0), frame(60_000), frame(120_000)];
        let segs = vec![
            seg(1_000, 5_000, "开场"),
            seg(61_000, 65_000, "第二页讲这个"),
            seg(90_000, 95_000, "还在第二页"),
            seg(121_000, 130_000, "第三页"),
        ];
        let units = align(&frames, &segs);
        assert_eq!(units.len(), 3);
        assert_eq!((units[0].start_ms, units[0].end_ms), (0, 60_000));
        assert_eq!((units[1].start_ms, units[1].end_ms), (60_000, 120_000));
        assert_eq!((units[2].start_ms, units[2].end_ms), (120_000, 130_000));
        assert_eq!(units[0].texts, ["开场"]);
        assert_eq!(units[1].texts, ["第二页讲这个", "还在第二页"]);
        assert_eq!(units[2].texts, ["第三页"]);
    }

    #[test]
    fn midpoint_rule_for_page_crossing_sentence() {
        // 句子横跨翻页点：中点在左页 → 归左页，且只归一页。
        let frames = vec![frame(0), frame(60_000)];
        let segs = vec![seg(55_000, 62_000, "跨页句")]; // 中点 58.5s < 60s
        let units = align(&frames, &segs);
        assert_eq!(units[0].texts, ["跨页句"]);
        assert!(units[1].texts.is_empty(), "跨页句只归中点所在页");
        // 中点在右页则归右页
        let segs2 = vec![seg(55_000, 70_000, "跨页句2")]; // 中点 62.5s
        let units2 = align(&frames, &segs2);
        assert!(units2[0].texts.is_empty());
        assert_eq!(units2[1].texts, ["跨页句2"]);
    }

    #[test]
    fn sentence_before_first_frame_goes_to_first_page() {
        let frames = vec![frame(10_000)];
        let segs = vec![seg(0, 5_000, "录屏前的开场白")];
        let units = align(&frames, &segs);
        assert_eq!(units[0].texts, ["录屏前的开场白"], "不丢句");
    }

    #[test]
    fn degrade_no_frames_fixed_windows() {
        let segs = vec![
            seg(1_000, 2_000, "第一窗"),
            seg(301_000, 302_000, "第二窗"), // 5min 窗边界 300_000
        ];
        let units = align(&[], &segs);
        assert_eq!(units.len(), 2, "302s → 2 个 5min 窗");
        assert!(units[0].frame_ts.is_none());
        assert_eq!(units[0].texts, ["第一窗"]);
        assert_eq!(units[1].texts, ["第二窗"]);
        assert_eq!(units[1].start_ms, FALLBACK_WINDOW_MS);
    }

    #[test]
    fn degrade_no_transcript_marks_empty_texts() {
        let frames = vec![frame(0), frame(60_000)];
        let units = align(&frames, &[]);
        assert_eq!(units.len(), 2);
        assert!(units.iter().all(|u| u.texts.is_empty()), "无讲解文字");
        assert!(units.iter().all(|u| u.ocr_text.is_none() || u.orig_path.is_some()));
    }

    #[test]
    fn unsorted_inputs_are_sorted() {
        let frames = vec![frame(60_000), frame(0)];
        let segs = vec![seg(61_000, 62_000, "后"), seg(1_000, 2_000, "先")];
        let units = align(&frames, &segs);
        assert_eq!(units[0].start_ms, 0);
        assert_eq!(units[0].texts, ["先"]);
        assert_eq!(units[1].texts, ["后"]);
    }

    #[test]
    fn empty_both_gives_empty() {
        assert!(align(&[], &[]).is_empty());
    }

    #[test]
    fn ocr_text_flows_into_unit() {
        let mut f = frame(0);
        f.ocr_text = Some("课件标题".into());
        let units = align(&[f], &[seg(0, 1000, "讲")]);
        assert_eq!(units[0].ocr_text.as_deref(), Some("课件标题"));
    }

    #[test]
    fn align_session_files_roundtrip_and_degrade() {
        let dir = std::env::temp_dir().join(format!("align_test_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        // 有 frames.json，无 transcript.jsonl → 降级不报错
        let frames = vec![frame(0)];
        std::fs::write(
            dir.join("frames.json"),
            serde_json::to_string(&frames).unwrap(),
        )
        .unwrap();
        let n = align_session(&dir, "test").unwrap();
        assert_eq!(n, 1);
        let units: Vec<AlignedUnit> = serde_json::from_str(
            &std::fs::read_to_string(dir.join("aligned.json")).unwrap(),
        )
        .unwrap();
        assert!(units[0].texts.is_empty());

        // 补 transcript（含一行损坏）→ 正常对齐，坏行跳过
        std::fs::write(
            dir.join("transcript.jsonl"),
            "{\"start_ms\":0,\"end_ms\":1000,\"text\":\"好句\"}\n{坏行}\n",
        )
        .unwrap();
        let n = align_session(&dir, "test").unwrap();
        assert_eq!(n, 1);
        let units: Vec<AlignedUnit> = serde_json::from_str(
            &std::fs::read_to_string(dir.join("aligned.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(units[0].texts, ["好句"]);

        // frames.json 也无 → 固定窗
        std::fs::remove_file(dir.join("frames.json")).unwrap();
        let n = align_session(&dir, "test").unwrap();
        assert_eq!(n, 1, "1s 转写 → 1 个固定窗");
        std::fs::remove_dir_all(&dir).ok();
    }
}
