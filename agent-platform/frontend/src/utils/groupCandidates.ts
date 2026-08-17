// ============================================================
// 2x 四轮 S9：节点组 @候选并集（纯函数，无 Vue 依赖）
//
// 规则（plan §Step9-④ / 规格 §10.3）：
//   候选 = ancestors ∪（祖先命中组任一成员 → 该组全员）
//   - 孤立组（无任何成员在 ancestors）→ 整组不进候选（防越权引用未连通节点）
//   - 命中组的**全部成员**归入该组分节（含非祖先成员——组即引用单元）
//   - 未命中组里的祖先成员 → 仍按散节点列出（它本身是合法上游）
// 排序：散节点在前（原节点序），命中组按 groups 数组序分节在后。
// ============================================================

import type { CanvasGroup, CanvasNode, MentionCandidate } from '@/types/canvas'

/** 组员上限（plan 运维容量约束：包围盒 rAF 重算的成员规模上界）。 */
export const MAX_GROUP_MEMBERS = 50

/** 8 色板（组色轮转；画布 8 色标注语义同源色系）。 */
export const GROUP_COLORS = [
  '#f6696b', '#ff9f43', '#ffd700', '#2dd4bf',
  '#60a5fa', '#a78bfa', '#f472b6', '#94a3b8'
]

/** 建组取色：按现存组数轮转色板。 */
export function nextGroupColor(groups: CanvasGroup[]): string {
  return GROUP_COLORS[groups.length % GROUP_COLORS.length]
}

/**
 * @候选并集扩展（mentionCandidates 数据源）。
 *
 * @param ancestorIds 选中节点的祖先集（反向 BFS 产物）
 * @param nodes       画布全部节点（取 label/存在性）
 * @param groups      全部节点组
 * @param labelOf     节点显示名提取（label ?? id）
 * @returns 分节候选列表：散祖先节点（无组或组未命中）+ 各命中组全员（带 groupId/groupLabel/groupColor）
 */
export function expandGroupCandidates(
  ancestorIds: Set<string>,
  nodes: CanvasNode[],
  groups: CanvasGroup[],
  labelOf: (n: CanvasNode) => string
): MentionCandidate[] {
  if (!groups.length) {
    return nodes
      .filter(n => ancestorIds.has(n.id))
      .map(n => ({ kind: 'node' as const, id: n.id, label: labelOf(n) }))
  }

  // 命中组：组内任一成员 ∈ ancestors
  const hitGroups = groups.filter(g => g.memberIds.some(id => ancestorIds.has(id)))
  // 节点 → 所属命中组（一节点只属一组——建组时已自动移出旧组）
  const nodeToHitGroup = new Map<string, CanvasGroup>()
  for (const g of hitGroups) {
    for (const id of g.memberIds) nodeToHitGroup.set(id, g)
  }

  const out: MentionCandidate[] = []
  // ① 散祖先节点：无组，或所属组未命中（未命中组的祖先成员仍是合法候选）
  for (const n of nodes) {
    if (!ancestorIds.has(n.id)) continue
    const g = nodeToHitGroup.get(n.id)
    if (g) continue // 命中组成员归组节展示
    out.push({ kind: 'node', id: n.id, label: labelOf(n) })
  }
  // ② 命中组全员分节（组序 = groups 数组序；成员按 nodes 序）
  const nodesById = new Map(nodes.map(n => [n.id, n]))
  for (const g of hitGroups) {
    for (const id of g.memberIds) {
      const n = nodesById.get(id)
      if (!n) continue
      out.push({
        kind: 'node',
        id,
        label: labelOf(n),
        groupId: g.id,
        groupLabel: g.name,
        groupColor: g.color
      })
    }
  }
  return out
}
