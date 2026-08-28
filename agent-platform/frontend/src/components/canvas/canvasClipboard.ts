import type { CanvasEdge, CanvasNode, CanvasNodeData } from '@/types/canvas'
import { uniqueLabel } from '@/utils/interpolate'
import { estimateSize } from '@/utils/autoLayout'
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

/** 组件内剪贴板（会话态，不入库不跨画布——规格 §6/§8 口径）。 */
export interface CanvasClipboard {
  items: ClipboardItem[]
  /** 诱导边快照（原 id，粘贴时经 remapEdges 重映射）。 */
  innerEdges: CanvasEdge[]
  /** 复制集包围盒（落点对齐用）。 */
  bbox: { left: number; top: number; width: number; height: number }
  /** 连续粘贴计数（每按一次 +32 错开）。 */
  pasteCount: number
}

/** 选中集 → 剪贴板。空选/全失效返回 null（调用方据此清空剪贴板恢复图片粘贴通道）。 */
export function buildCopySet(
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  selectedIds: string[]
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
  // 浅拷贝断响应式链（P4 交叉 review Y1）：存源对象引用会让 remapEdges 在**粘贴时刻**读到
  // 会话 class（如复制后又点了该边 → 选中态 class 被烤进新边，高亮永久残留）。
  const innerEdges = edges
    .filter(e => ids.has(e.source) && ids.has(e.target))
    .map(e => ({ ...e }))
  const left = Math.min(...items.map(i => i.position.x))
  const top = Math.min(...items.map(i => i.position.y))
  const right = Math.max(...items.map(i => i.position.x + i.width))
  const bottom = Math.max(...items.map(i => i.position.y + i.height))
  return {
    items,
    innerEdges,
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
