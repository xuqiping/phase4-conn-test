/**
 * 2x 四轮 S5：选中节点关联闭包（BFS 祖先+后代，设计 §8）。
 * 纯函数与 CanvasBoard 解耦（单测菱形/环/跨分支）；供关联高亮与「只看关联」共用。
 */

/** 关联闭包：命中节点集 + 闭包内两端点均在集内的边集（「命中路径上的边」）。 */
export interface GraphClosure {
  nodeIds: Set<string>
  edgeIds: Set<string>
}

/**
 * seeds 全体各自祖先∪后代（含 seed 本身）的并集；空 seed → null（无高亮态）。
 * 上/下游邻接 Map 一次构建，nodeIds 天然去重防环（BFS 不重复入队）；
 * 多选时 seeds 并集整体求关联（spec 边界：规则同单选）。
 */
export function relatedClosure(
  seeds: readonly string[],
  edges: readonly { id: string; source: string; target: string }[]
): GraphClosure | null {
  const valid = seeds.filter(Boolean)
  if (!valid.length) return null
  const up = new Map<string, string[]>() // target → 上游 sources（反向找祖先）
  const down = new Map<string, string[]>() // source → 下游 targets（找后代）
  for (const e of edges) {
    if (!up.has(e.target)) up.set(e.target, [])
    up.get(e.target)!.push(e.source)
    if (!down.has(e.source)) down.set(e.source, [])
    down.get(e.source)!.push(e.target)
  }
  const nodeIds = new Set<string>(valid)
  const queue = [...valid]
  while (queue.length) {
    const cur = queue.shift()!
    for (const p of up.get(cur) ?? []) {
      if (!nodeIds.has(p)) { nodeIds.add(p); queue.push(p) }
    }
    for (const c of down.get(cur) ?? []) {
      if (!nodeIds.has(c)) { nodeIds.add(c); queue.push(c) }
    }
  }
  // 诱导边集：两端点均入闭包的既有边（含菱形弦边 a→d 这类跨支汇合边）
  const edgeIds = new Set(
    edges.filter(e => nodeIds.has(e.source) && nodeIds.has(e.target)).map(e => e.id)
  )
  return { nodeIds, edgeIds }
}
