import type { CanvasEdge, CanvasGroup, CanvasNode, CanvasNodeData } from '@/types/canvas'
import { uniqueLabel } from '@/utils/interpolate'
import { estimateSize } from '@/utils/autoLayout'
import { groupEndpointOf, groupIdOf, isGroupEndpoint } from '@/utils/groupEdges'
import { RESET_KEYS } from './nodeClone'

/**
 * 修复VII（2x 增补①）：画布节点复制粘贴纯函数（VII-1，Q1 多选子图 / Q2 鼠标落点）。
 * 与 nodeClone 的分工：nodeClone=单节点「创建副本」（连边克隆含跨集边）；本模块=
 * Ctrl+C/V 子图复制——**只带诱导边**（两端都在选中集内的边，Q1 决策口径），脱钩语义
 * 完全同源（共用 RESET_KEYS + status 重算）。DOM/键盘接线在 CanvasBoard（Chunk4）。
 */

/** 复制条目（key=源节点 id，边重映射用；data 已脱钩）。 */
export interface ClipboardItem {
  key: string
  type: string
  data: CanvasNodeData
  position: { x: number; y: number }
  width: number
  height: number
}

/**
 * 进板组快照（修复XI XI-4 D3，Q7 完全包含即带组）：memberIds 为**旧节点 id**——
 * 粘贴时经 keyToNewId 重映射成新成员；新组 id/name 由粘贴方（D4）分配去重。
 */
export interface ClipboardGroup {
  name: string
  color: string
  memberIds: string[]
}

/**
 * 组级边快照（D3）：端点 key = 旧节点 id | `group:${clip.groups 下标}`（板内组伪引用，
 * 粘贴时映射新组）。覆盖三类——成员↔进板组（内边口径，两端都在板）、进板组↔板外节点
 * （组级跨边，对端保旧 id 粘贴时 alive 校验）、进板组↔进板组（两端下标，粘贴判新↔新）。
 * 与 crossEdges 的分工：组**不在**板的组端点边（半含组）仍走 crossEdges 连**原组**
 * （修复X X-3 口径不变）；组进板后其所有组端点边统一改走本数组（连接对象变**新组**）。
 */
export interface GroupCrossEdge {
  fromKey: string
  toKey: string
}

/** 组件内剪贴板（会话态，不入库不跨画布——规格 §6/§8 口径）。 */
export interface CanvasClipboard {
  items: ClipboardItem[]
  /** 进板组（完全包含才收；无组复制=空数组，向后兼容 VII/IX/X 既有链路）。 */
  groups: ClipboardGroup[]
  /** 组级边（见 GroupCrossEdge；无组=空数组）。 */
  groupCrossEdges: GroupCrossEdge[]
  /** 诱导边快照（原 id，粘贴时经 remapEdges 重映射）。 */
  innerEdges: CanvasEdge[]
  /**
   * 跨集边快照（修复IX-2 B1，Q4 拍板）：恰一端在选中集的边。**恒收集**——复制时点
   * 不看开关，粘贴时点由调用方按 keepLinksOnCopy 决定走不走 remapCrossEdges
   * （复制后切开关，按粘贴当下所见生效）。浅拷贝断响应式链（同 innerEdges Y1 口径）。
   * 修复X（2x 未解决③，X-3）：**组端点跨集边一并纳入**——组伪 id 恒不在节点选中集，
   * 「恰一端在集」判 true 即节点↔组边（新节点连原组，Q3 拍板）；组→组/组自环两端
   * 都不在集判 false 天然不收。
   */
  crossEdges: CanvasEdge[]
  /** 复制集包围盒（落点对齐用）。 */
  bbox: { left: number; top: number; width: number; height: number }
  /** 连续粘贴计数（每按一次 +32 错开）。 */
  pasteCount: number
}

