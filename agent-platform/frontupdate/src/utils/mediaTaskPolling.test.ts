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

  it('2x 四轮 Step1：连续失败退避 5→10→30s 封顶，成功归 5s', async () => {
    const getTask = vi.fn()
      .mockRejectedValueOnce(new Error('e1'))
      .mockRejectedValueOnce(new Error('e2'))
      .mockRejectedValueOnce(new Error('e3'))
      .mockRejectedValueOnce(new Error('e4'))
      .mockResolvedValueOnce({ status: 'RUNNING' })
      .mockResolvedValueOnce({ status: 'SUCCEEDED', id: 9 })
    const waits: number[] = []

    const result = await pollMediaTask(getTask, () => false, {
      intervalMs: 5000,
      wait: async ms => { waits.push(ms) }
    })

    // 失败 4 次：等待 5000(streak0) →10000→30000→30000→30000(第4败后仍封顶)；成功归零 → 5000；终态返回
    expect(waits).toEqual([5000, 10000, 30000, 30000, 30000, 5000])
    expect(result).toMatchObject({ status: 'SUCCEEDED', id: 9 })
  })

  it('2x 四轮 Step1：标签页回 visible 打断等待立即补轮', async () => {
    const getTask = vi.fn().mockResolvedValue({ status: 'SUCCEEDED', id: 2 })
    let releaseWait: (() => void) | null = null
    const wait = () => new Promise<void>(r => { releaseWait = r })

    const polling = pollMediaTask(getTask, () => false, { intervalMs: 5000, wait })
    // 首个等待挂起中触发 visibilitychange → 唤醒，不等满 5s 即轮询
    await Promise.resolve()
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))

    const result = await polling
    expect(getTask).toHaveBeenCalledTimes(1)
    expect(result).toMatchObject({ status: 'SUCCEEDED', id: 2 })
    ;(releaseWait as (() => void) | null)?.() // 未被打断的等待清理（已打断则 resolve 无害）
  })
})
