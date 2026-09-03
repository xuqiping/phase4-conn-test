import { describe, expect, it, vi } from 'vitest'
import { applyCloseBehavior } from '../closeBehavior'

describe('applyCloseBehavior', () => {
  it('shows the floating ball before hiding the main window', async () => {
    const calls: string[] = []
    await applyCloseBehavior('floating_ball', {
      showFloatingBall: vi.fn(async () => { calls.push('ball') }),
      hideMainWindow: vi.fn(async () => { calls.push('main') }),
      exitApplication: vi.fn()
    })

    expect(calls).toEqual(['ball', 'main'])
  })

  it('falls back to the tray when the floating ball cannot be shown', async () => {
    const hideMainWindow = vi.fn()
    const onFloatingBallFailure = vi.fn()
    await applyCloseBehavior('floating_ball', {
      showFloatingBall: vi.fn().mockRejectedValue(new Error('unavailable')),
      hideMainWindow,
      exitApplication: vi.fn(),
      onFloatingBallFailure
    })

    expect(hideMainWindow).toHaveBeenCalledOnce()
    expect(onFloatingBallFailure).toHaveBeenCalledOnce()
  })

  it('hides to tray or exits according to the selected behavior', async () => {
    const hideMainWindow = vi.fn()
    const exitApplication = vi.fn()
    const actions = {
      showFloatingBall: vi.fn(),
      hideMainWindow,
      exitApplication
    }

    await applyCloseBehavior('tray', actions)
    await applyCloseBehavior('exit', actions)

    expect(hideMainWindow).toHaveBeenCalledOnce()
    expect(exitApplication).toHaveBeenCalledOnce()
  })
})
