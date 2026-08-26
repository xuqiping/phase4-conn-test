// ============================================================
// D2（2x-8）：上游节点收集（属性面板「上游」区数据源）。
// 纯函数与组件解耦（单测菱形去重/环安全/深度分层/截断）。
// BFS 沿入边（target→source）上行：depth=1 直接上游，2+ 逐级更上游；
// 首次入队即最小深度（BFS 层序保证），菱形依赖自然去重，环天然安全（visited）。
// ============================================================

import type { CanvasNode } from '@/types/canvas'

/** 上游条目：节点引用 + 距选中节点的连线跳数（1=直接上游）。 */
export interface UpstreamItem {
  node: CanvasNode
  depth: number
}

/** 深链上限（防巨型图一次性渲染卡死面板；超出截断并提示）。 */
export const UPSTREAM_CAP = 50

/**
 * 收集 nodeId 的全部上游节点（BFS 按深度分层）。
 * 返回按 depth 升序（同层保持 BFS 先到序）；超 UPSTREAM_CAP 截断置 truncated。
 * nodeId 不在 nodes 中 → 空结果（防御：节点刚被删）。
 */
export function collectUpstream(
  nodeId: string,
  nodes: readonly CanvasNode[],
  edges: readonly { id: string; source: string; target: string }[],
  cap = UPSTREAM_CAP
): { items: UpstreamItem[]; truncated: boolean } {
  const byId = new Map<string, CanvasNode>()
  for (const n of nodes) byId.set(n.id, n)
  if (!byId.has(nodeId)) return { items: [], truncated: false }

  // 反向邻接：target → sources（一次遍历建表，O(V+E)，禁逐节点查父）
  const up = new Map<string, string[]>()
  for (const e of edges) {
    if (!up.has(e.target)) up.set(e.target, [])
    up.get(e.target)!.push(e.source)
  }

  const visited = new Set<string>([nodeId]) // 含 seed：自环/回到自身不再入队
  const items: UpstreamItem[] = []
  let truncated = false
  let queue: { id: string; depth: number }[] = [{ id: nodeId, depth: 0 }]
  while (queue.length) {
    const next: { id: string; depth: number }[] = []
    for (const cur of queue) {
      for (const p of up.get(cur.id) ?? []) {
        if (visited.has(p)) continue // 环/菱形共用上游：只按最浅深度收一次
        visited.add(p)
        if (items.length >= cap) {
          truncated = true
          continue // 仍标记 visited，防下轮重复计数
        }
        const node = byId.get(p)
        if (node) items.push({ node, depth: cur.depth + 1 })
        next.push({ id: p, depth: cur.depth + 1 })
      }
    }
    queue = next
  }
  return { items, truncated }
}
