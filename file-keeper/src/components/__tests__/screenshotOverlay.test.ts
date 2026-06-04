import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ScreenshotOverlay from '../ScreenshotOverlay.vue'

describe('ScreenshotOverlay', () => {
  it('emits selected region after drag selection', async () => {
    const wrapper = mount(ScreenshotOverlay)

    await wrapper.trigger('mousedown', { clientX: 20, clientY: 30, screenX: 20, screenY: 30 })
    await wrapper.trigger('mousemove', { clientX: 220, clientY: 130, screenX: 220, screenY: 130 })
    await wrapper.trigger('mouseup', { clientX: 220, clientY: 130, screenX: 220, screenY: 130 })

    expect(wrapper.emitted('capture')?.[0]?.[0]).toMatchObject({
      x: 20,
      y: 30,
      width: 200,
      height: 100
    })
  })

  it('uses screen coordinates for capture region while drawing with client coordinates', async () => {
    const wrapper = mount(ScreenshotOverlay)

    await wrapper.trigger('mousedown', { clientX: 20, clientY: 30, screenX: 520, screenY: 330 })
    await wrapper.trigger('mousemove', { clientX: 220, clientY: 130, screenX: 720, screenY: 430 })

    const selection = wrapper.find('.border-white')
    expect(selection.attributes('style')).toContain('left: 20px;')
    expect(selection.attributes('style')).toContain('top: 30px;')
    expect(selection.attributes('style')).toContain('width: 200px;')
    expect(selection.attributes('style')).toContain('height: 100px;')

    await wrapper.trigger('mouseup', { clientX: 220, clientY: 130, screenX: 720, screenY: 430 })

    expect(wrapper.emitted('capture')?.[0]?.[0]).toMatchObject({
      x: 520,
      y: 330,
      width: 200,
      height: 100
    })
  })

  it('normalizes drag direction', async () => {
    const wrapper = mount(ScreenshotOverlay)

    await wrapper.trigger('mousedown', { clientX: 220, clientY: 130, screenX: 720, screenY: 430 })
    await wrapper.trigger('mousemove', { clientX: 20, clientY: 30, screenX: 520, screenY: 330 })
    await wrapper.trigger('mouseup', { clientX: 20, clientY: 30, screenX: 520, screenY: 330 })

    expect(wrapper.emitted('capture')?.[0]?.[0]).toMatchObject({
      x: 520,
      y: 330,
      width: 200,
      height: 100
    })
  })

  it('cancels tiny selections', async () => {
    const wrapper = mount(ScreenshotOverlay)

    await wrapper.trigger('mousedown', { clientX: 20, clientY: 30 })
    await wrapper.trigger('mousemove', { clientX: 23, clientY: 35 })
    await wrapper.trigger('mouseup', { clientX: 23, clientY: 35 })

    expect(wrapper.emitted('capture')).toBeUndefined()
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('cancels on Escape', async () => {
    const wrapper = mount(ScreenshotOverlay, { attachTo: document.body })

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('cancel')).toHaveLength(1)
    wrapper.unmount()
  })
})
