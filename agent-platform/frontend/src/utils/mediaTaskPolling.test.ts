import { describe, expect, it, vi } from 'vitest'
import { pollMediaTask } from './mediaTaskPolling'

describe('pollMediaTask', () => {
  it('AC-V3-05 无轮数上限，网络异常后继续到终态', async () => {
    const getTask = vi.fn()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ status: 'RUNNING' })
      .mockResolvedValueOnce({ status: 'SUCCEEDED', id: 1 })

    const result = await pollMediaTask(getTask, () => false, {
      intervalMs: 0,
      wait: async () => undefined
    })

    expect(getTask).toHaveBeenCalledTimes(3)
    expect(result).toMatchObject({ status: 'SUCCEEDED', id: 1 })
  })

  it('节点被删除或任务替换时取消本地轮询', async () => {
    const getTask = vi.fn()
    const result = await pollMediaTask(getTask, () => true, { wait: async () => undefined })
    expect(result).toBeNull()
    expect(getTask).not.toHaveBeenCalled()
  })
})
