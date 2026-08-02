import { describe, expect, it, vi } from 'vitest'
import { register } from '@tauri-apps/plugin-global-shortcut'
import { registerGlobalShortcut } from '../shortcuts'

vi.mock('@tauri-apps/plugin-global-shortcut', () => ({
  register: vi.fn().mockResolvedValue(undefined),
  unregister: vi.fn().mockResolvedValue(undefined)
}))

describe('shortcut api', () => {
  it('runs shortcut handlers only for key press events', async () => {
    const handler = vi.fn()

    await registerGlobalShortcut('CommandOrControl+Shift+X', handler)
    const registeredHandler = vi.mocked(register).mock.calls[0][1]

    registeredHandler({ shortcut: 'CommandOrControl+Shift+X', id: 1, state: 'Released' })
    registeredHandler({ shortcut: 'CommandOrControl+Shift+X', id: 1, state: 'Pressed' })

    expect(handler).toHaveBeenCalledOnce()
  })
})
