import { describe, expect, it, vi } from 'vitest'
import { createFavoriteShortcutCoordinator } from '../useFavoriteShortcuts'

describe('favorite shortcut coordinator', () => {
  it('registers the new shortcut before unregistering the old shortcut', async () => {
    const calls: string[] = []
    const coordinator = createFavoriteShortcutCoordinator({
      register: vi.fn(async shortcut => { calls.push(`register:${shortcut}`) }),
      unregister: vi.fn(async shortcut => { calls.push(`unregister:${shortcut}`) })
    })

    await coordinator.replace('file-1', 'Ctrl+Alt+A', 'Ctrl+Alt+B', vi.fn())

    expect(calls).toEqual([
      'register:CommandOrControl+Alt+B',
      'unregister:CommandOrControl+Alt+A'
    ])
  })

  it('keeps the old registration when registering the new shortcut fails', async () => {
    const unregister = vi.fn()
    const coordinator = createFavoriteShortcutCoordinator({
      register: vi.fn().mockRejectedValue(new Error('occupied')),
      unregister
    })

    await expect(coordinator.replace('file-1', 'Ctrl+Alt+A', 'Ctrl+Alt+B', vi.fn()))
      .rejects.toThrow('occupied')
    expect(unregister).not.toHaveBeenCalled()
  })

  it('retries a persisted shortcut that was not restored at startup', async () => {
    const register = vi.fn().mockResolvedValue(undefined)
    const coordinator = createFavoriteShortcutCoordinator({
      register,
      unregister: vi.fn().mockResolvedValue(undefined)
    })

    await coordinator.replace('file-1', 'Ctrl+Alt+A', 'Ctrl+Alt+A', vi.fn())

    expect(register).toHaveBeenCalledWith('CommandOrControl+Alt+A', expect.any(Function))
  })

  it('rolls back the new shortcut when the old shortcut cannot be unregistered', async () => {
    const unregister = vi.fn()
      .mockRejectedValueOnce(new Error('old unregister failed'))
      .mockResolvedValueOnce(undefined)
    const coordinator = createFavoriteShortcutCoordinator({
      register: vi.fn().mockResolvedValue(undefined),
      unregister
    })

    await expect(coordinator.replace('file-1', 'Ctrl+Alt+A', 'Ctrl+Alt+B', vi.fn()))
      .rejects.toThrow('old unregister failed')
    expect(unregister).toHaveBeenNthCalledWith(2, 'CommandOrControl+Alt+B')
  })

  it('restores all valid shortcuts and isolates individual failures', async () => {
    const register = vi.fn(async (shortcut: string) => {
      if (shortcut.endsWith('+B')) throw new Error('occupied')
    })
    const coordinator = createFavoriteShortcutCoordinator({
      register,
      unregister: vi.fn().mockResolvedValue(undefined)
    })

    const failures = await coordinator.restore([
      { id: '1', shortcut: 'Ctrl+Alt+A' },
      { id: '2', shortcut: 'Ctrl+Alt+B' },
      { id: '3', shortcut: 'Ctrl+Alt+C' }
    ], () => vi.fn(), 2)

    expect(register).toHaveBeenCalledTimes(3)
    expect(failures).toEqual([{ id: '2', shortcut: 'CommandOrControl+Alt+B', error: 'occupied' }])
  })

  it('releases every registered favorite shortcut on dispose', async () => {
    const unregister = vi.fn().mockResolvedValue(undefined)
    const coordinator = createFavoriteShortcutCoordinator({
      register: vi.fn().mockResolvedValue(undefined),
      unregister
    })

    await coordinator.restore([
      { id: '1', shortcut: 'Ctrl+Alt+A' },
      { id: '2', shortcut: 'Ctrl+Alt+B' }
    ], () => vi.fn())
    const failures = await coordinator.dispose()

    expect(unregister.mock.calls).toEqual([
      ['CommandOrControl+Alt+A'],
      ['CommandOrControl+Alt+B']
    ])
    expect(failures).toEqual([])
  })

  it('continues releasing other shortcuts when one dispose call fails', async () => {
    const unregister = vi.fn(async (shortcut: string) => {
      if (shortcut.endsWith('+A')) throw new Error('release failed')
    })
    const coordinator = createFavoriteShortcutCoordinator({
      register: vi.fn().mockResolvedValue(undefined),
      unregister
    })

    await coordinator.restore([
      { id: '1', shortcut: 'Ctrl+Alt+A' },
      { id: '2', shortcut: 'Ctrl+Alt+B' }
    ], () => vi.fn())
    const failures = await coordinator.dispose()

    expect(unregister).toHaveBeenCalledTimes(2)
    expect(failures).toEqual([{ id: '1', shortcut: 'CommandOrControl+Alt+A', error: 'release failed' }])
  })
})
