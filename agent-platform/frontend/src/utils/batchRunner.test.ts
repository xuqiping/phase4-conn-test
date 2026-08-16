import { describe, expect, it, vi } from 'vitest'
import {
  BATCH_BACKOFF_MS,
  BATCH_WINDOW,
  backoffDelayMs,
  batchEligibilityOf,
  inducedTopoOrder,
  isConcurrentLimitError,
  runSlidingWindow,
  submitWithBackoff
} from './batchRunner'

describe('3x-C4 批量生成纯函数', () => {
  describe('backoffDelayMs', () => {
    it('指数退避 1s/2s/4s，越界取最后一档', () => {
      expect(BATCH_BACKOFF_MS).toEqual([1000, 2000, 4000])
      expect(backoffDelayMs(0)).toBe(1000)
      expect(backoffDelayMs(1)).toBe(2000)
      expect(backoffDelayMs(2)).toBe(4000)
      expect(backoffDelayMs(9)).toBe(4000)
      expect(backoffDelayMs(-1)).toBe(1000)
    })
    it('窗口宽度默认 2', () => {
      expect(BATCH_WINDOW).toBe(2)
    })
  })

  describe('isConcurrentLimitError', () => {
    it('识别 HTTP 429 / 业务码 42904 两种形态', () => {
      expect(isConcurrentLimitError({ response: { status: 429 } })).toBe(true)
      expect(isConcurrentLimitError({ response: { status: 500, data: { code: 42904 } } })).toBe(true)
      expect(isConcurrentLimitError({ code: 42904 })).toBe(true)
      expect(isConcurrentLimitError(new Error('视频提交失败'))).toBe(false)
      expect(isConcurrentLimitError({ response: { status: 403 } })).toBe(false)
      expect(isConcurrentLimitError(null)).toBe(false)
      expect(isConcurrentLimitError(undefined)).toBe(false)
    })
  })

  describe('batchEligibilityOf', () => {
    it('text/video 缺提示词 → 跳过原因', () => {
      expect(batchEligibilityOf({ type: 'text', data: { prompt: '  ' } })).toEqual({ ok: false, reason: '缺少提示词' })
      expect(batchEligibilityOf({ type: 'video', data: {} })).toEqual({ ok: false, reason: '缺少提示词' })
      expect(batchEligibilityOf({ type: 'text', data: { prompt: '写分镜' } })).toEqual({ ok: true })
    })
    it('image 需 model+prompt 双齐', () => {
      expect(batchEligibilityOf({ type: 'image', data: { prompt: '猫' } })).toEqual({ ok: false, reason: '缺少图片模型' })
      expect(batchEligibilityOf({ type: 'image', data: { prompt: '猫', model: 'seedream-4' } })).toEqual({ ok: true })
    })
    it('script/storyboard/audio 等类型不参与批量', () => {
      expect(batchEligibilityOf({ type: 'script', data: { prompt: 'x' } }).ok).toBe(false)
      expect(batchEligibilityOf({ type: 'storyboard', data: {} }).reason).toBe('该类型不支持批量生成')
      expect(batchEligibilityOf({ type: 'audio', data: {} }).ok).toBe(false)
    })
  })

  describe('inducedTopoOrder', () => {
    const edges = [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' },
      { source: 'x', target: 'b' } // x 未选中：诱导子图须忽略
    ]
    it('链 a→b→c 全选 → 拓扑序 a 先 c 后；未选中上游边被忽略', () => {
      const { order, cycle } = inducedTopoOrder(['c', 'b', 'a'], edges)
      expect(cycle).toBe(false)
      expect(order.indexOf('a')).toBeLessThan(order.indexOf('b'))
      expect(order.indexOf('b')).toBeLessThan(order.indexOf('c'))
    })
    it('只选 b、c 时 x→b 不入度（x 未选不补跑）', () => {
      const { order, cycle } = inducedTopoOrder(['c', 'b'], edges)
      expect(cycle).toBe(false)
      expect(order).toEqual(['b', 'c'])
    })
    it('选集内部成环 → cycle=true', () => {
      const ring = [
        { source: 'p', target: 'q' },
        { source: 'q', target: 'p' }
      ]
      const { cycle, order } = inducedTopoOrder(['p', 'q'], ring)
      expect(cycle).toBe(true)
      expect(order.length).toBeLessThan(2)
    })
    it('无选集 → 空序不环', () => {
      expect(inducedTopoOrder([], edges)).toEqual({ order: [], cycle: false })
    })
  })

  describe('runSlidingWindow', () => {
    it('5 项 limit=2：全完成且最大并发 ≤2', async () => {
      let running = 0
      let maxRunning = 0
      const done: number[] = []
      await runSlidingWindow([0, 1, 2, 3, 4], 2, async (item) => {
        running++
        maxRunning = Math.max(maxRunning, running)
        await new Promise(r => setTimeout(r, 5))
        done.push(item)
        running--
      })
      expect(done.sort()).toEqual([0, 1, 2, 3, 4])
      expect(maxRunning).toBeLessThanOrEqual(2)
    })
    it('worker 抛错 → 整体 reject', async () => {
      await expect(runSlidingWindow([1], 2, async () => {
        throw new Error('boom')
      })).rejects.toThrow('boom')
    })
    it('空列表 → 直接完成', async () => {
      const worker = vi.fn()
      await runSlidingWindow([], 2, worker)
      expect(worker).not.toHaveBeenCalled()
    })
  })

  describe('submitWithBackoff', () => {
    // 真实退避 1+2+4s 会超测试超时 → 假定时器一次性推进 7s（advanceTimersByTimeAsync 会冲刷微任务）
    it('429 连败 3 次后退避重试成功（不抛）', async () => {
      vi.useFakeTimers()
      try {
        const limitErr = { response: { status: 429 } }
        const submit = vi.fn()
          .mockRejectedValueOnce(limitErr)
          .mockRejectedValueOnce(limitErr)
          .mockRejectedValueOnce(limitErr)
          .mockResolvedValueOnce(42)
        const promise = submitWithBackoff(submit)
        await vi.advanceTimersByTimeAsync(7000)
        await expect(promise).resolves.toBe(42)
        expect(submit).toHaveBeenCalledTimes(4)
      } finally {
        vi.useRealTimers()
      }
    })
    it('退避 3 次仍 429 → 上抛（节点标 failed）', async () => {
      vi.useFakeTimers()
      try {
        const submit = vi.fn().mockRejectedValue({ response: { status: 429 } })
        const promise = submitWithBackoff(submit)
        promise.catch(() => { /* 立即挂 handler：fake-timer 时序下防 unhandled 告警 */ })
        await vi.advanceTimersByTimeAsync(7000)
        await expect(promise).rejects.toMatchObject({ response: { status: 429 } })
        expect(submit).toHaveBeenCalledTimes(4) // 首次 + 3 次重试
      } finally {
        vi.useRealTimers()
      }
    })
    it('非 429 错误不重试立即上抛', async () => {
      const submit = vi.fn().mockRejectedValue(new Error('模型不可用'))
      await expect(submitWithBackoff(submit)).rejects.toThrow('模型不可用')
      expect(submit).toHaveBeenCalledTimes(1)
    })
  })
})
