// ============================================================
// 画布视频节点 · 首尾帧 + @参考图/参考视频 收集引擎（F3，设计 §画布视频帧重构）
//
// 与 interpolate.ts / attachmentMention.ts 语义都不同：
// - interpolate：@{{node:id}} → 上游产出【文本】（文本/脚本节点内容）
// - attachmentMention：@{{image:<id>}} → 序号化图N（视频生成页，附件已落 UploadedAttachment）
// - 本引擎：画布视频节点专用 —— @{{node:id}} 指向 image 节点 → 收为 reference_image 附件 + 图N；
//   指向 video 节点 → 收为 reference_video 附件 + 视频N（2x-4：此前 video 节点被降级成
//   文本插值 "fileId:xxx.mp4" 拼进 prompt，供应商收不到 type:"video" 参数）；
//   其他节点 → 走文本插值；显式 firstFrameNodeId/lastFrameNodeId → 首尾帧附件。
//
// 纯函数（无 Vue 依赖），供 CanvasView.onRunVideo 调 + 单测覆盖。
// ============================================================

import type { AttachmentRef } from '@/api/media'
import type { MentionResolver } from '@/utils/interpolate'

/** 画布节点最小契约（buildVideoAttachments 只读 id/type/data）。 */
export interface CanvasVideoNodeLike {
  id: string
  type?: string
  data: Record<string, unknown>
}

/** @{{node|asset:id}} 占位符（与 interpolate.ts MENTION_RE 同构）。 */
const MENTION_RE = /@\{\{(node|asset):([^}]+)\}\}/g

/**
 * 收集画布视频节点的 attachments[] + 重写后的提示词。
 *
 * @param nodeData   视频节点 data（读 firstFrameNodeId/lastFrameNodeId）
 * @param rawPrompt  原始提示词（含 @{{node:id}} 占位符，未插值）
 * @param nodes      画布全部节点（按 id 查图节点 fileId）
 * @param textResolve 非 image 节点 @ 的文本插值器（buildMentionResolver 产物）
 *
 * attachments 顺序：[首帧, 尾帧] 或 [参考图...]；两种模式互斥。参考图按提示词里首次出现顺序，同 fileId 去重。
 * 首尾帧节点不参与「图N」序号；非图节点 @ 走文本插值（断链 → 「【断链】」）。
 */
export function resolveCanvasVideoAttachments(
  nodeData: Record<string, unknown>,
  rawPrompt: string,
  nodes: CanvasVideoNodeLike[],
  textResolve: MentionResolver
): { refs: AttachmentRef[]; rewrittenPrompt: string } {
  const byId = new Map(nodes.map((n) => [n.id, n]))

  // 1) 显式首/尾帧
  const frameNodeIds = new Set<string>()
  const refs: AttachmentRef[] = []
  const frameSlots: Array<[string, 'first_frame' | 'last_frame']> = [
    ['firstFrameNodeId', 'first_frame'],
    ['lastFrameNodeId', 'last_frame']
  ]
  for (const [key, role] of frameSlots) {
    const fid = nodeData[key] as string | undefined
    if (!fid) continue
    const fn = byId.get(fid)
    const fileId = fn?.data.fileId as string | undefined
    if (!fileId) continue
    frameNodeIds.add(fid)
    refs.push({ fileId, kind: 'image', frameRole: role })
  }

  // 2) @图节点 → 参考图；@视频节点 → 参考视频（按出现顺序去重，排除已是帧的）
  const refImageFileIds: string[] = [] // 已收参考图 fileId（去重+定序）
  const refVideoFileIds: string[] = [] // 已收参考视频 fileId（去重+定序，2x-4）
  const nodeIdToImageIdx = new Map<string, number>() // 节点 id → 图N 序号（0-based）
  const nodeIdToVideoIdx = new Map<string, number>() // 节点 id → 视频N 序号（0-based）

  MENTION_RE.lastIndex = 0
  const rewrittenPrompt = rawPrompt.replace(MENTION_RE, (_raw, kind: string, id: string) => {
    if (kind !== 'node') {
      // asset 占位符：保留文本插值语义（运行期 asset 不预解析 → 断链标记）
      const v = textResolve(kind as 'node' | 'asset', id)
      return v === undefined ? '【断链】' : v
    }
    const n = byId.get(id)
    if (n?.type === 'image' && !frameNodeIds.has(id)) {
      const fileId = n.data.fileId as string | undefined
      if (fileId) {
        let idx = nodeIdToImageIdx.get(id)
        if (idx === undefined) {
          // 同 fileId 去重：已被前面的节点引用过则复用序号
          const exist = refImageFileIds.indexOf(fileId)
          idx = exist >= 0 ? exist : refImageFileIds.length
          refImageFileIds.push(fileId)
          nodeIdToImageIdx.set(id, idx)
        }
        return `图${idx + 1}`
      }
    }
    // 2x-4：@视频节点收为 kind=video 附件（供应商侧映射为 reference_video 内容项），
    // 不再把 "fileId:xxx.mp4" 拼进 prompt 文本。
    if (n?.type === 'video' && !frameNodeIds.has(id)) {
      const fileId = n.data.fileId as string | undefined
      if (fileId) {
        let idx = nodeIdToVideoIdx.get(id)
        if (idx === undefined) {
          const exist = refVideoFileIds.indexOf(fileId)
          idx = exist >= 0 ? exist : refVideoFileIds.length
          refVideoFileIds.push(fileId)
          nodeIdToVideoIdx.set(id, idx)
        }
        return `视频${idx + 1}`
      }
    }
    // 其他节点 / 无 fileId → 文本插值（含 prompt+产物元信息）
    const v = textResolve(kind as 'node' | 'asset', id)
    return v === undefined ? '【断链】' : v
  })

  // 参考图/参考视频 attachments 追加在帧之后
  for (const fileId of refImageFileIds) {
    refs.push({ fileId, kind: 'image' })
  }
  for (const fileId of refVideoFileIds) {
    refs.push({ fileId, kind: 'video' })
  }

  if (frameNodeIds.size > 0 && (refImageFileIds.length > 0 || refVideoFileIds.length > 0)) {
    throw new Error('首帧/尾帧不能与参考媒体同时使用，请移除提示词中的 @参考图/@参考视频或清空首尾帧')
  }

  return { refs, rewrittenPrompt }
}
