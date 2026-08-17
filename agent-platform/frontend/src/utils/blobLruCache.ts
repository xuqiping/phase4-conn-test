/**
 * 会话内 Blob LRU 缓存工厂（6x#2）。逻辑抽自 media.ts 手写 LRU（mediaBlobCache），
 * 供 /api/files 预览池（file.ts）复用；media.ts 暂保持原实现（后续收敛机会，勿重复造）。
 *
 * 规则：键→Blob；命中=LRU 触碰（重插末尾）；满条数或满字节从最旧逐出；
 * 单条超过字节上限不缓存（防一条巨物清空整池）。
 * 注意：本缓存只存 Blob；objectURL 由调用方自建自毁（URL.revokeObjectURL），
 * 缓存命中方各拿各的 URL，互不影响。
 */
export interface BlobLruCache {
  /** 命中返回 Blob 并 LRU 触碰；未命中返回 null */
  get(key: string): Blob | null
  /** 写入（重复键覆盖且字节记账修正；超单条上限忽略） */
  put(key: string, blob: Blob): void
  clear(): void
  /** 当前缓存总字节数 */
  readonly bytes: number
  /** 当前条目数 */
  readonly size: number
}

export function createBlobLruCache(maxEntries: number, maxBytes: number): BlobLruCache {
  const map = new Map<string, Blob>() // Map 迭代序=插入序，最旧在头
  let totalBytes = 0
  return {
    get(key) {
      const hit = map.get(key)
      if (!hit) return null
      map.delete(key)
      map.set(key, hit)
      return hit
    },
    put(key, blob) {
      if (blob.size > maxBytes) return
      if (map.has(key)) {
        totalBytes -= map.get(key)!.size
        map.delete(key)
      }
      while (map.size >= maxEntries || (totalBytes + blob.size > maxBytes && map.size > 0)) {
        const oldest = map.keys().next().value
        if (oldest === undefined) break
        totalBytes -= map.get(oldest)!.size
        map.delete(oldest)
      }
      map.set(key, blob)
      totalBytes += blob.size
    },
    clear() {
      map.clear()
      totalBytes = 0
    },
    get bytes() {
      return totalBytes
    },
    get size() {
      return map.size
    }
  }
}
