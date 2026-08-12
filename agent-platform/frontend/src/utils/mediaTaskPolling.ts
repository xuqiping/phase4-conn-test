import { isTerminal, type MediaStatus } from '@/api/media'

interface PollOptions<T> {
  intervalMs?: number
  wait?: (ms: number) => Promise<void>
  onPending?: (task: T) => void
}

const defaultWait = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

/**
 * 可取消的持续轮询：没有轮数/业务超时上限；网络异常继续，只有后端终态或调用方取消才结束。
 */
export async function pollMediaTask<T extends { status: MediaStatus }>(
  getTask: () => Promise<T>,
  isCancelled: () => boolean,
  options: PollOptions<T> = {}
): Promise<T | null> {
  const wait = options.wait ?? defaultWait
  const intervalMs = options.intervalMs ?? 5000
  while (!isCancelled()) {
    await wait(intervalMs)
    if (isCancelled()) return null
    let task: T
    try {
      task = await getTask()
    } catch {
      continue
    }
    if (isTerminal(task.status)) return task
    options.onPending?.(task)
  }
  return null
}
