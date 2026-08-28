//! Step 9 (FR-108): 多形态渲染 + Markdown 导出。
//!
//! 输入是 Step 8 的草稿（`summary_draft.json` 的 current），输出两类产物：
//! - `Timeline`：章节 → 要点[时间戳] + 课件帧引用，供前端 Study.vue 消费（`get_timeline`）；
//! - Markdown：章节 / 要点 / 时间戳 / 课件帧引用，落 `exports/summary.md`（`export_markdown`）。
//! 导图 / Anki（PRD Should 级）本步只留接口签名，后续迭代实现。
//!
//! 安全：全部落本地 exports/，不产生任何网络请求；frame_ref 只作相对路径拼接，不出 session 目录。

use serde::Serialize;
use std::path::{Path, PathBuf};

use crate::summary::{load_summary_file, FinalSummary, SummaryDraft};

/// 导出的 Markdown 文件名（固定在 session_dir/exports/ 下）。
pub const EXPORT_MD_NAME: &str = "summary.md";

/// 时间轴上的一个要点（供前端渲染 + 点击跳回视频时刻）。
#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct TimelinePoint {
    pub text: String,
    pub ts_ms: i64,
    /// mm:ss / h:mm:ss 展示用时间戳（前端免格式化）。
    pub ts_label: String,
    /// 课件帧相对 session 的路径；None = 该要点无帧回链（固定窗降级产物）。
    pub frame_ref: Option<String>,
}

/// 时间轴章节：对应 Step 8 的一个分段（map-reduce 的段即章）。
#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct TimelineChapter {
    pub segment_id: usize,
    pub title: String,
    pub start_ms: i64,
    pub end_ms: i64,
    /// true = 本章是本地兜底产物（LLM 失败降级），前端可打标提示。
    pub local_fallback: bool,
    /// 章节总结（2026-08-20）：完全遵照选中侧重点生成的本章自由文本总结；可为空串。
    pub chapter_summary: String,
    pub points: Vec<TimelinePoint>,
}

/// `get_timeline` 返回的整体结构。
#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct Timeline {
    /// 草稿版本号 + 生成模型（供 SummaryPanel 草稿态提示）。
    pub version: u32,
    pub model: String,
    /// 全局大纲（reduce 产物；为空数组表示 reduce 失败降级）。
    pub outline: Vec<String>,
    /// true = 整体或部分走了本地兜底。
    pub fallback: bool,
    pub chapters: Vec<TimelineChapter>,
    /// 汇总定稿（2026-08-21）：点「汇总定稿」后有值；未汇总为 None。
    pub final_summary: Option<FinalSummary>,
}

/// 章节标题：大纲与分段一一对应时取大纲条目，否则退化为「章节 N」。
fn chapter_title(draft: &SummaryDraft, idx: usize, segment_id: usize) -> String {
    if draft.outline.len() == draft.segments.len() {
        draft.outline[idx].clone()
    } else {
        format!("章节 {}", segment_id + 1)
    }
}

/// 毫秒时间戳 → 展示串：<1h 用 mm:ss，≥1h 用 h:mm:ss（网课常见 1-2h）。
pub fn fmt_ts(ms: i64) -> String {
    let total_secs = ms.max(0) / 1000;
    let h = total_secs / 3600;
    let m = (total_secs % 3600) / 60;
    let s = total_secs % 60;
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m:02}:{s:02}")
    }
}

/// 从当前草稿构建时间轴。无草稿（尚未 summarize）返回 Err。
pub fn build_timeline(session_dir: &Path) -> Result<Timeline, String> {
    let draft = load_summary_file(session_dir)
        .current
        .ok_or("尚无总结草稿 —— 请先执行总结（summarize）")?;
    let chapters = draft
        .segments
        .iter()
        .enumerate()
        .map(|(idx, seg)| TimelineChapter {
            segment_id: seg.segment_id,
            title: chapter_title(&draft, idx, seg.segment_id),
            start_ms: seg.start_ms,
            end_ms: seg.end_ms,
            local_fallback: seg.local_fallback,
            chapter_summary: seg.chapter_summary.clone(),
            points: seg
                .points
                .iter()
                .map(|p| TimelinePoint {
                    text: p.text.clone(),
                    ts_ms: p.ts_ms,
                    ts_label: fmt_ts(p.ts_ms),
                    frame_ref: p.frame_ref.clone(),
                })
                .collect(),
        })
        .collect();
    Ok(Timeline {
        version: draft.version,
        model: draft.model.clone(),
        outline: draft.outline.clone(),
        fallback: draft.fallback,
        chapters,
        final_summary: draft.final_summary.clone(),
    })
}

