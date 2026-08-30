import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import AnnotateOverlay from './AnnotateOverlay.vue'

/**
 * 2x 四轮 S7 AnnotateOverlay 单测（plan 验证项：坐标归一化 / 框数上限 / 微框丢弃 / 空框禁用）。
 * 像素合成与颜色白名单在后端 VideoFrameServiceTest（annotate* 用例）覆盖。
 *
 * 坑：stage/img 尺寸来自 getBoundingClientRect——mock 原型固定 200x100，
 * 归一化断言才确定（0-1 值 = px / 200 或 /100）。
 */
const RECT = { x: 0, y: 0, top: 0, left: 0, right: 200, bottom: 100, width: 200, height: 100, toJSON: () => ({}) }
let rectSpy: ReturnType<typeof vi.spyOn>

function mountOverlay() {
  return mount(AnnotateOverlay, {
    props: { previewUrl: 'blob:preview' },
    global: { stubs: { teleport: true, 'n-button': { template: '<button><slot /></button>', emits: [] }, 'n-input': true } }
  })
}

function dragBox(w: ReturnType<typeof mountOverlay>, x0: number, y0: number, x1: number, y1: number) {
  const stage = w.find('.annotate-overlay__stage')
  stage.trigger('mousedown', { clientX: x0, clientY: y0, button: 0 })
  // mousemove/mouseup 绑在弹层根 div（同 FocusEditOverlay 范式），不在 stage 上
  w.find('.annotate-overlay').trigger('mousemove', { clientX: x1, clientY: y1 })
  w.find('.annotate-overlay').trigger('mouseup')
}

describe('AnnotateOverlay', () => {
  beforeEach(() => {
    rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(RECT as DOMRect)
  })
  afterEach(() => {
    rectSpy.mockRestore()
  })

  it('拖一个框 → 确认载荷坐标按 stage 尺寸归一化', async () => {
    const w = mountOverlay()
    // (100,20) → (180,60)：x=0.5 y=0.2 w=0.4 h=0.4，默认红
    dragBox(w, 100, 20, 180, 60)
    await Promise.resolve() // 等按钮 disabled→enabled 的 DOM 刷新（trigger 派发先于刷新）
    await w.find('.annotate-overlay').findAll('button').find(b => b.text() === '生成标注图')!.trigger('click')
    const payload = w.emitted('confirm-annotate')?.[0]?.[0] as {
      boxes: Array<{ x: number; y: number; w: number; h: number; color: string }>
      instructions: Array<{ index: number; text: string }>
    }
    expect(payload.boxes).toHaveLength(1)
    expect(payload.boxes[0]).toEqual({ x: 0.5, y: 0.2, w: 0.4, h: 0.4, color: 'red' })
    expect(payload.instructions[0].index).toBe(1)
  })

  it('框数上限 8：第 9 次 mousedown 不再新增', async () => {
    const w = mountOverlay()
    for (let i = 0; i < 9; i++) {
      // 各框错开但都在 stage 内（x 0-180，步进 10 保证 w=20）
      dragBox(w, 5 + i * 12, 5, 25 + i * 12, 25)
      await Promise.resolve()
    }
    await new Promise(r => setTimeout(r, 0))
    expect(w.findAll('.annotate-overlay__box')).toHaveLength(8)
  })

  it('微框（<8px 抖动误触）mouseup 即弃，不入数组', async () => {
    const w = mountOverlay()
    dragBox(w, 100, 20, 103, 23) // 3x3 微框
    await new Promise(r => setTimeout(r, 0))
    expect(w.findAll('.annotate-overlay__box')).toHaveLength(0)
  })

  it('无框时两个确认按钮禁用（L4-2）', () => {
    const w = mountOverlay()
    const confirmBtns = w.findAll('button').filter(b =>
      b.text() === '生成标注图' || b.text() === 'AI 修改')
    expect(confirmBtns).toHaveLength(2)
    for (const b of confirmBtns) {
      expect(b.attributes('disabled')).toBeDefined()
    }
  })

  it('点色板改当前选中框颜色（L4-3）', async () => {
    const w = mountOverlay()
    dragBox(w, 100, 20, 180, 60)
    await Promise.resolve()
    // 拖完自动选中该框；点第 6 个色板（blue）
    await w.findAll('.annotate-overlay__swatch')[5].trigger('click')
    await w.find('.annotate-overlay').findAll('button').find(b => b.text() === '生成标注图')!.trigger('click')
    const payload = w.emitted('confirm-annotate')?.[0]?.[0] as { boxes: Array<{ color: string }> }
    expect(payload.boxes[0].color).toBe('blue')
  })
})