/** 选中集 → 剪贴板。空选/全失效返回 null（调用方据此清空剪贴板恢复图片粘贴通道）。 */
export function buildCopySet(
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  selectedIds: string[],
  /** 画布现有组（修复XI D3）：完全包含（成员全在选中集且非空）才整组进板（Q7）。 */
  groups: CanvasGroup[] = []
): CanvasClipboard | null {
  const ids = new Set(selectedIds)
  const selected = nodes.filter(n => ids.has(n.id))
  if (!selected.length) return null
  const items = selected.map(n => {
    // JSON 深拷贝断响应式链（nodeClone/cloneHistoryState 同款范式）
    const data = JSON.parse(JSON.stringify(n.data ?? {})) as CanvasNodeData
    for (const k of RESET_KEYS) delete data[k]
    data.status = (data.previewUrl || data.outputText) ? 'success' : 'idle'
    const size = estimateSize(n.type, data)
    return {
      key: n.id,
      type: n.type,
      data,
      position: { ...n.position },
      width: size.width,
      height: size.height
    }
  })
  // 诱导边：两端都在集内（自环保留——粘贴体自身成环，同 cloneEdgesForDuplicate 口径）。
  // 组端点仍排除（VIII-1 ⑧ 口径保留半边——诱导边是「粘贴体内部结构」，组边是「与原组
  // 的外部连接」，两者语义不同；修复X 只放开后者）。
  // 浅拷贝断响应式链（P4 交叉 review Y1）：存源对象引用会让 remapEdges 在**粘贴时刻**读到
  // 会话 class（如复制后又点了该边 → 选中态 class 被烤进新边，高亮永久残留）。
  const innerEdges = edges
    .filter(e => !isGroupEndpoint(e.source) && !isGroupEndpoint(e.target))
    .filter(e => ids.has(e.source) && ids.has(e.target))
    .map(e => ({ ...e }))
  // 跨集边（IX-2 B1）：恰一端在集内——恒收集进剪贴板；粘贴时开关开 → remapCrossEdges
  // 单侧重映射（集内端换新 id，集外端保原 id）；关 → 不走此数组，粘贴体零跨集边。
  // 修复X（X-3）：组伪 id 不在 ids，节点↔组边恰一端在集判 true 被收（新节点连原组）。
  // 修复XI（D3）改分治：组端点边先按「组是否进板」分流——进板组的边走 groupCrossEdges
  // （连接对象=新组），不进板（半含/组外组）照旧走本数组（连原组，X-3 口径不变）。
  const clipGroups: ClipboardGroup[] = []
  const inclIdx = new Map<string, number>()
  groups.forEach(g => {
    if (!g.memberIds.length || !g.memberIds.every(id => ids.has(id))) return // 半含不收（Q7 完全包含）
    inclIdx.set(g.id, clipGroups.length)
    clipGroups.push({ name: g.name, color: g.color, memberIds: [...g.memberIds] })
  })
  const groupCrossEdges: GroupCrossEdge[] = []
  const crossEdges: CanvasEdge[] = []
  // 组端点 key 分流（P4 交叉 review 中①）：进板组→`group:{板内下标}`；**板外组保原伪 id**
  // `group:{原组 id}`——组↔组恰一端进板时对端组不在板，串化 `group:undefined` 会让粘贴端
  // 解析 NaN 静默丢边；保原伪 id 即 X-3「连原组」口径（remapGroupCrossEdges 同步分流解析）。
  const groupKeyOf = (gid: string | null, orig: string): string =>
    gid !== null && inclIdx.has(gid) ? `group:${inclIdx.get(gid)}` : orig
  for (const e of edges) {
    const srcGroup = isGroupEndpoint(e.source) ? groupIdOf(e.source) : null
    const dstGroup = isGroupEndpoint(e.target) ? groupIdOf(e.target) : null
    // 任一端=进板组 → 组级边统一进 groupCrossEdges（对端 key=旧节点 id/板内组下标/原组伪 id）；
    // 两端都在板（成员↔本组/组↔组）与对端在外（组级跨边）共用同一存储，粘贴端分流。
    if ((srcGroup !== null && inclIdx.has(srcGroup)) || (dstGroup !== null && inclIdx.has(dstGroup))) {
      groupCrossEdges.push({
        fromKey: groupKeyOf(srcGroup, e.source),
        toKey: groupKeyOf(dstGroup, e.target)
      })
      continue
    }
    // 组端点边但组不进板（半含组/组外组）：X-3 口径照旧——恰一端（节点侧）在集 → crossEdges 连原组
    if ((srcGroup !== null || dstGroup !== null)) {
      if ((ids.has(e.source) !== ids.has(e.target))) crossEdges.push({ ...e })
      continue
    }
    // 普通节点边：恰一端在集 → crossEdges（IX-2 B1 原口径）
    if (ids.has(e.source) !== ids.has(e.target)) crossEdges.push({ ...e })
  }
  const left = Math.min(...items.map(i => i.position.x))
  const top = Math.min(...items.map(i => i.position.y))
  const right = Math.max(...items.map(i => i.position.x + i.width))
  const bottom = Math.max(...items.map(i => i.position.y + i.height))
  return {
    items,
    groups: clipGroups,
    groupCrossEdges,
    innerEdges,
    crossEdges,
    bbox: { left, top, width: right - left, height: bottom - top },
    pasteCount: 0
  }
}

