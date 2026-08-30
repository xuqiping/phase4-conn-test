//! 分段 / 全局合并 prompt（Step 8, FR-107）。
//!
//! 长文本「中段迷失」对策（plan 备注）：
//! - 分段 prompt 明确段区间，要求每条要点 ts_ms 必须落在区间内；
//! - 合并 prompt 要求按时间去重、保持时间顺序。

use super::cloud_api::ChatMessage;
use super::map_reduce::SegmentInput;
use super::{SegmentSummary, SummaryDraft};

/// 分段总结 system 提示词（Prompt Caching 友好：固定内容放 system/开头）。
pub const SEGMENT_SYSTEM: &str = r#"你是网课学习助手。给你一节课的一个时间段的材料（老师讲解转写文字 + 该时段课件页的 OCR 文字）。
任务：提炼这个时间段的要点。

输出要求（严格遵守）：
1. 只输出一个 JSON 对象，不要输出任何其他文字或 markdown 代码围栏：
   {"points": [{"text": "要点内容", "ts_ms": 12345}]}
2. 每条要点必须带 ts_ms（该要点内容在课里被讲到的毫秒时间戳），且必须落在我给你的段区间内。
3. 要点 3-8 条，每条一句话，说清「是什么/怎么做/注意什么」，不要照抄原文长句。
4. 讲解文字与课件 OCR 矛盾时以讲解为准；课件上独有但老师没讲的公式/定义也可提炼为要点。
5. 全部内容是中文课，要点用中文。"#;

/// 全局合并 system。
pub const MERGE_SYSTEM: &str = r#"你是网课学习助手。给你一节课所有分段提炼出的要点（带毫秒时间戳）。
任务：生成全课大纲。

输出要求（严格遵守）：
1. 只输出一个 JSON 对象：{"outline": ["章节标题1", "章节标题2", ...]}
2. 大纲按时间顺序组织，5-15 个章节，标题格式「第N章 <主题>」，主题 ≤15 字。
3. 合并重复内容，丢弃纯寒暄/噪声。"#;

/// 章节总结 system 基础条款（2026-08-20 Phase4 手测未解决问题）。
/// 与分段要点（一句话要点）不同：章节总结是自由文本，长度不设上限。
const CHAPTER_SYSTEM_BASE: &str = r#"你是网课学习助手。给你一节课中一个章节（一页课件对应的时段）的材料（老师讲解转写文字 + 课件 OCR 文字）。
任务：写出这个章节的总结。

输出要求（严格遵守）：
1. 直接输出总结正文（纯文本，可用换行组织层次），不要 JSON、不要 markdown 代码围栏。
2. 总结必须完整覆盖本章讲到的核心内容，可以充分展开（解释、举例、步骤、考题摘录等），不受一句话限制。
3. 不得编造材料里没有的信息；材料矛盾时以老师讲解为准。
4. 全部内容是中文课，总结用中文。"#;

/// 章节总结 system 提示词。
///
/// - focus_prompt 非空：侧重点置于最前、标注最高优先级，总结**完全遵照**它来写；
/// - None / 空串：追加大白话默认结构（主题 → 内容 → 术语解释）。
pub fn chapter_system(focus_prompt: Option<&str>) -> String {
    match focus_prompt.map(|p| p.trim()).filter(|p| !p.is_empty()) {
        Some(p) => format!(
            "本节课的总结侧重点（最高优先级，章节总结必须完全遵照它来写）：\n{p}\n\n{CHAPTER_SYSTEM_BASE}"
        ),
        None => format!(
            "{CHAPTER_SYSTEM_BASE}\n5. 默认结构：本章主题 → 主要内容 → 涉及的专业术语/公式/定义各配一句大白话解释（不涉及则省略）。"
        ),
    }
}

