import type { CanvasEdge, CanvasNode } from '@/types/canvas'
import { ancestors } from './interpolate'

/**
 * 3x-C2 一键关联（LibTV「Link：按名称自动匹配引用」思路）：
 * 框选文本类节点 → 在其祖先节点（连线可达上游）的 label / 已引用资产名里找
 * 出现在目标文本中的名字 → 提案替换为 @{{node:id}} 占位符（等价手动 @）。
 *
 * 防误配三原则：
 * 1. 长名优先（按名字长度降序匹配）——防「主角1」吞掉「主角10」的命中区间；
 * 2. 已占用区间不复用——先命中的长名区间标记占用，短名不得在其内部再匹配；
 * 3. 已有 @{{...}} 占位符区间视为占用——绝不二次包装。
 */

/** 文本类节点 → 主文本字段（与 PropertyPanel MentionTextarea 字段一一对应）。 */
const TEXT_LIKE_FIELDS: Record<string, 'prompt' | 'synopsis' | 'description'> = {
  text: 'prompt',
  script: 'synopsis',
  storyboard: 'description'
}

export function textLikeFieldOf(node: CanvasNode): 'prompt' | 'synopsis' | 'description' | null {
  const key = TEXT_LIKE_FIELDS[node.type]
  return key ?? null
}

export interface AssociationProposal {
  /** 目标节点（被替换文本所在节点）。 */
  targetId: string
  targetLabel: string
  /** 主文本字段名（prompt/synopsis/description）。 */
  fieldKey: 'prompt' | 'synopsis' | 'description'
  /** 匹配到的祖先节点（将插入的 @ 引用目标）。 */
  candNodeId: string
  candName: string
  /** 命中区间（原文本索引，应用时按 start 降序替换防位移）。 */
  start: number
  end: number
  /** 替换预览上下文（弹窗展示用）。 */
  before: string
  match: string
  after: string
}

export interface SkippedNode {
  id: string
  label: string
  reason: string
}

interface Range { start: number; end: number }

/** 找出文本中所有 @{{...}} 占位符区间（视为已占用，防二次包装）。 */
function placeholderRanges(text: string): Range[] {
  const re = /@\{\{[^}]*\}\}/g
  const out: Range[] = []
  let m: RegExpExecArray | null
  while ((m = re.exec(text)) !== null) out.push({ start: m.index, end: m.index + m[0].length })
  return out
}

/** name 在 text 中的首个「不与已占用区间重叠」的出现位置；无则 -1。 */
function findFreeOccurrence(text: string, name: string, occupied: Range[]): number {
  let i = text.indexOf(name)
  while (i !== -1) {
    const end = i + name.length
    if (!occupied.some(r => i < r.end && end > r.start)) return i
    i = text.indexOf(name, i + 1)
  }
  return -1
}

/**
 * 生成关联提案。纯函数（快照式读取），不改动任何节点数据。
 * 候选名 = 祖先节点 label + 资产徽标 assetName（均需 ≥2 字符）。
 */
export function buildProposals(params: {
  selectedIds: string[]
  getNodes: () => CanvasNode[]
  getEdges: () => CanvasEdge[]
}): { proposals: AssociationProposal[]; skipped: SkippedNode[] } {
  const { selectedIds, getNodes, getEdges } = params
  const nodes = getNodes()
  const edges = getEdges()
  const byId = new Map(nodes.map(n => [n.id, n]))
  const proposals: AssociationProposal[] = []
  const skipped: SkippedNode[] = []

  for (const targetId of selectedIds) {
    const target = byId.get(targetId)
    if (!target) continue
    const fieldKey = textLikeFieldOf(target)
    if (!fieldKey) {
      skipped.push({ id: targetId, label: String(target.data.label ?? targetId), reason: '非文本类节点' })
      continue
    }
    const text = String(target.data[fieldKey] ?? '')
    if (!text.trim()) {
      skipped.push({ id: targetId, label: String(target.data.label ?? targetId), reason: '文本为空' })
      continue
    }

    // 祖先候选名收集：label + assetName 徽标，去重（同名只留首个来源）
    const anc = ancestors(targetId, edges)
    if (!anc.size) {
      skipped.push({ id: targetId, label: String(target.data.label ?? targetId), reason: '无连线可达的上游节点' })
      continue
    }
    const candNames: { name: string; nodeId: string }[] = []
    const seen = new Set<string>()
    for (const id of anc) {
      const n = byId.get(id)
      if (!n) continue
      for (const raw of [String(n.data.label ?? ''), String(n.data.assetName ?? '')]) {
        const name = raw.trim()
        if (name.length < 2 || seen.has(name)) continue
        seen.add(name)
        candNames.push({ name, nodeId: id })
      }
    }
    if (!candNames.length) {
      skipped.push({ id: targetId, label: String(target.data.label ?? targetId), reason: '上游无可匹配名称' })
      continue
    }

    // 长名优先匹配 + 区间占用
    const occupied = placeholderRanges(text)
    let matchedAny = false
    for (const cand of [...candNames].sort((a, b) => b.name.length - a.name.length)) {
      const i = findFreeOccurrence(text, cand.name, occupied)
      if (i === -1) continue
      const end = i + cand.name.length
      occupied.push({ start: i, end })
      matchedAny = true
      proposals.push({
        targetId,
        targetLabel: String(target.data.label ?? targetId),
        fieldKey,
        candNodeId: cand.nodeId,
        candName: cand.name,
        start: i,
        end,
        before: text.slice(Math.max(0, i - 10), i),
        match: cand.name,
        after: text.slice(end, end + 10)
      })
    }
    if (!matchedAny) {
      skipped.push({ id: targetId, label: String(target.data.label ?? targetId), reason: '上游名称未出现在文本中' })
    }
  }
  return { proposals, skipped }
}

/**
 * 应用勾选的提案：同目标按 start 降序逆序替换（后往前，先替换不影响前面的索引），
 * 占位符替换为 `@{{node:id}} `（尾随空格，与 MentionTextarea 插入行为一致）。
 */
export function applyProposals(
  checked: AssociationProposal[],
  helpers: {
    getNode: (id: string) => CanvasNode | null
    updateNodeData: (id: string, patch: Record<string, unknown>) => void
  }
): { applied: number; targets: number } {
  const byTarget = new Map<string, AssociationProposal[]>()
  for (const p of checked) {
    const list = byTarget.get(p.targetId)
    if (list) list.push(p)
    else byTarget.set(p.targetId, [p])
  }
  let applied = 0
  for (const [targetId, list] of byTarget) {
    const node = helpers.getNode(targetId)
    if (!node) continue
    let text = String(node.data[list[0].fieldKey] ?? '')
    for (const p of [...list].sort((a, b) => b.start - a.start)) {
      if (text.slice(p.start, p.end) !== p.match) continue // 文本已被外部改动则跳过（防御）
      text = text.slice(0, p.start) + `@{{node:${p.candNodeId}}} ` + text.slice(p.end)
      applied++
    }
    helpers.updateNodeData(targetId, { [list[0].fieldKey]: text })
  }
  return { applied, targets: byTarget.size }
}
