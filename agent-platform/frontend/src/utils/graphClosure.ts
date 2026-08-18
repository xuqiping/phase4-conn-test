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
 *
 * 2x 四轮 S5 修复：祖先/后代各自**定向** BFS——旧实现从闭包内每个节点同时走上+下，
 * 会把「祖先的另一支后代」也捞进来（seed 与其兄弟姐妹同框不暗化，等于整个连通分量），
 * 实测「选分镜1，同源另一支的 文本3/文本4 不透明」。正确语义：
 * 祖先集只沿 target→source 上行，后代集只沿 source→target 下行，两集∪seeds 即闭包。
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
  // 定向 BFS ×2：上行只追祖先，下行只追后代——不从中间节点反向折返。
  // 两方向各自维护已访集（共享会 让上行已访节点不再入下行队列，漏其下游，环+出边实测漏点）。
  const upSet = new Set<string>(valid)
  const upQueue = [...valid]
  while (upQueue.length) {
    const cur = upQueue.shift()!
    for (const p of up.get(cur) ?? []) {
      if (!upSet.has(p)) { upSet.add(p); upQueue.push(p) }
    }
  }
  const downSet = new Set<string>(valid)
  const downQueue = [...valid]
  while (downQueue.length) {
    const cur = downQueue.shift()!
    for (const c of down.get(cur) ?? []) {
      if (!downSet.has(c)) { downSet.add(c); downQueue.push(c) }
    }
  }
  const nodeIds = new Set<string>([...upSet, ...downSet])
  // 诱导边集：两端点均入闭包的既有边（含菱形弦边 a→d 这类跨支汇合边）
  const edgeIds = new Set(
    edges.filter(e => nodeIds.has(e.source) && nodeIds.has(e.target)).map(e => e.id)
  )
  return { nodeIds, edgeIds }
}
