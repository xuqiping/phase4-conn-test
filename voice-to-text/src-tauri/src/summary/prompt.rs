//! 分段 / 全局合并 prompt（Step 8, FR-107）。
//!
//! 长文本「中段迷失」对策（plan 备注）：
//! - 分段 prompt 明确段区间，要求每条要点 ts_ms 必须落在区间内；
//! - 合并 prompt 要求按时间去重、保持时间顺序。

use super::cloud_api::ChatMessage;
use super::map_reduce::SegmentInput;
use super::SegmentSummary;

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

fn fmt_ts(ms: i64) -> String {
    let s = ms / 1000;
    format!("{:02}:{:02}:{:02}", s / 3600, (s % 3600) / 60, s % 60)
}

/// 分段 system 提示词（含侧重点注入，2026-08-08 Phase4 手测问题2 / AC-111）。
///
/// - 智能输出格式（恒生效）：涉及专业术语/公式/定义时，要点用「术语：大白话解释」；
///   不涉及则保持普通要点（默认格式）。
/// - 侧重点：exam / concept / practice 追加一行侧重指令；空串 = 默认不追加。
pub fn segment_system(focus: &str) -> String {
    let mut s = String::from(SEGMENT_SYSTEM);
    s.push_str(
        "\n6. 若本段内容涉及专业术语、公式或定义，对应要点用「术语：一句大白话解释」的格式写；不涉及则保持普通要点。",
    );
    let line = match focus {
        "exam" => "\n7. 侧重点：考试复习 —— 优先提炼考点、定义公式、易错点、老师反复强调的内容。",
        "concept" => "\n7. 侧重点：概念理解 —— 优先解释概念的含义、为什么是这样、与相近概念的区别，表述通俗易懂。",
        "practice" => "\n7. 侧重点：实操步骤 —— 优先提炼操作步骤、流程顺序、注意事项和常见坑。",
        _ => "",
    };
    s.push_str(line);
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
pub fn segment_messages(seg: &SegmentInput, focus: &str) -> Vec<ChatMessage> {
    vec![
        ChatMessage::system(&segment_system(focus)),
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

    #[test]
    fn fmt_ts_hhmmss() {
        assert_eq!(fmt_ts(0), "00:00:00");
        assert_eq!(fmt_ts(3_723_000), "01:02:03");
    }

    /// AC-111：智能输出格式恒生效；侧重点按 focus 注入，空串不追加。
    #[test]
    fn segment_system_smart_format_and_focus() {
        let def = segment_system("");
        assert!(def.contains("术语：一句大白话解释"), "智能格式恒生效");
        assert!(!def.contains("侧重点"), "默认不追加侧重点");
        assert!(segment_system("exam").contains("考试复习"));
        assert!(segment_system("concept").contains("概念理解"));
        assert!(segment_system("practice").contains("实操步骤"));
        assert!(!segment_system("unknown-junk").contains("侧重点"), "非法值按默认");
    }
}
