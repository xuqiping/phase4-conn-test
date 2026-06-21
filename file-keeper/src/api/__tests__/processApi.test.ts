import { beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { killProcess, killProcesses } from '../process'

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn()
}))

const mockedInvoke = vi.mocked(invoke)

describe('process api', () => {
  beforeEach(() => {
    mockedInvoke.mockReset()
  })

  it('kills a process by pid', async () => {
    mockedInvoke.mockResolvedValueOnce(undefined)

    await killProcess(4242)

    expect(mockedInvoke).toHaveBeenCalledWith('kill_app_process', { pid: 4242 })
  })

  it('kills multiple processes by pid', async () => {
    mockedInvoke.mockResolvedValueOnce({ succeeded: 2, failed: 1 })

    const result = await killProcesses([4242, 5252, 6262])

    expect(mockedInvoke).toHaveBeenCalledWith('kill_app_processes', { pids: [4242, 5252, 6262] })
    expect(result).toEqual({ succeeded: 2, failed: 1 })
  })
})