/// 渲染 Markdown 文本（纯函数，便于测试）。
/// 课件帧引用写成相对 exports/ 的图片链接（`../frames/...`），Typora/Obsidian 直接可见。
pub fn render_markdown(session_id: &str, draft: &SummaryDraft) -> String {
    let timeline_chapters: Vec<(String, &crate::summary::SegmentSummary)> = draft
        .segments
        .iter()
        .enumerate()
        .map(|(idx, seg)| (chapter_title(draft, idx, seg.segment_id), seg))
        .collect();

    let mut md = String::new();
    md.push_str(&format!("# 网课总结 — {session_id}\n\n"));
    md.push_str(&format!("- 模型：{}\n", draft.model));
    md.push_str(&format!("- 草稿版本：v{}\n", draft.version));
    if draft.fallback {
        md.push_str("- ⚠️ 本次总结包含本地兜底内容（云端 API 失败降级，要点为本地抽取，非 AI 生成）\n");
    }
    md.push('\n');

    if !draft.outline.is_empty() {
        md.push_str("## 大纲\n\n");
        for line in &draft.outline {
            md.push_str(&format!("- {line}\n"));
        }
        md.push('\n');
    }

    // 汇总定稿（若有）放在最前——这是去重后的最终版，导出供复习的主产物。
    if let Some(fin) = &draft.final_summary {
        md.push_str("## 汇总定稿（去重后最终章节）\n\n");
        for c in &fin.chapters {
            md.push_str(&format!(
                "### {}（{} - {}）\n\n{}\n\n",
                c.title,
                fmt_ts(c.start_ms),
                fmt_ts(c.end_ms),
                c.summary
            ));
        }
    }

    md.push_str("## 章节要点\n\n");
    for (title, seg) in timeline_chapters {
        md.push_str(&format!(
            "### {}（{} - {}）\n\n",
            title,
            fmt_ts(seg.start_ms),
            fmt_ts(seg.end_ms)
        ));
        if !seg.chapter_summary.trim().is_empty() {
            md.push_str(&format!("**章节总结**\n\n{}\n\n", seg.chapter_summary.trim()));
        }
        for p in &seg.points {
            md.push_str(&format!("- [{}] {}\n", fmt_ts(p.ts_ms), p.text));
            if let Some(frame) = &p.frame_ref {
                md.push_str(&format!("  ![课件帧](../{frame})\n"));
            }
        }
        md.push('\n');
    }
    md
}

/// 导出 Markdown 到 session_dir/exports/summary.md，返回文件完整路径。
pub fn export_markdown(session_dir: &Path, session_id: &str) -> Result<PathBuf, String> {
    let draft = load_summary_file(session_dir)
        .current
        .ok_or("尚无总结草稿 —— 请先执行总结（summarize）")?;
    let exports_dir = session_dir.join("exports");
    std::fs::create_dir_all(&exports_dir).map_err(|e| format!("create exports dir: {e}"))?;
    let path = exports_dir.join(EXPORT_MD_NAME);
    let md = render_markdown(session_id, &draft);
    std::fs::write(&path, md).map_err(|e| format!("write markdown: {e}"))?;
    log::info!(
        "[session][{session_id}] markdown exported: {} bytes -> {}",
        path.metadata().map(|m| m.len()).unwrap_or(0),
        path.display()
    );
    Ok(path)
}

/// 汇总定稿 Markdown 导出（2026-08-22 用户要求）：
/// - 文件名 = 全课总标题（清洗非法字符，兜底「网课总结」）；
/// - 每个最终章节：`## 标题（起 - 止）` + 本章总结 + 带时间戳要点；
/// - **重复图片只呈现一次**：同一课件帧全课首次出现时贴图，之后仅文字要点。
/// 落 `exports/<总标题>.md`，返回完整路径。无定稿 → Err。
pub fn export_final_markdown(session_dir: &Path) -> Result<PathBuf, String> {
    let draft = load_summary_file(session_dir)
        .current
        .ok_or("尚无总结草稿 —— 请先执行总结（summarize）")?;
    let fin = draft
        .final_summary
        .as_ref()
        .ok_or("尚无汇总定稿 —— 请先点「汇总定稿」")?;
    let md = render_final_markdown_body(&draft);
    let exports_dir = session_dir.join("exports");
    std::fs::create_dir_all(&exports_dir).map_err(|e| format!("create exports dir: {e}"))?;
    let path = exports_dir.join(format!("{}.md", sanitize_filename(&fin.title)));
    std::fs::write(&path, md).map_err(|e| format!("write final markdown: {e}"))?;
    log::info!("[summary] final markdown exported -> {}", path.display());
    Ok(path)
}

/// 文件名清洗：去掉 Windows 非法字符与首尾空白，限长 60 字（超长截断）。
fn sanitize_filename(name: &str) -> String {
    let cleaned: String = name
        .trim()
        .chars()
        .map(|c| match c {
            '\\' | '/' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => ' ',
            _ => c,
        })
        .collect();
    let cleaned = cleaned.trim();
    if cleaned.is_empty() {
        "网课总结".to_string()
    } else {
        cleaned.chars().take(60).collect()
    }
}

