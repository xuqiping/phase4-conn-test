import { describe, expect, it, vi } from 'vitest'
import {
  BATCH_BACKOFF_MS,
  BATCH_WINDOW,
  backoffDelayMs,
  batchEligibilityOf,
  inducedTopoOrder,
  isConcurrentLimitError,
  runDependencyScheduled,
  runSlidingWindow,
  submitWithBackoff
} from './batchRunner'

/** 受控延迟（依赖调度测试用：awaitTerminal 挂起→测试择机放行）。 */
function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>(r => { resolve = r })
  return { promise, resolve }
}

/** 快进微任务几拍（主循环 tick 注入即时 wait，10ms 实等足够所有派发完成）。 */
const flush = () => new Promise(r => setTimeout(r, 10))

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

/** 2x 四轮 S4：依赖调度（就绪集=选集内上游全 SUCCEEDED；429 重排不占槽；看门狗/取消令牌）。 */
describe('runDependencyScheduled（S4）', () => {
  const neverWatchdog = () => new Promise<'WATCHDOG'>(() => {})
  // tick 必须让出宏任务：微任务级 wait 会让主循环饿死 setTimeout（flush 永不触发，整挂）
  const baseOpts = { wait: async () => { await new Promise(r => setTimeout(r, 0)) }, watchdog: neverWatchdog }

  it('链 A→B→C：下游提交被上游 SUCCEEDED 释放（顺序执行）', async () => {
    const events: string[] = []
    const termA = deferred<'SUCCEEDED' | 'FAILED'>()
    const termB = deferred<'SUCCEEDED' | 'FAILED'>()
    const edges = [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' }
    ]
    const sched = runDependencyScheduled(
      ['a', 'b', 'c'],
      edges,
      {
        submit: async id => { events.push(`submit:${id}`) },
        awaitTerminal: async id => {
          if (id === 'a') return termA.promise
          if (id === 'b') return termB.promise
          events.push('terminal:c')
          return 'SUCCEEDED'
        }
      },
      baseOpts
    )
    await flush()
    // 只 a 被派发；b/c 等上游
    expect(events).toEqual(['submit:a'])
    termA.resolve('SUCCEEDED')
    await flush()
    expect(events).toEqual(['submit:a', 'submit:b'])
    termB.resolve('SUCCEEDED')
    await flush()
    expect(events).toEqual(['submit:a', 'submit:b', 'submit:c', 'terminal:c'])
    const out = await sched
    expect([...out.entries()].sort()).toEqual([
      ['a', 'SUCCEEDED'], ['b', 'SUCCEEDED'], ['c', 'SUCCEEDED']
    ])
  })

  it('独立分支并行（窗口 2）+ 上游失败 → 下游级联 SKIPPED、旁支继续', async () => {
    const events: string[] = []
    const termA = deferred<'SUCCEEDED' | 'FAILED'>()
    const termD = deferred<'SUCCEEDED' | 'FAILED'>()
    const edges = [
      { source: 'a', target: 'b' },
      { source: 'd', target: 'e' }
    ]
    const sched = runDependencyScheduled(
      ['a', 'b', 'd', 'e'],
      edges,
      {
        submit: async id => { events.push(`submit:${id}`) },
        awaitTerminal: async id => (id === 'a' ? termA.promise : id === 'd' ? termD.promise : 'SUCCEEDED')
      },
      baseOpts
    )
    await flush()
    // 窗口 2：a、d 同时占满；各自未终态前 b/e 都不提交
    expect(events.filter(e => e.startsWith('submit:')).sort()).toEqual(['submit:a', 'submit:d'])
    termD.resolve('SUCCEEDED')
    await flush()
    // d 成功释放 e（槽位空出，与 a 无关的分支照常推进）
    expect(events).toContain('submit:e')
    expect(events).not.toContain('submit:b')
    termA.resolve('FAILED')
    const out = await sched
    expect(out.get('a')).toBe('FAILED')
    expect(out.get('b')).toBe('SKIPPED_UPSTREAM_FAILED')
    expect(out.get('d')).toBe('SUCCEEDED')
    expect(out.get('e')).toBe('SUCCEEDED')
    expect(events).not.toContain('submit:b')
  })

  it('上游不在选集（含资格剔除）→ SKIPPED_UPSTREAM_MISSING，不静默跑', async () => {
    const edges = [
      { source: 'x', target: 'b' }, // x 未入选集（如被资格预检剔除）
      { source: 'a', target: 'c' }
    ]
    const out = await runDependencyScheduled(
      ['a', 'b', 'c'],
      edges,
      { submit: async () => {}, awaitTerminal: async () => 'SUCCEEDED' },
      baseOpts
    )
    expect(out.get('b')).toBe('SKIPPED_UPSTREAM_MISSING')
    expect(out.get('a')).toBe('SUCCEEDED')
    expect(out.get('c')).toBe('SUCCEEDED')
  })

  it('菱形 A→(B,C)→D：双上游全成才释放 D', async () => {
    const edges = [
      { source: 'a', target: 'b' },
      { source: 'a', target: 'c' },
      { source: 'b', target: 'd' },
      { source: 'c', target: 'd' }
    ]
    const termB = deferred<'SUCCEEDED' | 'FAILED'>()
    const events: string[] = []
    const sched = runDependencyScheduled(
      ['a', 'b', 'c', 'd'],
      edges,
      {
        submit: async id => { events.push(`submit:${id}`) },
        awaitTerminal: async id => (id === 'b' ? termB.promise : 'SUCCEEDED')
      },
      baseOpts
    )
    await flush()
    // a 完成 → b、c 并行（窗口 2）；b 未终态 d 不提交
    expect(events.filter(e => e.startsWith('submit:'))).toContain('submit:b')
    expect(events.filter(e => e.startsWith('submit:'))).toContain('submit:c')
    expect(events).not.toContain('submit:d')
    termB.resolve('SUCCEEDED')
    const out = await sched
    expect(out.get('d')).toBe('SUCCEEDED')
    expect(events).toContain('submit:d')
  })

  it('选集内部成环 → 抛 dependency-cycle（预检双保险）', async () => {
    await expect(
      runDependencyScheduled(
        ['p', 'q'],
        [
          { source: 'p', target: 'q' },
          { source: 'q', target: 'p' }
        ],
        { submit: async () => {}, awaitTerminal: async () => 'SUCCEEDED' },
        baseOpts
      )
    ).rejects.toThrow('dependency-cycle')
  })

  it('429 → 冷却重排队尾不占槽（窗口 1：旁节点 B 在 A 重试前先跑）', async () => {
    const events: string[] = []
    let aSubmits = 0
    const out = await runDependencyScheduled(
      ['a', 'b'],
      [],
      {
        submit: async id => {
          if (id === 'a') {
            aSubmits++
            if (aSubmits === 1) {
              events.push('submit:a#429')
              const err = { response: { status: 429 } } as never
              throw err
            }
            events.push('submit:a#ok')
            return
          }
          events.push('submit:b')
        },
        awaitTerminal: async () => 'SUCCEEDED'
      },
      baseOpts
    )
    expect(aSubmits).toBe(2)
    // 窗口 1 下 b 在 a 的重试前完成提交（429 冷却期槽位让出，不占窗口槽）
    expect(events.indexOf('submit:b')).toBeGreaterThan(events.indexOf('submit:a#429'))
    expect(events.indexOf('submit:a#ok')).toBeGreaterThan(events.indexOf('submit:b'))
    expect(out.get('a')).toBe('SUCCEEDED')
    expect(out.get('b')).toBe('SUCCEEDED')
  })

  it('重排超上限（3 次）→ 该节点 FAILED 不无限退避', async () => {
    const out = await runDependencyScheduled(
      ['a'],
      [],
      {
        submit: async () => { throw { response: { status: 429 } } },
        awaitTerminal: async () => 'SUCCEEDED'
      },
      baseOpts
    )
    expect(out.get('a')).toBe('FAILED')
  })

  it('看门狗：awaitTerminal 悬挂 → 按 FAILED 释放下游（在途不撤）', async () => {
    const out = await runDependencyScheduled(
      ['a', 'b'],
      [{ source: 'a', target: 'b' }],
      {
        submit: async () => {},
        awaitTerminal: async () => new Promise<'SUCCEEDED' | 'FAILED'>(() => {}) // 永不终态
      },
      { wait: async () => {}, watchdog: () => Promise.resolve('WATCHDOG' as const) }
    )
    expect(out.get('a')).toBe('FAILED')
    expect(out.get('b')).toBe('SKIPPED_UPSTREAM_FAILED')
  })

  it('取消令牌：停派发，未终态标 CANCELLED（在途任务的迟到写入不改快照）', async () => {
    let cancel = false
    const termA = deferred<'SUCCEEDED' | 'FAILED'>()
    const sched = runDependencyScheduled(
      ['a', 'b'],
      [{ source: 'a', target: 'b' }],
      {
        submit: async id => { if (id === 'a') cancel = true }, // a 一提交即取消
        awaitTerminal: async id => (id === 'a' ? termA.promise : 'SUCCEEDED')
      },
      { ...baseOpts, isCancelled: () => cancel }
    )
    const out = await sched
    expect(out.get('a')).toBe('CANCELLED')
    expect(out.get('b')).toBe('CANCELLED')
    expect(out.get('b')).not.toBe('SKIPPED_UPSTREAM_FAILED') // 取消≠失败级联
    termA.resolve('SUCCEEDED') // 在途迟到终态
    await flush()
    expect(out.get('a')).toBe('CANCELLED') // 快照不被迟到写入改写
  })
})
