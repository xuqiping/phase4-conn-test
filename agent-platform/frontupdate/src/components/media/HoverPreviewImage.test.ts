import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { NPopover } from 'naive-ui'
import HoverPreviewImage from './HoverPreviewImage.vue'

// 悬浮放大（4x#3/6x#1）：300ms 防抖弹出、移出即关、快速划过不弹、delay 可调
describe('HoverPreviewImage', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  function mountThumb(props: Record<string, unknown> = {}) {
    return mount(HoverPreviewImage, {
      props: { previewSrc: 'blob:big', ...props },
      slots: { default: '<img src="blob:thumb" alt="缩略" />' }
    })
  }
  const shown = (w: ReturnType<typeof mountThumb>) =>
    w.findComponent(NPopover).props('show') as boolean

  it('停留满 300ms 才弹；移出即关', async () => {
    const w = mountThumb()
    const span = w.find('.hover-preview-image')
    await span.trigger('mouseenter')

    vi.advanceTimersByTime(299)
    expect(shown(w)).toBe(false)
    vi.advanceTimersByTime(1)
    await nextTick() // show ref 变更后等 NPopover 重渲染
    expect(shown(w)).toBe(true)

    await span.trigger('mouseleave')
    expect(shown(w)).toBe(false)
  })

  it('快速划过（<300ms 离开）不弹（防抖取消）', async () => {
    const w = mountThumb()
    const span = w.find('.hover-preview-image')
    await span.trigger('mouseenter')
    vi.advanceTimersByTime(120)
    await span.trigger('mouseleave')
    vi.advanceTimersByTime(500)
    expect(shown(w)).toBe(false)
  })

  it('delay 可调（100ms 生效）', async () => {
    const w = mountThumb({ delay: 100 })
    await w.find('.hover-preview-image').trigger('mouseenter')
    vi.advanceTimersByTime(100)
    await nextTick()
    expect(shown(w)).toBe(true)
  })

  it('悬浮前再次进入重置计时（不提前弹）', async () => {
    const w = mountThumb()
    const span = w.find('.hover-preview-image')
    await span.trigger('mouseenter')
    vi.advanceTimersByTime(200)
    await span.trigger('mouseenter') // 重新计时
    vi.advanceTimersByTime(200)
    expect(shown(w)).toBe(false)
    vi.advanceTimersByTime(100)
    await nextTick()
    expect(shown(w)).toBe(true)
  })

  it('previewSrc 缺失弹占位文案而非破图', async () => {
    const w = mountThumb({ previewSrc: null })
    await w.find('.hover-preview-image').trigger('mouseenter')
    vi.advanceTimersByTime(300)
    await nextTick()
    expect(shown(w)).toBe(true)
    expect(document.body.textContent).toContain('预览未加载')
  })
})
