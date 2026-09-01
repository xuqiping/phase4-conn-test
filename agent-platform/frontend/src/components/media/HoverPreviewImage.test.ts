import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { NPopover } from 'naive-ui'
import HoverPreviewImage from './HoverPreviewImage.vue'

// 修复X B1（2x 未解决②）：kind=video 双态——默认 image 向后兼容（5 处既有调用零改动）。
// NPopover trigger=manual：内容 teleport 到 body 且 show 才渲染——断言走 document 查询。
describe('HoverPreviewImage · 修复X B1 kind 双态', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers(); document.body.innerHTML = '' })

  /** 挂到 body + 悬浮满 delay → popover 内容已出（返回 wrapper）。 */
  async function openPopover(props: { previewSrc: string | null; kind?: 'image' | 'video'; alt?: string }) {
    const wrapper = mount(HoverPreviewImage, {
      props,
      slots: { default: '<span>trigger</span>' },
      attachTo: document.body
    })
    await wrapper.find('.hover-preview-image').trigger('mouseenter')
    await vi.advanceTimersByTimeAsync(300)
    return wrapper
  }

  it('默认（不传 kind）→ 渲染 img 分支（向后兼容基线）', async () => {
    const wrapper = await openPopover({ previewSrc: 'blob:img', alt: '图' })
    const img = document.body.querySelector('.hover-preview-image__big')
    expect(img?.tagName).toBe('IMG')
    expect(img?.getAttribute('src')).toBe('blob:img')
    expect(document.body.querySelector('video')).toBeNull()
    wrapper.unmount()
  })

  it('kind=video → 渲染 video 首帧分支（preload=metadata），无 img 无尺寸行', async () => {
    const wrapper = await openPopover({ previewSrc: 'blob:vid', kind: 'video', alt: '视' })
    const video = document.body.querySelector('.hover-preview-image__big')
    expect(video?.tagName).toBe('VIDEO')
    expect(video?.getAttribute('src')).toBe('blob:vid')
    expect(video?.getAttribute('preload')).toBe('metadata')
    expect(document.body.querySelector('img')).toBeNull()
    // 尺寸行属 img onload 派生——video 态恒不渲染（只存在于 img 分支模板内）
    expect(document.body.querySelector('.hover-preview-image__dims')).toBeNull()
    wrapper.unmount()
  })

  it('previewSrc=null → 占位文案（两态同口径）', async () => {
    const wrapper = await openPopover({ previewSrc: null, kind: 'video' })
    expect(document.body.textContent).toContain('预览未加载')
    expect(document.body.querySelector('video')).toBeNull()
    wrapper.unmount()
  })

  it('悬浮 300ms 防抖后才弹（快速划过不弹，现状口径回归）；移出即关', async () => {
    const wrapper = mount(HoverPreviewImage, {
      props: { previewSrc: 'blob:img' },
      slots: { default: '<span>trigger</span>' },
      attachTo: document.body
    })
    await wrapper.find('.hover-preview-image').trigger('mouseenter')
    await vi.advanceTimersByTimeAsync(100)
    expect(document.body.querySelector('img')).toBeNull() // 未满 300ms 不弹
    await vi.advanceTimersByTimeAsync(250)
    expect(document.body.querySelector('img')).not.toBeNull()
    await wrapper.find('.hover-preview-image').trigger('mouseleave')
    // happy-dom 不派发真实 transitionend（NPopover 收起过渡挂起）——移出即关以
    // v-model:show 绑定态断言（show=false 即内容进入收起，真浏览器由过渡完成卸载）
    expect(wrapper.findComponent(NPopover).props('show')).toBe(false)
    wrapper.unmount()
  })
})
