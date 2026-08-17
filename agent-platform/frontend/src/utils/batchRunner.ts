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

// ---- 2x 四轮 S4：依赖调度（上游 SUCCEEDED 释放下游；spec §5） ----

/** 依赖调度节点终态。SUCCEEDED/FAILED=任务终态；两个 SKIPPED=未起跑（上游失败/上游未入选集）。 */
export type DepOutcome =
  | 'SUCCEEDED'
  | 'FAILED'
  | 'SKIPPED_UPSTREAM_FAILED'
  | 'SKIPPED_UPSTREAM_MISSING'
  | 'CANCELLED'

/** 调度回调两段式：submit 走窗口槽（429 可重排不占槽）；awaitTerminal 不占槽（纯等待/轮询）。 */
export interface DepSchedPhases {
  /** 提交任务（taskId 即时持久化由调用方负责）。抛并发上限错误 → 调度器冷却后重排（不占槽）。 */
  submit: (id: string) => Promise<void>
  /** 等到该节点终态。返回值按任务终态映射（null/异常 → 调度器按 FAILED 释放下游）。 */
  awaitTerminal: (id: string) => Promise<'SUCCEEDED' | 'FAILED'>
}

export interface DepSchedOptions {
  /** 并发窗口（成功提交→终态占 1 槽）。默认 BATCH_WINDOW。 */
  window?: number
  /** 单节点看门狗：submit 起等终态的超时（超时按 FAILED 释放下游，在途任务不撤）。默认 60 分钟。 */
  watchdogMs?: number
  /** 取消令牌：true 时停止派发新任务，未启动的标 CANCELLED；在途任务不撤（仅停释放）。 */
  isCancelled?: () => boolean
  /** 单节点 429 重排上限（沿用退避表重试次数口径），超过按 FAILED 落终态。默认 3。 */
  maxRequeues?: number
  /** 进度回调：waiting=等上游；submitting/running=占槽中；终态=outcome（含跳过/取消）。 */
  onNodeState?: (id: string, state: 'waiting' | 'submitting' | 'running' | DepOutcome) => void
  /** 测试注入：主循环/冷却等待函数（默认 setTimeout）。 */
  wait?: (ms: number) => Promise<void>
  /** 测试注入：看门狗 Promise（默认 setTimeout(watchdogMs) 后 resolve 'WATCHDOG'）。 */
  watchdog?: (ms: number) => Promise<'WATCHDOG'>
}

/** 主循环空转检查间隔（默认 500ms；单测注入 wait 后无实义）。 */
const DEP_SCHED_TICK_MS = 500
/** 节点看门狗默认 60 分钟（spec §5：上游永不终态 → 下游悬挂的兜底）。 */
export const DEP_WATCHDOG_MS = 60 * 60 * 1000

/**
 * 依赖调度批量生成（spec §5，替换 onBatchRun 的裸滑窗）：
 * - 就绪=选集内全部直接上游 SUCCEEDED；上游不在选集（含资格剔除）→ SKIPPED_UPSTREAM_MISSING
 *   （不静默丢，节点标灰+原因）；
 * - 上游 FAILED/被跳过 → 级联 SKIPPED_UPSTREAM_FAILED，无关分支继续；
 * - 滑窗 window 并发（互不依赖分支并行）；submit 撞 429 → 冷却后重排队尾（冷却期不占槽，坑点表）；
 * - 看门狗 watchdogMs/节点：等终态超时按 FAILED 释放下游（在途任务不撤）；
 * - isCancelled：停派发，未启动标 CANCELLED，在途不撤。
 * 返回全量节点终态表（Map）。
 */
