// ============================================================
// 节点 @引用插值引擎（S13，设计 §十三）
//
// 职责：
// 1. ancestors(nodeId, edges) —— 反向 BFS 沿 edges 求祖先集（visited 防环）
// 2. parseMentions(text) —— 解析 `@{{node:id}}` / `@{{asset:id}}` 占位符
// 3. interpolate(text, resolve) —— 把占位符替换为被引用方产出文本（**不递归**）
// 4. uniqueLabel(base, existing) —— 同画布节点 label 查重自动序号（L9 三入口）
//
// 设计要点（plan §S13 / 设计 §十三关键决策）：
// - 前端解析、后端零改动：插值发生在 onRunNode 之前，runner 只收最终 prompt
// - 不递归：注入的是上游已物化产出，不再二次解析（A@B、B 含 @ 不展开）
// - 失效语义：被引用方未跑/被删 → resolve 返 undefined → 占位符降级「断链」标记
// ============================================================

/** 连线最小契约（CanvasEdge 子集，解耦 vue-flow 类型）。 */
export interface EdgeLike {
  source: string
  target: string
}

/** 占位符种类与本体（parseMentions 产物）。 */
export interface ParsedMention {
  kind: 'node' | 'asset'
  /** 占位符本体 id（节点 id / 资产 id）。 */
  id: string
  /** 原文匹配片段（含 `@{{}}`，interpolate 时用于精准替换）。 */
  raw: string
}

/**
 * 反向 BFS 求祖先集：沿 target=nodeId 的入边一路向上（传递闭包）。
 * visited 集合防环——画布成环时不会死循环（环内节点互为祖先会被收录，但选择器在环检测后通常为空）。
 * 返回的集合**不含** nodeId 自身（不自引用）。
 */
export function ancestors(nodeId: string, edges: EdgeLike[]): Set<string> {
  const result = new Set<string>()
  const queue: string[] = []
  // 入边邻接表：target → [source...]（反向）
  const inEdges = new Map<string, string[]>()
  for (const e of edges) {
    if (!inEdges.has(e.target)) inEdges.set(e.target, [])
    inEdges.get(e.target)!.push(e.source)
  }
  for (const s of inEdges.get(nodeId) ?? []) queue.push(s)
  while (queue.length) {
    const cur = queue.shift()!
    if (cur === nodeId || result.has(cur)) continue // 防环 + 不自引用
    result.add(cur)
    for (const s of inEdges.get(cur) ?? []) {
      if (!result.has(s) && s !== nodeId) queue.push(s)
    }
  }
  return result
}

/** 占位符正则：`@{{node:xxx}}` / `@{{asset:xxx}}`（id 至少 1 字符，到首个 `}}` 止）。 */
const MENTION_RE = /@\{\{(node|asset):([^}]+)\}\}/g

/** 解析文本中全部占位符（顺序保留，去重由调用方按需）。 */
export function parseMentions(text: string): ParsedMention[] {
  if (!text) return []
  const out: ParsedMention[] = []
  MENTION_RE.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = MENTION_RE.exec(text)) !== null) {
    out.push({ kind: m[1] as 'node' | 'asset', id: m[2], raw: m[0] })
  }
  return out
}

/**
 * 同步解析器：给占位符返注入文本；返 undefined → 该占位符判为断链。
 * 不递归：返串中的 @占位符不再被二次展开（plan 踩坑预判「@占位符解析」）。
 */
export type MentionResolver = (kind: 'node' | 'asset', id: string) => string | undefined

/** 单条占位符的降级文本（运行前可见，提示重连或移除，L7 断链灰显同源）。 */
const BROKEN_MARKER = '【断链】'

/**
 * 把文本中占位符替换为被引用方产出（**不递归**）。
 * resolve 返 undefined 的占位符 → 降级 BROKEN_MARKER（调用方可据 parseMentions 单独统计断链做灰显）。
 */
export function interpolate(text: string, resolve: MentionResolver): string {
  if (!text) return text
  const parsed = parseMentions(text)
  if (!parsed.length) return text
  let out = text
  for (const mention of parsed) {
    const value = resolve(mention.kind, mention.id)
    // 单次替换（同一占位符多次出现也全替）；不递归展开返回串
    const safe = value === undefined ? BROKEN_MARKER : value
    out = out.split(mention.raw).join(safe)
  }
  return out
}

/**
 * 检查文本中是否有断链占位符（被引用方 resolve 不到）。
 * 供属性面板即时灰显（L7/L8：删连线/删节点后 chip 灰显，运行前提示）。
 */
export function findBrokenMentions(text: string, exists: (kind: 'node' | 'asset', id: string) => boolean): string[] {
  return parseMentions(text)
    .filter((m) => !exists(m.kind, m.id))
    .map((m) => m.raw)
}

/**
 * label 唯一化（L9 三入口：新建/粘贴/重命名）。
 * base 不冲突直接返；冲突则加序号「图片 2」「图片 3」直到不冲突。
 * existing 为当前画布全部 label（可含 base 自身——调用方按场景剔除）。
 */
export function uniqueLabel(base: string, existing: string[]): string {
  const set = new Set(existing)
  if (!set.has(base)) return base
  let n = 2
  // 处理 base 本身已带序号的情况：从「图片」「图片 2」推下一个
  while (set.has(`${base} ${n}`)) n++
  return `${base} ${n}`
}
