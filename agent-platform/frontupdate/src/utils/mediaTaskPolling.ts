import { isTerminal, type MediaStatus } from '@/api/media'

interface PollOptions<T> {
  intervalMs?: number
  wait?: (ms: number) => Promise<void>
  onPending?: (task: T) => void
}

const defaultWait = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

/**
 * 失败退避间隔（2x 四轮 Step1）：连续失败第 1/2/≥3 次后的等待 = 基数 / 基数×2 / 30s 封顶。
 * 默认基数 5s → 5→10→30→30…；任一成功归零回基数。断网不锤接口，恢复自动续跑。
 */
function backoffDelay(intervalMs: number, failStreak: number): number {
  if (failStreak <= 0) return intervalMs
  if (failStreak === 1) return intervalMs * 2
  return Math.max(30000, intervalMs)
}

/**
 * 可取消的持续轮询：没有轮数/业务超时上限；网络异常退避续跑（5→10→30s 封顶），
 * 只有后端终态或调用方取消才结束。
 * 2x 四轮 Step1：标签页 visibilitychange 回到 visible 时打断当前等待立即补一轮
 * （切走的标签页被浏览器节流，回来先补一次再等下一拍，状态不至于「过期一个周期」）。
 */
export async function pollMediaTask<T extends { status: MediaStatus }>(
  getTask: () => Promise<T>,
  isCancelled: () => boolean,
  options: PollOptions<T> = {}
): Promise<T | null> {
  const wait = options.wait ?? defaultWait
  const intervalMs = options.intervalMs ?? 5000
  let failStreak = 0

  // visibility 唤醒：注册回调存引用，事件触发时 resolve 当前等待（立即进入下一轮轮询）
  let wake: (() => void) | null = null
  const onVisibility = () => {
    if (typeof document !== 'undefined' && document.visibilityState === 'visible') wake?.()
  }
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', onVisibility)
  }
  /** 等待 ms，期间被 visibility 唤醒则提前结束（返 true=被打断）。 */
  const waitInterruptible = (ms: number) => new Promise<boolean>(resolve => {
    let settled = false
    const finish = (interrupted: boolean) => {
      if (settled) return
      settled = true
      wake = null
      resolve(interrupted)
    }
    void wait(ms).then(() => finish(false))
    wake = () => finish(true)
  })

  try {
    while (!isCancelled()) {
      await waitInterruptible(backoffDelay(intervalMs, failStreak))
      if (isCancelled()) return null
      let task: T
      try {
        task = await getTask()
        failStreak = 0
      } catch {
        failStreak++
        continue
      }
      if (isTerminal(task.status)) return task
      options.onPending?.(task)
    }
    return null
  } finally {
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }
}
