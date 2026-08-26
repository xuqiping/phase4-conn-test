import { describe, expect, it } from 'vitest'
import { caretViewportRect, placePopover } from './caret'

// D4（2x-9）：@候选弹层光标锚定——纯定位数学 + DOM 探测兜底。
describe('placePopover · 上方优先/翻转/夹边', () => {
  it('锚点上方放得下 → 弹层底缘贴光标上方', () => {
    // 光标 y=100，弹层高 80 → top=100-4-80=16（≥0，above）
    const pos = placePopover({ caretX: 50, caretY: 100, caretH: 16, rootW: 400, popW: 240, popH: 80 })
    expect(pos).toEqual({ left: 50, top: 16, placement: 'above' })
  })

  it('上方放不下（首行）→ 翻转到光标下方', () => {
    // 光标 y=20，弹层高 80 → above top=-64 <0 → below：20+16+4=40
    const pos = placePopover({ caretX: 50, caretY: 20, caretH: 16, rootW: 400, popW: 240, popH: 80 })
    expect(pos.placement).toBe('below')
    expect(pos.top).toBe(40)
  })

  it('左右夹在容器内：光标偏右 → left 夹到 rootW-popW', () => {
    const pos = placePopover({ caretX: 380, caretY: 100, caretH: 16, rootW: 400, popW: 240, popH: 80 })
    expect(pos.left).toBe(160)
  })

  it('容器比弹层窄 → left 夹 0', () => {
    const pos = placePopover({ caretX: 30, caretY: 100, caretH: 16, rootW: 200, popW: 240, popH: 80 })
    expect(pos.left).toBe(0)
  })

  it('光标在最左 → left=0', () => {
    const pos = placePopover({ caretX: 0, caretY: 100, caretH: 16, rootW: 400, popW: 240, popH: 80 })
    expect(pos.left).toBe(0)
  })

  it('弹层未量到高（popH=0）→ above 贴光标上方 4px', () => {
    const pos = placePopover({ caretX: 0, caretY: 100, caretH: 16, rootW: 400, popW: 240, popH: 0 })
    expect(pos.top).toBe(96)
    expect(pos.placement).toBe('above')
  })
})

describe('caretViewportRect · DOM 探测兜底', () => {
  it('未挂载元素（isConnected=false）→ null（调用方回落静态定位）', () => {
    const el = document.createElement('div')
    expect(caretViewportRect(el)).toBeNull()
  })

  it('挂载但无选区（rangeCount=0）→ null，不抛错', () => {
    const el = document.createElement('div')
    document.body.appendChild(el)
    try {
      const sel = window.getSelection()
      sel?.removeAllRanges()
      expect(caretViewportRect(el)).toBeNull()
    } finally {
      el.remove()
    }
  })
})