/// 章节总结消息：材料与分段要点共用 user 文本（同一页课件时段的转写 + OCR）。
pub fn chapter_messages(seg: &SegmentInput, focus_prompt: Option<&str>) -> Vec<ChatMessage> {
    vec![
        ChatMessage::system(&chapter_system(focus_prompt)),
        ChatMessage::user(segment_user_text(seg)),
    ]
}

fn fmt_ts(ms: i64) -> String {
    let s = ms / 1000;
    format!("{:02}:{:02}:{:02}", s / 3600, (s % 3600) / 60, s % 60)
}

/// 分段 system 提示词（含侧重点注入，2026-08-08 Phase4 手测问题2 / AC-111，
/// 2026-08-19 升级为 focus_prompt 自定义文本）。
///
/// - 智能输出格式（恒生效）：涉及专业术语/公式/定义时，要点用「术语：大白话解释」；
///   不涉及则保持普通要点（默认格式）。
/// - 侧重点：focus_prompt 非空时追加一行侧重指令；None / 空串 = 默认不追加。
pub fn segment_system(focus_prompt: Option<&str>) -> String {
    let mut s = String::from(SEGMENT_SYSTEM);
    s.push_str(
        "\n6. 若本段内容涉及专业术语、公式或定义，对应要点用「术语：一句大白话解释」的格式写；不涉及则保持普通要点。",
    );
    if let Some(p) = focus_prompt.map(|p| p.trim()).filter(|p| !p.is_empty()) {
        s.push_str("\n7. 侧重点：");
        s.push_str(p);
    }
    s
}

/// 分段 user 消息文本：段区间 + 逐页材料（OCR + 讲解，带时间戳）。
pub fn segment_user_text(seg: &SegmentInput) -> String {
    let mut s = format!(
        "段区间：[{}, {}]（{} 至 {}），要点 ts_ms 必须 ≥ {} 且 < {}。\n\n",
        seg.start_ms,
        seg.end_ms,
        fmt_ts(seg.start_ms),
        fmt_ts(seg.end_ms),
        seg.start_ms,
        seg.end_ms
    );
    for part in &seg.parts {
        s.push_str(&format!("--- 时间段 {} ---\n", fmt_ts(part.ts_ms)));
        if let Some(ocr) = &part.ocr_text {
            if !ocr.trim().is_empty() {
                s.push_str(&format!("[课件OCR] {}\n", ocr.trim()));
            }
        }
        if !part.texts.is_empty() {
            s.push_str(&format!("[讲解] {}\n", part.texts.join(" ")));
        }
    }
    s
}

/// 分段消息（纯文字；多模态精修时调用方改用 user_with_images 附图）。
pub fn segment_messages(seg: &SegmentInput, focus_prompt: Option<&str>) -> Vec<ChatMessage> {
    vec![
        ChatMessage::system(&segment_system(focus_prompt)),
        ChatMessage::user(segment_user_text(seg)),
    ]
}

/// 全局合并消息：所有分段要点压成一个 JSON 输入。
pub fn merge_messages(segments: &[SegmentSummary]) -> Vec<ChatMessage> {
    let points: Vec<serde_json::Value> = segments
        .iter()
        .flat_map(|seg| {
            seg.points.iter().map(move |p| {
                serde_json::json!({"ts_ms": p.ts_ms, "time": fmt_ts(p.ts_ms), "text": p.text})
            })
        })
        .collect();
    let user = format!(
        "全课分段要点（{} 条，已按时间排序）：\n{}",
        points.len(),
        serde_json::to_string_pretty(&points).unwrap_or_default()
    );
    vec![ChatMessage::system(MERGE_SYSTEM), ChatMessage::user(user)]
}

/// 汇总定稿 system（2026-08-21 手测新需求）：把分段总结做最终去重汇总。
const CONSOLIDATE_SYSTEM: &str = r#"你是网课学习助手。给你一节课按课件翻页分出的所有章节（每章有现标题、时间区间、章节总结）。
任务：生成全课的最终汇总定稿。背景：抽帧偶有不准，不同章节可能讲了重复内容；现标题也可能只是「章节 N」这种占位。

