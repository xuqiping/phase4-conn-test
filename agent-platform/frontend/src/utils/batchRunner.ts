/**
 * 3x-C4 批量生成纯函数集（窗口调度/退避/资格判定/诱导子图拓扑序）。
 * 与 CanvasView 解耦便于单测；onBatchRun 只做画布读写与消息。
 */

/** 批量滑动窗口宽度（同时提交 2 个；后端另有每用户并发闸 video=2/image=3 兜底）。 */
export const BATCH_WINDOW = 2

/** 429 并发上限指数退避表（1s/2s/4s，上限 3 次重试）。 */
export const BATCH_BACKOFF_MS = [1000, 2000, 4000] as const

/** 退避取值：第 attempt 次重试（0 起）的等待毫秒；越界取最后一档。 */
export function backoffDelayMs(attempt: number): number {
  const i = Math.min(Math.max(attempt, 0), BATCH_BACKOFF_MS.length - 1)
  return BATCH_BACKOFF_MS[i]
}

/**
 * 是否「并发上限」类错误（后端 MEDIA_CONCURRENT_LIMIT=42904 → HTTP 429）。
 * 兼容两种形态：axios error（response.status/response.data.code）与业务 Error。
 */
export function isConcurrentLimitError(e: unknown): boolean {
  const err = e as { response?: { status?: number; data?: { code?: number } }; code?: number } | null
  return err?.response?.status === 429
    || err?.response?.data?.code === 42904
    || err?.code === 42904
}

/** 节点批量资格（类型可跑 + 必填字段齐）。reason 供「跳过原因」列表展示。 */
export interface BatchEligibility {
  ok: boolean
  reason?: string
}

/** 批量可跑类型：text 走画布 runner；image/video 走 media 提交（script 用拆分镜、storyboard/audio 上传型，均不参与）。 */
export function batchEligibilityOf(node: { type: string; data?: Record<string, unknown> | null }): BatchEligibility {
  const data = node.data ?? {}
  const prompt = typeof data.prompt === 'string' ? data.prompt.trim() : ''
  switch (node.type) {
    case 'text':
      return prompt ? { ok: true } : { ok: false, reason: '缺少提示词' }
    case 'video':
      return prompt ? { ok: true } : { ok: false, reason: '缺少提示词' }
    case 'image':
      if (!prompt) return { ok: false, reason: '缺少提示词' }
      if (!data.model) return { ok: false, reason: '缺少图片模型' }
      return { ok: true }
    default:
      return { ok: false, reason: '该类型不支持批量生成' }
  }
}

/**
 * 选中节点诱导子图拓扑序（Kahn）：只统计两端均在选集内的边——
 * 未选中的上游不参与排序，其产出按【当前已物化内容】插值（不自动补跑）。
 * order.length < 选集数 ⇒ 选集内部存在环。
 */
export function inducedTopoOrder(
  selectedIds: readonly string[],
  edges: readonly { source: string; target: string }[]
): { order: string[]; cycle: boolean } {
  const inSet = new Set(selectedIds)
  const inDegree = new Map<string, number>()
  for (const id of inSet) inDegree.set(id, 0)
  const adj = new Map<string, string[]>([...inSet].map(id => [id, []] as const))
  for (const e of edges) {
    if (!inSet.has(e.source) || !inSet.has(e.target)) continue
    adj.get(e.source)!.push(e.target)
    inDegree.set(e.target, (inDegree.get(e.target) ?? 0) + 1)
  }
  const queue: string[] = []
  for (const [id, deg] of inDegree) if (deg === 0) queue.push(id)
  const order: string[] = []
  while (queue.length) {
    const cur = queue.shift()!
    order.push(cur)
    for (const nb of adj.get(cur) ?? []) {
      const nd = (inDegree.get(nb) ?? 1) - 1
      inDegree.set(nb, nd)
      if (nd === 0) queue.push(nb)
    }
  }
  return { order, cycle: order.length < inSet.size }
}

/**
 * 滑动窗口并发执行（泳道池）：起 min(limit, items.length) 条泳道循环取下一个，
 * 始终 ≤limit 个 worker 在飞；任一 worker 抛错 → 整体 reject（调用方决定忽略粒度）。
 */
export async function runSlidingWindow<T>(
  items: readonly T[],
  limit: number,
  worker: (item: T, index: number) => Promise<void>
): Promise<void> {
  let next = 0
  const lanes = Array.from(
    { length: Math.max(1, Math.min(limit, items.length)) },
    async () => {
      for (;;) {
        const i = next++
        if (i >= items.length) return
        await worker(items[i], i)
      }
    }
  )
  await Promise.all(lanes)
}

/** 提交 + 429 指数退避重试（最多 BATCH_BACKOFF_MS.length 次）；其他错误立即上抛。 */
export async function submitWithBackoff<T>(submit: () => Promise<T>): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try {
      return await submit()
    } catch (e) {
      if (attempt < BATCH_BACKOFF_MS.length && isConcurrentLimitError(e)) {
        await new Promise(resolve => setTimeout(resolve, backoffDelayMs(attempt)))
        continue
      }
      throw e
    }
  }
}