const GRID = 16
function snap(v: number): number {
  return Math.round(v / GRID) * GRID
}

/**
 * 粘贴落点（Q2）：包围盒**中心**平移到鼠标画布坐标；pasteCount>0 时整体再
 * +32×n 错开（连按不叠）；末尾 16 网格对齐（同拖拽 snap，防后续手拖跳格）。
 */
export function planPastePositions(
  clip: CanvasClipboard,
  target: { x: number; y: number }
): { key: string; x: number; y: number }[] {
  const dx = target.x - (clip.bbox.left + clip.bbox.width / 2) + clip.pasteCount * 32
  const dy = target.y - (clip.bbox.top + clip.bbox.height / 2) + clip.pasteCount * 32
  return clip.items.map(it => ({ key: it.key, x: snap(it.position.x + dx), y: snap(it.position.y + dy) }))
}

/** 批内+对画布现有 label 三级去重（撞名追加序号；@文本引用不重写——内容引用非结构）。 */
export function planLabels(
  clip: CanvasClipboard,
  existingLabels: string[]
): string[] {
  const used = [...existingLabels]
  return clip.items.map(it => {
    const label = uniqueLabel(String(it.data.label ?? '新节点'), used)
    used.push(label)
    return label
  })
}

/** 边重映射序号：同毫秒批量克隆（多条边一次入集）防撞。 */
let remapSeq = 0

/** 诱导边 → 新边（端点换新节点 id；handles/type 随展开保留，同 cloneEdgesForDuplicate；class 为会话态不带走）。 */
export function remapEdges(
  clip: CanvasClipboard,
  keyToNewId: Map<string, string>
): CanvasEdge[] {
  return clip.innerEdges
    .map(e => {
      const source = keyToNewId.get(e.source)
      const target = keyToNewId.get(e.target)
      if (!source || !target) return null // 理论不可达（诱导边端点必在 items），双保险
      // 剥会话 class（P4 交叉 review Y1 双保险）：复制时刻边可能正处选中/淡化态，
      // 烤进新边=永久高亮残留（applyVisualClasses 不重算存量边 class）。
      const { class: _sessionClass, ...rest } = e
      return {
        ...rest,
        id: `edge-${source}-${target}-${Date.now()}-${remapSeq++}`,
        source,
        target
      }
    })
    .filter((e): e is CanvasEdge => e !== null)
}

