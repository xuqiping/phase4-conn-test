import type { CanvasNode, CanvasNodeData } from '@/types/canvas'

/**
 * 修复III C4（2x-4）：节点创建副本——纯数据变换（DOM/画布操作留给调用方）。
 *
 * 语义（plan 口径）：位置 +40/+40 右下错开；参数/提示词深拷贝保留；生成态与
 * 会话态字段清空回 idle——副本是「同参数重新生成」的起点，不携带旧产物与旧任务引用。
 * width/height 保留（用户手拉的布局偏好跟副本走）；资产绑定三件套保留（参数性质，
 * 副本仍引用同资产同版本）；组员关系只存组侧（CanvasBoard groups），副本天然不归属
 * 任何组——平节点口径（plan 边界注记：现分组结构不支持整组复制）。
 */
const RESET_KEYS: (keyof CanvasNodeData | string)[] = [
  // 运行态与错误
  'status', 'errorMsg',
  // 生成产物与旧任务引用（防副本删除/重生成误伤原节点任务）
  'fileId', 'previewUrl', 'coverPreviewUrl', 'outputText', 'taskId', 'mediaTaskId',
  'startedAt', 'finishedAt',
  // 会话级派生态
  'assetHasUpdate', 'changeLog', 'localizeWarning',
  // 指向上游节点的引用——副本未连线，保留必断链
  'firstFrameNodeId'
]

export function cloneNodeForDuplicate(src: CanvasNode): { type: string; position: { x: number; y: number }; data: CanvasNodeData } {
  const data: CanvasNodeData = JSON.parse(JSON.stringify(src.data ?? {})) as CanvasNodeData
  for (const k of RESET_KEYS) delete data[k]
  data.status = 'idle'
  return {
    type: src.type,
    position: { x: src.position.x + 40, y: src.position.y + 40 },
    data
  }
}
