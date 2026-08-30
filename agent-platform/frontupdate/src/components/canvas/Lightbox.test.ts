import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Lightbox from './Lightbox.vue'

/** Teleport 打桩渲染在位（jsdom 无 body 布局也可直接 find）。 */
function mountLb(props: Partial<{ open: boolean; kind: 'image' | 'video'; src: string }> = {}) {
  return mount(Lightbox, {
    props: { open: true, kind: 'image', src: 'blob:x', ...props },
    global: { stubs: { teleport: true } }
  })
}

describe('Lightbox · D1 统一预览（2x-8）', () => {
  it('open=false 或 src 空 → 不挂载层', () => {
    const w1 = mountLb({ open: false })
    expect(w1.find('.lbx').exists()).toBe(false)
    const w2 = mountLb({ src: '' })
    expect(w2.find('.lbx').exists()).toBe(false)
  })

  it('图片：滚轮放大（deltaY<0）→ scale>1，transform 生效', async () => {
    const wrapper = mountLb()
    await wrapper.find('.lbx__img').trigger('wheel', { deltaY: -100 })
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as unknown as { scale: number }
    expect(vm.scale).toBeGreaterThan(1)
    // 重渲染会替换 img 元素，必须重新 find（持旧引用读到过期 DOM）
    const img = wrapper.find('.lbx__img').element as HTMLElement
    expect(img.style.transform).toContain(`scale(${vm.scale})`)
  })

  it('缩放边界：0.2–5x 夹紧（连续缩小不破下界，连续放大不破上界）', async () => {
    const wrapper = mountLb()
    const vm = wrapper.vm as unknown as { scale: number; zoomBy: (f: number) => void }
    for (let i = 0; i < 30; i++) vm.zoomBy(1 / 2)
    expect(vm.scale).toBeCloseTo(0.2, 5)
    for (let i = 0; i < 30; i++) vm.zoomBy(2)
    expect(vm.scale).toBeCloseTo(5, 5)
  })

  it('复位（双击或按钮）→ scale/平移归 1/0', async () => {
    const wrapper = mountLb()
    const vm = wrapper.vm as unknown as { scale: number; tx: number; ty: number; zoomBy: (f: number) => void; reset: () => void }
    vm.zoomBy(3)
    vm.tx = 40
    vm.ty = -20
    await wrapper.find('.lbx__img').trigger('dblclick')
    expect(vm.scale).toBe(1)
    expect(vm.tx).toBe(0)
    expect(vm.ty).toBe(0)
  })

  it('Esc → emit close', async () => {
    const wrapper = mountLb()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('点遮罩 → close；点图本体不关（防误触）', async () => {
    const wrapper = mountLb()
    await wrapper.find('.lbx__img').trigger('click')
    expect(wrapper.emitted('close')).toBeUndefined()
    await wrapper.find('.lbx').trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('工具条按钮（缩小/放大/复位）键盘可达（均 button）', () => {
    const wrapper = mountLb()
    const tools = wrapper.findAll('.lbx__tools button')
    expect(tools).toHaveLength(3)
    expect(tools.every(b => b.attributes('aria-label'))).toBe(true)
  })

  it('视频：渲染 <video controls>，无缩放工具条', () => {
    const wrapper = mountLb({ kind: 'video', src: 'blob:v' })
    const video = wrapper.find('video')
    expect(video.exists()).toBe(true)
    expect(video.attributes('controls')).toBeDefined()
    expect(wrapper.find('.lbx__tools').exists()).toBe(false)
  })

  it('a11y：role=dialog + aria-modal', () => {
    const wrapper = mountLb()
    expect(wrapper.find('.lbx').attributes('role')).toBe('dialog')
    expect(wrapper.find('.lbx').attributes('aria-modal')).toBe('true')
  })
})