/**
 * 跨集边 → 新边（修复IX-2 B1，Q4 拍板——开关开时粘贴补连线）：**单侧**重映射——
 * 集内端换新节点 id、集外端保原 id（原节点连线延伸到副本，语义=「副本接入原上下文」）。
 * 防悬挂：集外端点已不在画布存活集（复制后原节点被删/组被解散）→ 丢该边，绝不产出
 * 引用不存在端点的断边。修复X（X-3，Q3 拍板）：组端点边纳入——组伪 id 端保原伪 id
 * （新节点=组**外部对端**，不入组员），alive 校验组端查 aliveGroupIds（groupIdOf）。
 * 允许与既有边平行重复（同 source/target 多条并存，Q4 口径——去重=丢用户结构，不做）。
 */
export function remapCrossEdges(
  clip: CanvasClipboard,
  keyToNewId: Map<string, string>,
  aliveNodeIds: Set<string>,
  aliveGroupIds: Set<string>
): CanvasEdge[] {
  // 端点存活分流：组伪 id → 组集（去前缀查）；节点 id → 节点集
  const isAlive = (id: string): boolean =>
    isGroupEndpoint(id) ? aliveGroupIds.has(groupIdOf(id)) : aliveNodeIds.has(id)
  return clip.crossEdges
    .map(e => {
      const sourceIn = keyToNewId.has(e.source)
      const targetIn = keyToNewId.has(e.target)
      // 恒收集时点保证恰一端在集内；复制后结构变化 → 两端都不在/都在的退化情形按不可重映射丢
      if (sourceIn === targetIn) return null
      const source = sourceIn ? keyToNewId.get(e.source)! : e.source
      const target = targetIn ? keyToNewId.get(e.target)! : e.target
      if (!isAlive(source) || !isAlive(target)) return null
      const { class: _sessionClass, ...rest } = e
      return {
        ...rest,
        id: `edge-${source}-${target}-${Date.now()}-${remapSeq++}`,
        source,
        target
      }
    })
    .filter((e): e is CanvasEdge => e !== null)
}

/**
 * 组级边 → 新边（修复XI D3→D4，⛓ 开时粘贴补连线）：key 解析——
 * - `group:${idx}`（板内组下标）→ 新组伪 id（粘贴方重建组时产出；下标失配=理论不可达，丢）；
 * - 节点 id：在 keyToNewId（板内成员）→ 新节点 id（粘贴体必有，恒活）；不在 = 板外对端
 *   → 保旧 id，aliveNodeIds 校验（复制后原节点被删 → 丢，绝不产断边，同 remapCrossEdges 口径）。
 * 产物含组伪 id 端点 → 调用方落 groupEdges 池（不进 v-model）；id 序号防同毫秒撞。
 */
export function remapGroupCrossEdges(
  clip: CanvasClipboard,
  keyToNewId: Map<string, string>,
  groupIdxToNewId: Map<number, string>,
  aliveNodeIds: Set<string>,
  /** P4 交叉 review 中①：板外原组端（`group:{原组 id}`）存活校验集（X-3 连原组口径）。 */
  aliveGroupIds: Set<string>
): CanvasEdge[] {
  const resolve = (key: string): string | null => {
    if (key.startsWith('group:')) {
      const idx = Number(key.slice('group:'.length))
      // 板内下标 → 新组伪 id；非数字 = 原组伪 id（组↔组恰一端进板的对端）——保原 id 连原组，
      // 原组已删/解散 → 丢（同 remapCrossEdges 防悬挂口径）。
      if (Number.isInteger(idx)) {
        const newId = groupIdxToNewId.get(idx)
        return newId ? groupEndpointOf(newId) : null
      }
      return aliveGroupIds.has(key.slice('group:'.length)) ? key : null
    }
    if (keyToNewId.has(key)) return keyToNewId.get(key)!
    return aliveNodeIds.has(key) ? key : null // 板外对端：已删 → 丢
  }
  return clip.groupCrossEdges
    .map(({ fromKey, toKey }) => {
      const source = resolve(fromKey)
      const target = resolve(toKey)
      if (!source || !target) return null
      return {
        id: `edge-${source}-${target}-${Date.now()}-${remapSeq++}`,
        source,
        target
      } as CanvasEdge
    })
    .filter((e): e is CanvasEdge => e !== null)
}