/// 汇总定稿正文（纯函数，便于测试）。
fn render_final_markdown_body(draft: &SummaryDraft) -> String {
    let fin = draft.final_summary.as_ref().expect("caller checked");
    let segs: std::collections::HashMap<usize, &crate::summary::SegmentSummary> = draft
        .segments
        .iter()
        .map(|s| (s.segment_id, s))
        .collect();
    let mut seen_frames = std::collections::HashSet::new();
    let mut md = String::new();
    md.push_str(&format!("# {}\n\n", fin.title.trim()));
    for c in &fin.chapters {
        md.push_str(&format!(
            "## {}（{} - {}）\n\n**本章总结**\n\n{}\n\n**要点**\n",
            c.title,
            fmt_ts(c.start_ms),
            fmt_ts(c.end_ms),
            c.summary.trim()
        ));
        for &sid in &c.merged_segment_ids {
            if let Some(seg) = segs.get(&sid) {
                for p in &seg.points {
                    md.push_str(&format!("- [{}] {}\n", fmt_ts(p.ts_ms), p.text));
                    if let Some(fr) = &p.frame_ref {
                        if seen_frames.insert(fr.clone()) {
                            md.push_str(&format!("  ![课件帧](../{fr})\n"));
                        }
                    }
                }
            }
        }
        md.push('\n');
    }
    md
}



// ---- 预留接口（PRD Should 级：导图 / Anki）----

/// TODO(Should): 渲染 markmap 兼容的层级 Markdown（思维导图）。
#[allow(dead_code)] // 预留接口（PRD Should），本步只留签名。
pub fn render_markmap(_draft: &SummaryDraft) -> Result<String, String> {
    Err("markmap 导出为 Should 级需求，本迭代未实现".into())
}

