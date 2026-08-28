import type { CanvasEdge, CanvasNode, CanvasNodeData } from '@/types/canvas'

/**
 * 修复III C4（2x-4）：节点创建副本——纯数据变换（DOM/画布操作留给调用方）。
 *
 * 修复IV C3（C-8，决策 4「副本完全独立」）语义升级：副本**带产物显示**但与资产库/
 * 任务链彻底脱钩——产物四件（fileId/previewUrl/coverPreviewUrl/outputText）与会话
 * objectURL 保留（副本即显结果）；资产绑定三件套与 taskId 清空（副本入库=新资产、
 * 副本重生成=新任务，绝不命中原任务/原资产行）；status 按产物有无回填 success/idle。
 * width/height 保留（用户手拉的布局偏好跟副本走）；firstFrameNodeId 保留（结构引用，
 * 指向画布内上游节点而非外部资源）；组员关系只存组侧（CanvasBoard groups），副本
 * 天然不归属任何组——平节点口径。
 */
const RESET_KEYS: (keyof CanvasNodeData | string)[] = [
  // 运行态与错误（status 在函数尾部按产物有无重算）
  'status', 'errorMsg',
  // 任务链脱钩：重生成/删除副本绝不牵动原节点任务
  'taskId', 'mediaTaskId', 'startedAt', 'finishedAt',
  // 资产绑定脱钩：副本入库走全新资产（不命中「已入库」判重）
  'assetId', 'assetName', 'assetVersion',
  // 会话级派生态
  'assetHasUpdate', 'changeLog', 'localizeWarning'
]

export function cloneNodeForDuplicate(src: CanvasNode): { type: string; position: { x: number; y: number }; data: CanvasNodeData } {
  const data: CanvasNodeData = JSON.parse(JSON.stringify(src.data ?? {})) as CanvasNodeData
  for (const k of RESET_KEYS) delete data[k]
  // 产物四件保留（决策 4）：有产物 → 副本即显 success；无产物 → idle 起点
  data.status = (data.previewUrl || data.outputText) ? 'success' : 'idle'
  return {
    type: src.type,
    position: { x: src.position.x + 40, y: src.position.y + 40 },
    data
  }
}

/** 修复VI 副本连线 id 序号：同毫秒批量克隆（多条边一次 appendEdges）防撞。 */
let cloneEdgeSeq = 0

/**
 * 修复VI（2x 未解决③，用户决策「连线克隆一份」）：副本连线克隆——原节点所有入边/出边
 * 各克隆一条指向/发自副本。纯数据变换：
 * - 原边一律不动（含 source==target==原 的自环边 → 克隆成副本自环）；
 * - handles/type/style 随展开保留（结构语义与原边一致）；
 * - 新边 id 唯一（Date.now+seq，防同批撞）。
 * 组员关系不带（平节点口径维持，组只存组侧）。
 */
export function cloneEdgesForDuplicate(originalId: string, newId: string, edges: CanvasEdge[]): CanvasEdge[] {
  return edges
    .filter(e => e.source === originalId || e.target === originalId)
    .map(e => {
      const source = e.source === originalId ? newId : e.source
      const target = e.target === originalId ? newId : e.target
      return { ...e, id: `edge-${source}-${target}-${Date.now()}-${cloneEdgeSeq++}`, source, target }
    })
}