输出要求（严格遵守）：
1. 只输出一个 JSON 对象，不要任何其他文字或 markdown 代码围栏：
   {"title": "全课总标题", "chapters": [{"title": "最终章节标题", "segment_ids": [0, 2], "summary": "该最终章节的总结"}, ...]}
2. "title" 是全课总标题：概括整节课真实主题（≤30 字，不含书名号/冒号等不适合做文件名的字符）——将用作总结文档的文件名与主标题。
3. **去重合并**：内容重复/连贯的多个原章节合并为一个最终章节（segment_ids 列出全部原章节号，按时间升序）；每个原章节号必须且只能分配给一个最终章节，不得遗漏、不得重复。
4. 最终章节按时间顺序排列，标题重新拟定为反映真实内容的主题（≤20 字，不要「第N章」前缀，不要照抄占位标题）。
5. 每个最终章节的 summary 合并所含原章节的章节总结，剔除重复表述，保留全部不重复的核心信息（自由文本，不限长）。
6. 不得编造材料里没有的信息。全部内容是中文课，用中文。"#;

/// 汇总定稿消息：所有原章节（标题 + 时间 + 章节总结 + 要点）压成 JSON 输入。
/// 侧重点（focus_prompt）与章节总结同规则注入 system 最前、标注最高优先级
/// ——定稿的章节合并去重、总标题、各章 summary 都要延续该侧重（2026-08-22 补）。
pub fn consolidate_messages(draft: &SummaryDraft, focus_prompt: Option<&str>) -> Vec<ChatMessage> {
    let system = match focus_prompt.map(|p| p.trim()).filter(|p| !p.is_empty()) {
        Some(p) => format!(
            "本节课的总结侧重点（最高优先级，汇总定稿的合并取舍、总标题与各章总结必须完全遵照它来写）：\n{p}\n\n{CONSOLIDATE_SYSTEM}"
        ),
        None => CONSOLIDATE_SYSTEM.to_string(),
    };
    let chapters: Vec<serde_json::Value> = draft
        .segments
        .iter()
        .map(|seg| {
            let material = if seg.chapter_summary.trim().is_empty() {
                seg.points.iter().map(|p| p.text.clone()).collect::<Vec<_>>().join("\n")
            } else {
                seg.chapter_summary.trim().to_string()
            };
            serde_json::json!({
                "segment_id": seg.segment_id,
                "title": format!("章节 {}", seg.segment_id + 1),
                "start_ms": seg.start_ms,
                "end_ms": seg.end_ms,
                "material": material,
            })
        })
        .collect();
    let user = format!(
        "全课原章节（{} 章，已按时间排序，segment_id 为原章节号）：\n{}",
        chapters.len(),
        serde_json::to_string_pretty(&chapters).unwrap_or_default()
    );
    vec![ChatMessage::system(&system), ChatMessage::user(user)]
}