/// TODO(Should): 渲染 Anki 卡片（要点 → 问答对，TSV/CSV）。
#[allow(dead_code)] // 预留接口（PRD Should），本步只留签名。
pub fn render_anki(_draft: &SummaryDraft) -> Result<String, String> {
    Err("Anki 导出为 Should 级需求，本迭代未实现".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::summary::{save_new_draft, SegmentSummary, SummaryPoint};

    fn sample_draft() -> SummaryDraft {
        SummaryDraft {
            version: 1,
            model: "k3-256k".into(),
            fallback: false,
            outline: vec!["第一章 引入".into(), "第二章 推导".into()],
            final_summary: None,
            segments: vec![
                SegmentSummary {
                    segment_id: 0,
                    start_ms: 0,
                    end_ms: 300_000,
                    local_fallback: false,
                    chapter_summary: "本章围绕考试考点展开，重点是网络分类。".into(),
                    points: vec![SummaryPoint {
                        text: "课程目标介绍".into(),
                        ts_ms: 65_000,
                        frame_ref: Some("frames/change_0001.jpg".into()),
                    }],
                },
                SegmentSummary {
                    segment_id: 1,
                    start_ms: 300_000,
                    end_ms: 3_900_000,
                    local_fallback: true,
                    chapter_summary: String::new(),
                    points: vec![SummaryPoint {
                        text: "公式推导过程".into(),
                        ts_ms: 3_661_000,
                        frame_ref: None,
                    }],
                },
            ],
        }
    }

    fn draft_in(dir: &Path) {
        std::fs::create_dir_all(dir).unwrap();
        save_new_draft(dir, sample_draft()).unwrap();
    }

    #[test]
    fn fmt_ts_minutes_and_hours() {
        assert_eq!(fmt_ts(0), "00:00");
        assert_eq!(fmt_ts(65_000), "01:05");
        assert_eq!(fmt_ts(3_661_000), "1:01:01");
        assert_eq!(fmt_ts(-5), "00:00"); // 负值防御
    }

    #[test]
    fn timeline_maps_segments_to_chapters_with_labels() {
        let dir = std::env::temp_dir().join(format!("vtt_render_tl_{}", std::process::id()));
        draft_in(&dir);
        let tl = build_timeline(&dir).unwrap();
        assert_eq!(tl.outline.len(), 2);
        assert!(!tl.fallback);
        assert_eq!(tl.chapters.len(), 2);
        // 大纲与分段一一对应 → 章节标题取大纲。
        assert_eq!(tl.chapters[0].title, "第一章 引入");
        assert_eq!(tl.chapters[0].points[0].ts_label, "01:05");
        assert_eq!(
            tl.chapters[0].points[0].frame_ref.as_deref(),
            Some("frames/change_0001.jpg")
        );
        // 本地兜底段如实打标。
        assert!(tl.chapters[1].local_fallback);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn timeline_without_draft_errors() {
        let dir = std::env::temp_dir().join(format!("vtt_render_nodraft_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let err = build_timeline(&dir).unwrap_err();
        assert!(err.contains("尚无总结草稿"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn timeline_title_falls_back_when_outline_mismatch() {
        // reduce 失败降级时 outline 为空 → 章节标题退化「章节 N」，不 panic。
        let mut d = sample_draft();
        d.outline = vec![];
        assert_eq!(chapter_title(&d, 0, 0), "章节 1");
        assert_eq!(chapter_title(&d, 1, 1), "章节 2");
    }

    #[test]
    fn markdown_contains_outline_points_ts_and_frame_link() {
        let md = render_markdown("sess-1", &sample_draft());
        assert!(md.starts_with("# 网课总结 — sess-1"));
        assert!(md.contains("## 大纲\n\n- 第一章 引入\n- 第二章 推导"));
        assert!(md.contains("### 第一章 引入（00:00 - 05:00）"));
        // 章节总结输出在章标题下、要点前；空串的章节不输出该块。
        assert!(md.contains("**章节总结**\n\n本章围绕考试考点展开，重点是网络分类。"));
        assert!(!md.contains("**章节总结**\n\n\n"));
        assert!(md.contains("- [01:05] 课程目标介绍"));
        assert!(md.contains("![课件帧](../frames/change_0001.jpg)"));
        assert!(md.contains("- [1:01:01] 公式推导过程"));
        // 非 fallback 草稿不带降级提示。
        assert!(!md.contains("本地兜底"));
    }

    #[test]
    fn markdown_flags_fallback_draft() {
        let mut d = sample_draft();
        d.fallback = true;
        let md = render_markdown("s", &d);
        assert!(md.contains("⚠️ 本次总结包含本地兜底内容"));
    }

    /// 汇总定稿 Markdown：文件名 = 总标题（清洗非法字符）；重复帧图只贴一次。
    #[test]
    fn export_final_markdown_uses_title_and_dedupes_frames() {
        let dir = std::env::temp_dir().join(format!("vtt_render_fin_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut d = sample_draft();
        // 第 2 章要点也引用同一帧 → 验证去重只贴一张。
        d.segments[1].points[0].frame_ref = Some("frames/change_0001.jpg".into());
        d.final_summary = Some(crate::summary::FinalSummary {
            title: "计算机网络：基础与拓扑/进阶".into(),
            chapters: vec![crate::summary::FinalChapter {
                title: "第一章合并".into(),
                start_ms: 0,
                end_ms: 3_900_000,
                merged_segment_ids: vec![0, 1],
                summary: "合并后的全章总结。".into(),
            }],
        });
        save_new_draft(&dir, d).unwrap();
        let path = export_final_markdown(&dir).unwrap();
        let name = path.file_name().unwrap().to_string_lossy().to_string();
        assert!(name.starts_with("计算机网络：基础与拓扑 进阶"), "非法字符被清洗: {name}");
        assert!(name.ends_with(".md"));
        let md = std::fs::read_to_string(&path).unwrap();
        assert!(md.starts_with("# 计算机网络：基础与拓扑/进阶"));
        assert!(md.contains("## 第一章合并（00:00 - 1:05:00）"));
        assert!(md.contains("**本章总结**\n\n合并后的全章总结。"));
        // 两章要点各引用同一帧一次 → 全文只贴一张图。
        assert_eq!(md.matches("![课件帧]").count(), 1, "重复帧图应只贴一次: {md}");
        // 无定稿 → Err。
        let d2 = std::env::temp_dir().join(format!("vtt_render_fin2_{}", std::process::id()));
        std::fs::create_dir_all(&d2).unwrap();
        draft_in(&d2);
        assert!(export_final_markdown(&d2).unwrap_err().contains("尚无汇总定稿"));
        let _ = std::fs::remove_dir_all(&dir);
        let _ = std::fs::remove_dir_all(&d2);
    }

    #[test]
    fn export_writes_file_under_exports() {
        let dir = std::env::temp_dir().join(format!("vtt_render_exp_{}", std::process::id()));
        draft_in(&dir);
        let path = export_markdown(&dir, "sess-x").unwrap();
        assert!(path.ends_with("exports/summary.md") || path.ends_with(r"exports\summary.md"));
        let content = std::fs::read_to_string(&path).unwrap();
        assert!(content.contains("# 网课总结 — sess-x"));
        // 无草稿 session 报错。
        let empty = std::env::temp_dir().join(format!("vtt_render_exp2_{}", std::process::id()));
        std::fs::create_dir_all(&empty).unwrap();
        assert!(export_markdown(&empty, "s").unwrap_err().contains("尚无总结草稿"));
        let _ = std::fs::remove_dir_all(&dir);
        let _ = std::fs::remove_dir_all(&empty);
    }
}
