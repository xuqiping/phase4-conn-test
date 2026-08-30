// ============================================================
// D4（2x-9）：@候选弹层光标锚定。
// 纯定位数学 placePopover（可单测：上方优先/越界翻转/左右夹边）
// + contenteditable 光标矩形探测 caretRect（DOM 薄封装，jsdom 无布局时返 null 兜底）。
// 注：原计划 textarea 镜像 div 量宽——A1 已把 MentionTextarea 重写为
// contenteditable，浏览器原生 Range.getClientRects 直接给光标矩形，无需镜像。
// ============================================================

export interface PopoverPos {
  left: number
  top: number
  placement: 'above' | 'below'
}

/**
 * 弹层落点：锚在光标矩形。上方优先（底部停靠输入框场景不被遮），
 * 锚点上方放不下（top<0）→ 翻转到光标下方；left 夹在 [0, rootW-popW]。
 */
export function placePopover(args: {
  /** 光标矩形（相对锚定容器的坐标）。 */
  caretX: number
  caretY: number
  caretH: number
  /** 锚定容器宽（弹层不越右界）。 */
  rootW: number
  popW: number
  popH: number
  /** 光标与弹层间距（默认 4）。 */
  margin?: number
}): PopoverPos {
  const { caretX, caretY, caretH, rootW, popW, popH, margin = 4 } = args
  let top = caretY - margin - popH
  let placement: 'above' | 'below' = 'above'
  if (top < 0) {
    top = caretY + caretH + margin
    placement = 'below'
  }
  const left = Math.max(0, Math.min(caretX, Math.max(0, rootW - popW)))
  return { left, top, placement }
}

/**
 * 取 contenteditable 当前光标矩形（视口坐标；高度=行高）。
 * 折叠 Range 取 getClientRects 首个，空时回落 getBoundingClientRect，
 * 仍全零（空编辑器首行/jsdom 无布局）→ 返回 null（调用方回落静态定位）。
 */
export function caretViewportRect(el: HTMLElement): { left: number; top: number; height: number } | null {
  const sel = window.getSelection()
  if (!el.isConnected || !sel || sel.rangeCount === 0) return null
  const range = sel.getRangeAt(0).cloneRange()
  const rects = range.getClientRects()
  let rect: DOMRect | null = rects.length ? (rects[0] as DOMRect) : null
  if (!rect) rect = range.getBoundingClientRect()
  if (rect.width === 0 && rect.height === 0 && rect.top === 0 && rect.left === 0) {
    // 空编辑器首行：光标矩形常全零 → 用编辑器内容盒顶左角兜底
    const box = el.getBoundingClientRect()
    if (box.width === 0 && box.height === 0) return null
    return { left: box.left, top: box.top, height: 0 }
  }
  return { left: rect.left, top: rect.top, height: rect.height }
}
