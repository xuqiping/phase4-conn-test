import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useProcessStore } from '../processStore'
import * as processApi from '../../api/process'
import type { ProcessInfo } from '../../types/process'

vi.mock('../../api/process', () => ({
  getRunningProcesses: vi.fn(),
  closeProcess: vi.fn(),
  closeProcesses: vi.fn(),
  killProcess: vi.fn(),
  killProcesses: vi.fn()
}))

function createProcess(overrides: Partial<ProcessInfo> = {}): ProcessInfo {
  return {
    pid: 4242,
    name: 'File Keeper',
    window_title: 'Main Window',
    category: 'Other',
    memory_mb: 128,
    cpu_usage: 1.5,
    window_handle: 1001,
    ...overrides
  }
}

describe('processStore kill actions', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('kills one pid and removes every window owned by that process', async () => {
    vi.mocked(processApi.killProcess).mockResolvedValueOnce(undefined)
    const store = useProcessStore()
    store.processes = [
      createProcess({ pid: 4242, window_handle: 1001, window_title: 'Main Window' }),
      createProcess({ pid: 4242, window_handle: 1002, window_title: 'Settings Window' }),
      createProcess({ pid: 5252, window_handle: 2001, name: 'Explorer' })
    ]
    store.selectedIds = new Set([1001, 1002, 2001])

    const success = await store.killProcess(4242)

    expect(success).toBe(true)
    expect(processApi.killProcess).toHaveBeenCalledWith(4242)
    expect(store.processes.map(process => process.pid)).toEqual([5252])
    expect(Array.from(store.selectedIds)).toEqual([2001])
  })

  it('kills selected processes by unique pid and clears selected rows after success', async () => {
    vi.mocked(processApi.killProcesses).mockResolvedValueOnce({ succeeded: 2, failed: 0 })
    const store = useProcessStore()
    store.processes = [
      createProcess({ pid: 4242, window_handle: 1001, window_title: 'Main Window' }),
      createProcess({ pid: 4242, window_handle: 1002, window_title: 'Settings Window' }),
      createProcess({ pid: 5252, window_handle: 2001, name: 'Explorer' }),
      createProcess({ pid: 6262, window_handle: 3001, name: 'Terminal' })
    ]
    store.selectedIds = new Set([1001, 1002, 2001])

    const result = await store.killSelected()

    expect(result).toEqual({ success: 2, failed: 0 })
    expect(processApi.killProcesses).toHaveBeenCalledWith([4242, 5252])
    expect(store.processes.map(process => process.pid)).toEqual([6262])
    expect(store.selectedIds.size).toBe(0)
  })
})
