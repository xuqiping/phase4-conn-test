import type { CloseBehavior } from '../types/settings'

export interface CloseBehaviorActions {
  showFloatingBall: () => Promise<unknown>
  hideMainWindow: () => Promise<unknown>
  exitApplication: () => Promise<unknown>
  onFloatingBallFailure?: (error: unknown) => void
}

export async function applyCloseBehavior(
  behavior: CloseBehavior,
  actions: CloseBehaviorActions
): Promise<void> {
  if (behavior === 'exit') {
    await actions.exitApplication()
    return
  }

  if (behavior === 'tray') {
    await actions.hideMainWindow()
    return
  }

  try {
    await actions.showFloatingBall()
  } catch (error) {
    actions.onFloatingBallFailure?.(error)
  }
  await actions.hideMainWindow()
}
