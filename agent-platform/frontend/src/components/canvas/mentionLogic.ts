// ============================================================
// MentionTextarea · @占位符纯逻辑（A1 contenteditable 重写抽出）
//
// 与 DOM 无关，可纯单测。组件层只做 DOM↔字符串绑定。
// 占位符语法：@{{<kind>:<id>}}，kind 为任意单词（画布 node/asset；视频页 image/video/audio）。
// id 稳定（重命名不断链）；label 仅服务人眼，由组件层用 candidates 映射。
// 序列化语义：DOM 里 chip=原子块(contenteditable=false)，<br>=换行，文本节点=字面量。
// ============================================================

/** @{{kind:id}} 占位符全局正则（带 index 用于精确切分；用前须 reset lastIndex）。 */
export const MENTION_RE = /@\{\{(\w+):([^}]+)\}\}/g

/** 切分片段：普通文本 / 占位符。 */
export type MentionSeg =
  | { type: 'text'; value: string }
  | { type: 'mention'; raw: string; kind: string; id: string }

/**
 * 把含占位符的字符串切成「文本段 + 占位符段」。
 * 纯字符串操作，contenteditable 渲染与断链判定共用。
 */
export function parseSegments(text: string): MentionSeg[] {
  const out: MentionSeg[] = []
  MENTION_RE.lastIndex = 0
  let m: RegExpExecArray | null
  let last = 0
  while ((m = MENTION_RE.exec(text)) !== null) {
    if (m.index > last) out.push({ type: 'text', value: text.slice(last, m.index) })
    out.push({ type: 'mention', raw: m[0], kind: m[1], id: m[2] })
    last = m.index + m[0].length
  }
  if (last < text.length) out.push({ type: 'text', value: text.slice(last) })
  return out
}

/**
 * 从光标位置回退定位 @ 唤起锚点。
 * 规则（与旧 textarea 版一致，保既有测试语义）：
 * - 遇 @：@ 前一字符须非字母数字（拦邮箱 foo@bar；允许行首/空白/中文/标点后触发）
 * - @ 后遇空白 → 关闭（query 被空格断开）
 * 返回 @ 下标 + 查询串，或 null。
 */
export function detectAnchor(text: string, caret: number): { at: number; q: string } | null {
  if (caret <= 0) return null
  let i = caret - 1
  while (i >= 0) {
    const ch = text[i]
    if (ch === '@') {
      const prev = i > 0 ? text[i - 1] : ' '
      if (!/[A-Za-z0-9]/.test(prev)) return { at: i, q: text.slice(i + 1, caret) }
      return null
    }
    if (/\s/.test(ch)) return null
    i--
  }
  return null
}

/**
 * 在 @ 锚点处插入占位符 token + 尾随空格，返回新串与光标目标位置。
 * at=@ 下标；caret=当前光标（@ 与 caret 之间的查询串会被 token 覆盖）。
 */
export function insertMention(
  text: string,
  at: number,
  caret: number,
  kind: string,
  id: string
): { text: string; pos: number } {
  const token = `@{{${kind}:${id}}}`
  const after = text[caret]
  const suffix = after !== undefined && /\s/.test(after) ? '' : ' '
  const next = text.slice(0, at) + token + suffix + text.slice(caret)
  return { text: next, pos: at + token.length + suffix.length }
}

/** HTML 转义（innerHTML 渲染文本/label 时防 XSS——节点名/label 均为用户内容）。 */
export function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}
