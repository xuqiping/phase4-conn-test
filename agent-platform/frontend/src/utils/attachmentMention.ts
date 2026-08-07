// ============================================================
// 视频生成页 · 附件 @ 引用插值（H，设计 §4.5）
//
// 与画布 interpolate.ts 语义不同：
// - 画布 interpolate：@{{node:id}} / @{{asset:id}} → 展开成上游产出【内容】
// - 本引擎：@{{image|video|audio:<id>}} → 序号化为【图N/视频N/音频N】（Ark 认序号不认 token）
//
// id 用附件稳定 uuid（重排/删除不断链）；序号按提交时附件当前顺序解析（重排后跟随）。
// 断链（引用的附件已删）降级「〔已删除的参考〕」提示词可见，避免裸 token 送 Ark。
// ============================================================

/** 附件最小契约（只需稳定 id 做序号定位）。 */
export interface AttachmentLike {
  id: string
}

/** 序号前缀（与 VideoGenView ATTACH_KIND_LABELS 同源）。 */
const KIND_PREFIX: Record<string, string> = { image: '图', video: '视频', audio: '音频' }

/** @{{image|video|audio:<id>}} 占位符（与 MentionTextarea MENTION_RE_LOCAL 的 \w+ 同构，限附件三类）。 */
const ATTACH_MENTION_RE = /@\{\{(image|video|audio):([^}]+)\}\}/g

/** 断链降级文本（提交态可见，提示用户该参考已删）。 */
export const BROKEN_ATTACHMENT_MARKER = '〔已删除的参考〕'

/**
 * 把提示词里的附件 @ 占位符序号化为「图N/视频N/音频N」。
 * 按各 kind 列表的当前顺序解析 1-based 序号；id 找不到（已删）→ 降级标记。
 */
export function interpolateAttachmentPrompt(
  prompt: string,
  images: AttachmentLike[],
  videos: AttachmentLike[],
  audios: AttachmentLike[]
): string {
  if (!prompt) return prompt
  return prompt.replace(ATTACH_MENTION_RE, (_match, kind: string, id: string) => {
    const list = kind === 'image' ? images : kind === 'video' ? videos : kind === 'audio' ? audios : null
    if (!list) return _match
    const idx = list.findIndex((a) => a.id === id)
    if (idx < 0) return BROKEN_ATTACHMENT_MARKER
    const prefix = KIND_PREFIX[kind] ?? kind
    return `${prefix}${idx + 1}`
  })
}
