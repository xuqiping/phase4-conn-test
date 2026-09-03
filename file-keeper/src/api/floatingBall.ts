import { emit, type UnlistenFn } from '@tauri-apps/api/event'
import { invoke } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import type { FloatingBallPosition } from '../types/settings'

export async function showFloatingBall(position?: FloatingBallPosition): Promise<FloatingBallPosition> {
  return invoke<FloatingBallPosition>('show_floating_ball', {
    x: position?.x,
    y: position?.y
  })
}

export async function hideFloatingBall(): Promise<void> {
  await invoke('hide_floating_ball')
}

export async function restoreMainWindow(): Promise<void> {
  await invoke('restore_main_window')
}

export async function exitApplication(): Promise<void> {
  await invoke('exit_application')
}

export async function showFloatingBallMenu(labels: {
  open: string
  tray: string
  exit: string
}): Promise<void> {
  await invoke('show_floating_ball_menu', labels)
}

export async function reportFloatingBallPosition(position: FloatingBallPosition): Promise<void> {
  await emit('floating-ball://moved', position)
}

export async function listenFloatingBallMoved(
  handler: (position: FloatingBallPosition) => void
): Promise<UnlistenFn> {
  return getCurrentWindow().onMoved(({ payload }) => handler({ x: payload.x, y: payload.y }))
}
