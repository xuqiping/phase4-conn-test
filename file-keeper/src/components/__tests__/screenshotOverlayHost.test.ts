import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { emit } from '@tauri-apps/api/event'
import { getCurrentWindow } from '@tauri-apps/api/window'
import ScreenshotOverlayHost from '../ScreenshotOverlayHost.vue'

const windowMock = vi.hoisted(() => ({
  destroy: vi.fn().mockResolvedValue(undefined)
}))

vi.mock('@tauri-apps/api/event', () => ({
  emit: vi.fn().mockResolvedValue(undefined)
}))

vi.mock('@tauri-apps/api/window', () => ({
  getCurrentWindow: vi.fn(() => windowMock)
}))

describe('ScreenshotOverlayHost', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })
  it('emits selected screenshot regions to the main window and closes itself', async () => {
    const wrapper = mount(ScreenshotOverlayHost)
    const region = { x: 10, y: 20, width: 100, height: 80, scaleFactor: 1 }

    wrapper.getComponent({ name: 'ScreenshotOverlay' }).vm.$emit('capture', region)
    await vi.waitFor(() => expect(emit).toHaveBeenCalledWith('screenshot://capture', region))

    expect(getCurrentWindow).toHaveBeenCalled()
    expect(windowMock.destroy).toHaveBeenCalledOnce()
  })

  it('emits cancel events to the main window and closes itself', async () => {
    const wrapper = mount(ScreenshotOverlayHost)

    wrapper.getComponent({ name: 'ScreenshotOverlay' }).vm.$emit('cancel')
    await vi.waitFor(() => expect(emit).toHaveBeenCalledWith('screenshot://cancel'))

    expect(windowMock.destroy).toHaveBeenCalledOnce()
  })
})
