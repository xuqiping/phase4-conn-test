import dagre from '@dagrejs/dagre'
import type { CanvasEdge, CanvasNode } from '@/types/canvas'

/**
 * 修复VII（2x 增补②）：一键优化布局纯函数（LibTV 式，dagre 分层）。
 * 纯数据变换，不碰 Vue/DOM——CanvasBoard 负责接线和历史/落库（同 nodeClone 范式）。
 *
 * 口径（规格 §4 VII-2）：
 * - LR：上游在左、下游在右（rankdir='LR'，ranksep/nodesep 走规格 100/60）；
 * - 子图模式（includeIds 给定）：只排集内节点，诱导边=两端都在集内；结果整体平移
 *   使新包围盒左上角=原子图包围盒左上角（最小漂移，集外节点零动）；
 *   全图模式同款锚定到旧全图 bbox 左上角（比归一 0,0 少一次视觉跳，规格超集）；
 * - 末尾 16 网格取整（对齐 snap-to-grid，防松手跳格）；
 * - 自环边不参与排布（对分层无贡献，dagre 画在节点自身旁）；
 * - 空集返回空 Map；不抛错（按钮层已禁用，此处双保险）。
 */

/** data.width/height 缺省时的类型默认尺寸估算（整理是重排不是精排，见规格 §8）。 */
const DEFAULT_SIZE: Record<string, { width: number; height: number }> = {
  image: { width: 320, height: 320 },
  video: { width: 320, height: 320 },
  audio: { width: 300, height: 160 },
  text: { width: 300, height: 180 },
  script: { width: 300, height: 180 },
  storyboard: { width: 300, height: 180 },
  director: { width: 300, height: 180 },
}
const FALLBACK_SIZE = { width: 300, height: 180 }

/** 尺寸估算导出（修复VII Chunk3 剪贴板包围盒复用同一张表，两处不漂移）。 */
export function estimateSize(type: string, data?: { width?: unknown; height?: unknown }): { width: number; height: number } {
  const def = DEFAULT_SIZE[type] ?? FALLBACK_SIZE
  const w = typeof data?.width === 'number' && data.width > 0 ? data.width : def.width
  const h = typeof data?.height === 'number' && data.height > 0 ? data.height : def.height
  return { width: w, height: h }
}

function nodeSizeOf(node: CanvasNode): { width: number; height: number } {
  return estimateSize(node.type, node.data)
}

/** 圈住一组节点的最小矩形（左上角 + 尺寸；dagre 中心点语义已转左上角后再算）。 */
function bboxOf(entries: { x: number; y: number; width: number; height: number }[]) {
  const minX = Math.min(...entries.map(e => e.x))
  const minY = Math.min(...entries.map(e => e.y))
  return { left: minX, top: minY }
}

const GRID = 16
function snap(v: number): number {
  return Math.round(v / GRID) * GRID
}

export interface AutoLayoutOptions {
  direction?: 'LR' | 'TB'
  /** 子图模式：只排这些节点；缺省 = 全集。 */
  includeIds?: Set<string>
}

/**
 * 计算重排坐标。返回 Map<nodeId, {x,y}>（左上角坐标，已锚定+网格对齐）。
 * dagre 确定性：同输入同输出（单测钉死），多层画布/离线重放可复现。
 */
export function computeAutoLayout(
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  opts: AutoLayoutOptions = {}
): Map<string, { x: number; y: number }> {
  const direction = opts.direction ?? 'LR'
  const include = opts.includeIds
  const participating = nodes.filter(n => !include || include.has(n.id))
  const result = new Map<string, { x: number; y: number }>()
  if (!participating.length) return result

  const idSet = new Set(participating.map(n => n.id))
  const sizes = new Map(participating.map(n => [n.id, nodeSizeOf(n)] as const))

  // 入参可能来自 reactive Proxy（v-model nodes），剥壳喂 dagre（cloneHistoryState 同款范式）
  const g = new dagre.graphlib.Graph({ multigraph: true })
  g.setGraph({ rankdir: direction, ranksep: 100, nodesep: 60, marginx: 20, marginy: 20 })
  g.setDefaultEdgeLabel(() => ({}))
  for (const n of participating) g.setNode(n.id, { ...sizes.get(n.id)! })
  for (const e of edges) {
    if (e.source === e.target) continue // 自环不参与分层
    if (idSet.has(e.source) && idSet.has(e.target)) g.setEdge(e.source, e.target)
  }
  dagre.layout(g)

  for (const n of participating) {
    const p = g.node(n.id)
    const s = sizes.get(n.id)!
    // dagre 给中心点 → vue-flow position 是左上角，减半宽高（不减整体偏半身位）
    result.set(n.id, { x: p.x - s.width / 2, y: p.y - s.height / 2 })
  }

  // 锚定：新 bbox 左上角平移回旧 bbox 左上角（全图/子图同款；子图=最小漂移）
  const oldBox = bboxOf(participating.map(n => {
    const s = sizes.get(n.id)!
    return { x: n.position.x, y: n.position.y, width: s.width, height: s.height }
  }))
  const newBox = bboxOf(participating.map(n => {
    const p = result.get(n.id)!
    const s = sizes.get(n.id)!
    return { x: p.x, y: p.y, width: s.width, height: s.height }
  }))
  const dx = oldBox.left - newBox.left
  const dy = oldBox.top - newBox.top
  for (const [id, p] of result) {
    result.set(id, { x: snap(p.x + dx), y: snap(p.y + dy) })
  }
  return result
}
