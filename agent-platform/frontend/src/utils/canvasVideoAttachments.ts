// ============================================================
// 画布视频节点 · 首尾帧 + @参考图/参考视频 收集引擎（F3，设计 §画布视频帧重构）
//
// 与 interpolate.ts / attachmentMention.ts 语义都不同：
// - interpolate：@{{node:id}} → 上游产出【文本】（文本/脚本节点内容）
// - attachmentMention：@{{image:<id>}} → 序号化图N（视频生成页，附件已落 UploadedAttachment）
// - 本引擎：画布视频节点专用 —— @{{node:id}} 指向 image 节点 → 收为 reference_image 附件 + 图N；
//   指向 video 节点 → 收为 reference_video 附件 + 视频N（2x-4：此前 video 节点被降级成
//   文本插值 "fileId:xxx.mp4" 拼进 prompt，供应商收不到 type:"video" 参数）；
//   指向 audio 节点 → 收为 reference_audio 附件 + 音频N（修复VI VE 2x#6，三类独立编号）；
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
  const c = collectCanvasRefs(nodeData, rawPrompt, nodes, textResolve)
  if (c.frameNodeIds.size > 0
    && (c.refImageFileIds.length > 0 || c.refVideoFileIds.length > 0 || c.refAudioFileIds.length > 0)) {
    throw new Error('首帧/尾帧不能与参考媒体同时使用，请移除提示词中的 @参考图/@参考视频/@参考音频或清空首尾帧')
  }
  return { refs: c.refs, rewrittenPrompt: c.rewrittenPrompt }
}

/**
 * 2x 四轮 S8：属性面板「参考」区预览列表项（fileId 缩略 + 帧角色/图N/视频N 徽标）。
 * 与提交 attachments 同一收集引擎（collectCanvasRefs）产出——序号必然一致，防两处漂移。
 */
export interface CanvasReferenceItem {
  fileId: string
  kind: 'image' | 'video' | 'audio'
  /** 徽标文案：首帧/尾帧/图N/视频N/音频N（与 rewrittenPrompt 里的序号化写法一致）。 */
  label: string
}

/**
 * 2x 四轮 S8：解析节点的参考媒体预览列表（帧角色 + @图/@视频 → 图N/视频N 徽标）。
 * 与 resolveCanvasVideoAttachments 序号化同源（共用 collectCanvasRefs）；
 * 首尾帧+参考互斥场景**不抛**（提交时才拒），预览照常展示两组（L7：互斥报错时参考区仍渲染）。
 */
export function buildCanvasReferenceList(
  nodeData: Record<string, unknown>,
  rawPrompt: string,
  nodes: CanvasVideoNodeLike[]
): CanvasReferenceItem[] {
  const c = collectCanvasRefs(nodeData, rawPrompt, nodes, () => undefined)
  let imgN = 0
  let vidN = 0
  let audN = 0
  return c.refs.map((r) => {
    if (r.frameRole === 'first_frame') return { fileId: r.fileId, kind: 'image' as const, label: '首帧' }
    if (r.frameRole === 'last_frame') return { fileId: r.fileId, kind: 'image' as const, label: '尾帧' }
    if (r.kind === 'video') return { fileId: r.fileId, kind: 'video' as const, label: `视频${++vidN}` }
    // 修复VI VE（2x#6）：@音频节点 → 参考音频附件（kind:'audio'，图/视频/音频各自独立编号不混排）
    if (r.kind === 'audio') return { fileId: r.fileId, kind: 'audio' as const, label: `音频${++audN}` }
    return { fileId: r.fileId, kind: 'image' as const, label: `图${++imgN}` }
  })
}

/** 收集内核（resolveCanvasVideoAttachments 与 buildCanvasReferenceList 共用，单处维护防序号漂移）。 */
function collectCanvasRefs(
  nodeData: Record<string, unknown>,
  rawPrompt: string,
  nodes: CanvasVideoNodeLike[],
  textResolve: MentionResolver
): {
  refs: AttachmentRef[]
  rewrittenPrompt: string
  frameNodeIds: Set<string>
  refImageFileIds: string[]
  refVideoFileIds: string[]
  refAudioFileIds: string[]
} {
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

  // 2) @图节点 → 参考图；@视频节点 → 参考视频；@音频节点 → 参考音频（修复VI VE 2x#6；
  //    按出现顺序去重，排除已是帧的；图/视频/音频三类各自独立编号）
  const refImageFileIds: string[] = [] // 已收参考图 fileId（去重+定序）
  const refVideoFileIds: string[] = [] // 已收参考视频 fileId（去重+定序，2x-4）
  const refAudioFileIds: string[] = [] // 已收参考音频 fileId（去重+定序，VE）
  const nodeIdToImageIdx = new Map<string, number>() // 节点 id → 图N 序号（0-based）
  const nodeIdToVideoIdx = new Map<string, number>() // 节点 id → 视频N 序号（0-based）
  const nodeIdToAudioIdx = new Map<string, number>() // 节点 id → 音频N 序号（0-based）

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
          // 同 fileId 去重：已被前面的节点引用过则复用序号（不重复 push——跨节点同 fileId 只产一条附件）
          const exist = refImageFileIds.indexOf(fileId)
          idx = exist >= 0 ? exist : refImageFileIds.length
          if (exist < 0) refImageFileIds.push(fileId)
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
          if (exist < 0) refVideoFileIds.push(fileId)
          nodeIdToVideoIdx.set(id, idx)
        }
        return `视频${idx + 1}`
      }
    }
    // 修复VI VE（2x#6）：@音频节点收为 kind=audio 附件（独立编号「音频N」，不混进图/视频序号）。
    if (n?.type === 'audio' && !frameNodeIds.has(id)) {
      const fileId = n.data.fileId as string | undefined
      if (fileId) {
        let idx = nodeIdToAudioIdx.get(id)
        if (idx === undefined) {
          const exist = refAudioFileIds.indexOf(fileId)
          idx = exist >= 0 ? exist : refAudioFileIds.length
          if (exist < 0) refAudioFileIds.push(fileId)
          nodeIdToAudioIdx.set(id, idx)
        }
        return `音频${idx + 1}`
      }
    }
    // 其他节点 / 无 fileId → 文本插值（含 prompt+产物元信息）
    const v = textResolve(kind as 'node' | 'asset', id)
    return v === undefined ? '【断链】' : v
  })

  // 参考图/参考视频/参考音频 attachments 追加在帧之后
  for (const fileId of refImageFileIds) {
    refs.push({ fileId, kind: 'image' })
  }
  for (const fileId of refVideoFileIds) {
    refs.push({ fileId, kind: 'video' })
  }
  for (const fileId of refAudioFileIds) {
    refs.push({ fileId, kind: 'audio' })
  }

  return { refs, rewrittenPrompt, frameNodeIds, refImageFileIds, refVideoFileIds, refAudioFileIds }
}
