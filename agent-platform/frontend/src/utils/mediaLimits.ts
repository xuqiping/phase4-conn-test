/**
 * 修复VI（2x 未解决①②⑤）：媒体文件类型/大小判定的前端单源。
 *
 * 大小上限与后端 MediaStorageService.KIND_MAX_BYTES 对齐（image 30MB / audio 15MB /
 * video 50MB，base64 前原始大小）——此处只做体验性预检（超限 toast 即拒不发请求），
 * 服务端校验才是闸门。原 VideoGenView 内联常量改引此处，防两处漂移。
 */

export type MediaKind = 'image' | 'video' | 'audio'

export const KIND_LIMIT_BYTES: Record<MediaKind, number> = {
  image: 30 * 1024 * 1024,
  video: 50 * 1024 * 1024,
  audio: 15 * 1024 * 1024
}

export const KIND_LIMIT_LABEL: Record<MediaKind, string> = {
  image: '30MB',
  video: '50MB',
  audio: '15MB'
}

/** MIME → 媒体类型（image/*→image、video/*→video、audio/*→audio；其余 null=不支持建节点） */
export function kindFromMime(mime: string | undefined | null): MediaKind | null {
  if (!mime) return null
  if (mime.startsWith('image/')) return 'image'
  if (mime.startsWith('video/')) return 'video'
  if (mime.startsWith('audio/')) return 'audio'
  return null
}

/** 超限话术；未超限返回 null（调用方 null=放行） */
export function sizeLimitError(kind: MediaKind, size: number, name: string): string | null {
  return size > KIND_LIMIT_BYTES[kind]
    ? `文件过大：${name}（${kind} ≤${KIND_LIMIT_LABEL[kind]}）`
    : null
}

/**
 * 画布粘贴守卫（修复VI 2x#1）：事件目标在可编辑元素内 → 不拦（正常粘文本）。
 * 供 CanvasBoard.onPaste 判定；独立成函数便于单测。
 */
export function isEditableTarget(target: HTMLElement | null): boolean {
  return !!target?.closest('input, textarea, [contenteditable="true"]')
}