export async function runDependencyScheduled(
  selectedIds: readonly string[],
  edges: readonly { source: string; target: string }[],
  phases: DepSchedPhases,
  opts: DepSchedOptions = {}
): Promise<Map<string, DepOutcome>> {
  const windowSize = opts.window ?? BATCH_WINDOW
  const watchdogMs = opts.watchdogMs ?? DEP_WATCHDOG_MS
  const maxRequeues = opts.maxRequeues ?? BATCH_BACKOFF_MS.length
  const wait = opts.wait ?? ((ms: number) => new Promise<void>(r => setTimeout(r, ms)))
  const isCancelled = opts.isCancelled ?? (() => false)
  const onState = opts.onNodeState
  // 看门狗：默认真 setTimeout（race 输了由 stopWatchdog 清理）；测试注入可控时序
  let stopWatchdog: () => void = () => {}
  const watchdogOf =
    opts.watchdog
    ?? ((ms: number) =>
      new Promise<'WATCHDOG'>(resolve => {
        const t = setTimeout(() => resolve('WATCHDOG'), ms)
        stopWatchdog = () => clearTimeout(t)
      }))

  const inSet = new Set(selectedIds)
  // 选集内直接上游表（key=节点, value=上游 id 列表）；跨选集入边单独记 MISSING
  const rawPreds = new Map<string, string[]>()
  const missing = new Set<string>()
  for (const e of edges) {
    if (!inSet.has(e.target)) continue
    if (!inSet.has(e.source)) {
      missing.add(e.target) // 上游不在选集：目标节点判 MISSING（spec §5.2）
      continue
    }
    const list = rawPreds.get(e.target) ?? []
    list.push(e.source)
    rawPreds.set(e.target, list)
  }
  const upstreamOf = (id: string) => rawPreds.get(id) ?? []
  // 环守卫（复用诱导子图拓扑序）：有环直接抛，调用方已预检，双保险
  if (inducedTopoOrder(selectedIds, edges).cycle) {
    throw new Error('dependency-cycle')
  }

  const outcome = new Map<string, DepOutcome>()
  const ready: string[] = []
  const requeues = new Map<string, number>()
  let inFlight = 0
  // 429 冷却中的节点数：主循环退出条件须计入——冷却节点尚未回队，
  // 此时 inFlight=0 && ready 空 ≠ 全部终态（曾竞态：冷却期主循环误判收工把重排节点丢掉）
  let cooling = 0

  /** 记终态（含跳过/取消）并级联核对下游：全部上游 SUCCEEDED → 入就绪队；否则级联 SKIPPED。 */
  const settle = (id: string, st: DepOutcome) => {
    if (outcome.has(id)) return
    outcome.set(id, st)
    onState?.(id, st)
    for (const nid of inSet) {
      if (outcome.has(nid) || !upstreamOf(nid).includes(id)) continue
      releaseIfResolved(nid)
    }
  }
  function releaseIfResolved(id: string) {
    if (outcome.has(id)) return
    const preds = upstreamOf(id)
    if (preds.some(p => !outcome.has(p))) return // 还有上游未终态
    if (preds.every(p => outcome.get(p) === 'SUCCEEDED')) {
      ready.push(id)
    } else if (preds.some(p => outcome.get(p) === 'CANCELLED')) {
      return // 上游被取消（用户停调度）：本节点留给收尾统一标 CANCELLED，不算失败级联
    } else {
      // 上游存在 FAILED/SKIPPED → 级联跳过（spec：标灰，其余分支继续）
      settle(id, 'SKIPPED_UPSTREAM_FAILED')
    }
  }

  // 初始判定：跨选集上游 → MISSING；无上游 → 就绪；其余 waiting 等释放
  for (const id of inSet) {
    onState?.(id, 'waiting')
    if (missing.has(id)) {
      settle(id, 'SKIPPED_UPSTREAM_MISSING')
      continue
    }
    if (upstreamOf(id).length === 0) ready.push(id)
  }

  async function launch(id: string) {
    onState?.(id, 'submitting')
    try {
      await phases.submit(id)
    } catch (e) {
      const n = (requeues.get(id) ?? 0) + 1
      requeues.set(id, n)
      if (isConcurrentLimitError(e) && n <= maxRequeues) {
        // 429：槽位已让出（inFlight 在 launch 外层减），冷却后重排队尾——重试期间不占窗口槽
        inFlight--
        cooling++
        try {
          await wait(backoffDelayMs(n - 1))
        } finally {
          cooling--
        }
        if (isCancelled()) {
          settle(id, 'CANCELLED')
          return
        }
        ready.push(id)
        onState?.(id, 'waiting')
        return
      }
      inFlight--
      settle(id, 'FAILED')
      return
    }
    onState?.(id, 'running')
    // 终态等待：看门狗超时按 FAILED 释放（在途轮询不撤，超时后迟到的终态写入无害）
    const terminal = await Promise.race([
      phases.awaitTerminal(id).catch(() => 'FAILED' as const),
      watchdogOf(watchdogMs)
    ])
    stopWatchdog()
    inFlight--
    settle(id, terminal === 'WATCHDOG' ? 'FAILED' : terminal)
  }

  // 主循环：派发就绪节点至窗口满；全终态（含冷却中）或取消退出
  for (;;) {
    if (isCancelled()) break
    while (ready.length > 0 && inFlight < windowSize && !isCancelled()) {
      const id = ready.shift()!
      inFlight++
      void launch(id)
    }
    if (inFlight === 0 && ready.length === 0 && cooling === 0) break // 全部终态（或初始全跳过）
    await wait(DEP_SCHED_TICK_MS)
  }
  // 取消退出：未终态的一律 CANCELLED（在途任务的迟到终态不写入本表）
  if (isCancelled()) {
    for (const id of inSet) if (!outcome.has(id)) settle(id, 'CANCELLED')
  }
  // 返回快照：在途任务迟到的 settle 只落内部表，不改已交出的结果
  return new Map(outcome)
}
