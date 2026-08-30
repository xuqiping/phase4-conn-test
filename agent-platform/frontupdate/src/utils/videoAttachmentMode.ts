export type VideoAttachmentTarget = 'image' | 'video' | 'audio' | 'first' | 'last'

export interface VideoAttachmentModeState {
  frameCount: number
  referenceMediaCount: number
}

/** Ark 要求帧模式和参考媒体模式二选一；同一模式内允许继续追加。 */
export function canAddVideoAttachment(
  target: VideoAttachmentTarget,
  state: VideoAttachmentModeState
): boolean {
  const addingFrame = target === 'first' || target === 'last'
  return addingFrame ? state.referenceMediaCount === 0 : state.frameCount === 0
}