/// 从模型输出里提取 JSON 对象文本（容忍 markdown 围栏/前后赘语）。
/// 返回第一个完整 {...} 的子串（按花括号配对，能正确处理字符串内的括号够不到，
/// 但对要点类输出够用；解析失败上层还有本地兜底）。
pub fn extract_json_object(text: &str) -> Option<String> {
    let start = text.find('{')?;
    let mut depth = 0i32;
    let mut in_str = false;
    let mut escaped = false;
    for (i, ch) in text.char_indices().skip_while(|(i, _)| *i < start) {
        if in_str {
            if escaped {
                escaped = false;
            } else if ch == '\\' {
                escaped = true;
            } else if ch == '"' {
                in_str = false;
            }
            continue;
        }
        match ch {
            '"' => in_str = true,
            '{' => depth += 1,
            '}' => {
                depth -= 1;
                if depth == 0 {
                    return Some(text[start..=i].to_string());
                }
            }
            _ => {}
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::summary::map_reduce::SegmentPart;

    fn seg() -> SegmentInput {
        SegmentInput {
            segment_id: 0,
            start_ms: 60_000,
            end_ms: 300_000,
            parts: vec![SegmentPart {
                ts_ms: 60_000,
                frame_ref: Some("frames/page_0001.jpg".into()),
                thumb_ref: None,
                ocr_text: Some("牛顿第二定律 F=ma".into()),
                texts: vec!["这里讲的是力和加速度的关系".into()],
            }],
        }
    }

    #[test]
    fn segment_prompt_has_range_and_materials() {
        let t = segment_user_text(&seg());
        assert!(t.contains("60000") && t.contains("300000"));
        assert!(t.contains("00:01:00"));
        assert!(t.contains("[课件OCR] 牛顿第二定律 F=ma"));
        assert!(t.contains("[讲解] 这里讲的是力和加速度的关系"));
    }

    #[test]
    fn merge_prompt_lists_points_sorted() {
        let segs = vec![SegmentSummary {
            segment_id: 0,
            start_ms: 0,
            end_ms: 10_000,
            points: vec![super::super::SummaryPoint {
                text: "要点A".into(),
                ts_ms: 5000,
                frame_ref: None,
            }],
            local_fallback: false,
            chapter_summary: String::new(),
        }];
        let msgs = merge_messages(&segs);
        let user = msgs[1].content.as_str().unwrap();
        assert!(user.contains("要点A") && user.contains("5000"));
    }

    #[test]
    fn extract_json_tolerates_fence_and_chatter() {
        assert_eq!(
            extract_json_object("好的，结果如下：\n```json\n{\"points\": []}\n```"),
            Some("{\"points\": []}".to_string())
        );
        assert_eq!(
            extract_json_object("{\"a\": \"含}括号\"} trailing"),
            Some("{\"a\": \"含}括号\"}".to_string())
        );
        assert_eq!(extract_json_object("no json here"), None);
        assert_eq!(extract_json_object("{\"unclosed\":"), None);
    }

    /// 汇总定稿：侧重点置于 system 最前并标注最高优先级；None/空白不追加。
    #[test]
    fn consolidate_messages_inject_focus_first() {
        let d = SummaryDraft {
            version: 1,
            model: "m".into(),
            fallback: false,
            outline: vec![],
            segments: vec![],
            final_summary: None,
        };
        let sys_none = consolidate_messages(&d, None)[0].content.as_str().unwrap().to_string();
        assert!(!sys_none.contains("侧重点"));
        let sys_focus = consolidate_messages(&d, Some("考试复习 —— 优先提炼考点"))[0]
            .content
            .as_str()
            .unwrap()
            .to_string();
        assert!(sys_focus.starts_with("本节课的总结侧重点（最高优先级"));
        assert!(sys_focus.contains("考试复习 —— 优先提炼考点"));
        assert!(!consolidate_messages(&d, Some("  "))[0]
            .content
            .as_str()
            .unwrap()
            .contains("侧重点"), "空白 prompt 按默认");
    }

    #[test]
    fn fmt_ts_hhmmss() {
        assert_eq!(fmt_ts(0), "00:00:00");
        assert_eq!(fmt_ts(3_723_000), "01:02:03");
    }

    /// AC-111：智能输出格式恒生效；侧重点按 focus_prompt 注入，None/空串不追加。
    #[test]
    fn segment_system_smart_format_and_focus() {
        let def = segment_system(None);
        assert!(def.contains("术语：一句大白话解释"), "智能格式恒生效");
        assert!(!def.contains("侧重点"), "默认不追加侧重点");
        assert!(segment_system(Some("考试复习 —— 优先提炼考点")).contains("考试复习"));
        assert!(segment_system(Some("概念理解 —— 优先解释含义")).contains("概念理解"));
        assert!(segment_system(Some("实操步骤 —— 优先提炼流程")).contains("实操步骤"));
        assert!(!segment_system(Some("  ")).contains("侧重点"), "空白 prompt 按默认");
    }
}
