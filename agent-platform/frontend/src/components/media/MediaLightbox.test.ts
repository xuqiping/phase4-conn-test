import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import MediaLightbox from './MediaLightbox.vue'

// 共享灯箱（4x#3/6x#1）：ImageGenView 抽取，画布/反推复用——关闭三路 + 监听器防泄漏
describe('MediaLightbox', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
  })

  it('src=null 不渲染遮罩', () => {
    mount(MediaLightbox, { props: { src: null } })
    expect(document.querySelector('.media-lightbox')).toBeNull()
  })

  it('src 打开渲染大图；点遮罩 emit close；点图本身不关', async () => {
    const w = mount(MediaLightbox, { props: { src: 'blob:preview-1' } })
    const overlay = document.querySelector('.media-lightbox') as HTMLElement
    const img = overlay.querySelector('img') as HTMLImageElement
    expect(img.getAttribute('src')).toBe('blob:preview-1')

    img.dispatchEvent(new MouseEvent('click'))
    expect(w.emitted('close')).toBeUndefined()

    overlay.dispatchEvent(new MouseEvent('click'))
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('Esc 触发 close；src 置空后 Esc 监听已移除', async () => {
    const w = mount(MediaLightbox, { props: { src: 'blob:p' } })
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(w.emitted('close')).toHaveLength(1)

    await w.setProps({ src: null })
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(w.emitted('close')).toHaveLength(1) // 不新增=监听器已摘除
  })

  it('unmount 移除 keydown 监听（防泄漏）', () => {
    const spy = vi.spyOn(window, 'removeEventListener')
    const w = mount(MediaLightbox, { props: { src: 'blob:p' } })
    w.unmount()
    expect(spy).toHaveBeenCalledWith('keydown', expect.any(Function))
  })
})
