import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import FloatingBall from '../FloatingBall.vue'

const {
  restoreMainWindow,
  listenFloatingBallMoved,
  showFloatingBallMenu
} = vi.hoisted(() => ({
  restoreMainWindow: vi.fn(),
  listenFloatingBallMoved: vi.fn().mockResolvedValue(() => undefined),
  showFloatingBallMenu: vi.fn()
}))

vi.mock('../../api/floatingBall', () => ({
  restoreMainWindow,
  listenFloatingBallMoved,
  showFloatingBallMenu,
  reportFloatingBallPosition: vi.fn()
}))

describe('FloatingBall', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('restores the main window when activated', async () => {
    const wrapper = mount(FloatingBall)

    await wrapper.get('[data-test="floating-ball-trigger"]').trigger('click')

    expect(restoreMainWindow).toHaveBeenCalledOnce()
  })

  it('opens the native context menu without enlarging the transparent window', async () => {
    const wrapper = mount(FloatingBall)

    await wrapper.get('[data-test="floating-ball-trigger"]').trigger('contextmenu')

    expect(showFloatingBallMenu).toHaveBeenCalledWith({
      open: '打开主窗口',
      tray: '切换为托盘',
      exit: '退出 File Keeper'
    })
  })

  it('uses the native drag region while keeping click activation available', async () => {
    const wrapper = mount(FloatingBall)
    const trigger = wrapper.get('[data-test="floating-ball-trigger"]')

    expect(trigger.attributes()).toHaveProperty('data-tauri-drag-region')
    await trigger.trigger('click')

    expect(restoreMainWindow).toHaveBeenCalledOnce()
  })
})
