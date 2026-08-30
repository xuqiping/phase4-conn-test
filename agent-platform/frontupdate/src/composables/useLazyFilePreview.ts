// ============================================================
// useLazyFilePreview · 文件预览 objectURL 懒加载组合式（C2）
// 资产卡片缩略图 / 画布节点预览共用。设计目标（plan §C2 验证项）：
//   ① 视口外不拉（IntersectionObserver 门控首次拉取）
//   ② 同 fileId 不重复拉（模块级缓存 + in-flight promise 去重）
//   ③ objectURL 受限释放（refcount + 30s idle 回收 + LRU 上限 80，防 blob 配额泄漏）
// 复用 /api/files/{fileId}（FileStorageService.load 归属咽喉点，已校验防 IDOR）。
// ============================================================

import { onBeforeUnmount, onMounted, ref, toValue, watch, type Ref, type MaybeRefOrGetter } from 'vue'
import { fetchFilePreview } from '@/api/file'

interface CacheEntry {
  url: string | null
  promise: Promise<string> | null
  refs: number
  idleTimer: number | null
}

const CACHE = new Map<string, CacheEntry>() // Map 保持插入序 = LRU 驱逐序
const CAP = 80 // blob URL 浏览器配额约数百；80 留余量且足够一屏+滚动复用
const IDLE_MS = 30_000 // refs 归零后 30s 仍无人复用 → 回收（滚动来回 30s 内命中缓存）

function clearIdle(e: CacheEntry) {
  if (e.idleTimer !== null) {
    clearTimeout(e.idleTimer)
    e.idleTimer = null
  }
}

/** refs=0 的最旧条目驱逐（LRU；不驱逐在用条目）。 */
function evictOneIdle() {
  for (const [k, e] of CACHE) {
    if (e.refs === 0) {
      clearIdle(e)
      if (e.url) URL.revokeObjectURL(e.url)
      CACHE.delete(k)
      return
    }
  }
}

function acquire(fileId: string): Promise<string> {
  const existed = CACHE.get(fileId)
  if (existed) {
    existed.refs++
    clearIdle(existed) // 复用取消待回收
    if (existed.url) return Promise.resolve(existed.url)
    return existed.promise! // in-flight 去重
  }
  if (CACHE.size >= CAP) evictOneIdle()
  const e: CacheEntry = { url: null, promise: null, refs: 1, idleTimer: null }
  CACHE.set(fileId, e)
  e.promise = fetchFilePreview(fileId)
    .then(u => {
      e.url = u
      return u
    })
    .catch(err => {
      // 失败清条目：下次进入视口可重试（非永久失败）
      if (CACHE.get(fileId) === e) CACHE.delete(fileId)
      throw err
    })
  return e.promise
}

function release(fileId: string) {
  const e = CACHE.get(fileId)
  if (!e) return
  e.refs = Math.max(0, e.refs - 1)
  if (e.refs === 0) {
    // 不立即回收——30s idle 窗口允许滚动来回命中缓存；超时才 revoke
    clearIdle(e)
    e.idleTimer = window.setTimeout(() => {
      if (e.url) URL.revokeObjectURL(e.url)
      CACHE.delete(fileId)
    }, IDLE_MS)
  }
}

/**
 * @param target  被观察的容器元素 ref（进入视口才拉取）
 * @param fileId  文件 id（null/空 → 不拉；变化自动切换并释放旧 id）
 * @param enabled 是否启用预览（按处理类别：image/video=true，text/audio=false）
 * @returns url（objectURL，未就绪 null）+ failed（拉取失败标志，回退色块）
 */
export function useLazyFilePreview(
  target: Ref<HTMLElement | null>,
  fileId: MaybeRefOrGetter<string | null | undefined>,
  enabled: MaybeRefOrGetter<boolean>
) {
  const url = ref<string | null>(null)
  const failed = ref(false)

  let io: IntersectionObserver | null = null
  let holding: string | null = null // 当前持有（已 acquire）的 fileId，卸载/切换时 release
  let visible = false

  async function ensure(fid: string) {
    if (holding === fid && (url.value || failed.value)) return
    if (holding && holding !== fid) {
      release(holding)
      holding = null
      url.value = null
      failed.value = false
    }
    holding = fid
    try {
      url.value = await acquire(fid)
      failed.value = false
    } catch {
      if (holding === fid) {
        failed.value = true
        url.value = null
        holding = null
      }
    }
  }

  function disconnect() {
    if (io) {
      io.disconnect()
      io = null
    }
  }

  function setupIO() {
    const el = target.value
    // jsdom 无 IntersectionObserver：静默跳过（生产浏览器必有；测试按需 polyfill）
    if (!el || io || typeof IntersectionObserver === 'undefined') return
    io = new IntersectionObserver(
      entries => {
        const ev = entries[0]
        visible = !!ev?.isIntersecting
        const fid = toValue(fileId)
        if (visible && toValue(enabled) && fid) void ensure(fid)
      },
      { rootMargin: '120px' } // 提前 120px 预拉，滚动顺滑
    )
    io.observe(el)
  }

  // 模板 ref 挂载后建立观察（onMounted 保证 DOM 就绪；watch 兜底晚绑定/条件渲染场景）
  onMounted(() => setupIO())
  watch(target, el => {
    if (el) setupIO()
    else disconnect()
  })

  // fileId/开关变化：当前已在视口则立刻拉新 id
  watch(
    [() => toValue(fileId), () => toValue(enabled)],
    ([fid, en]) => {
      if (en && fid && visible) void ensure(fid)
      else if ((!en || !fid) && holding) {
        release(holding)
        holding = null
        url.value = null
        failed.value = false
      }
    }
  )

  onBeforeUnmount(() => {
    disconnect()
    if (holding) {
      release(holding)
      holding = null
    }
  })

  return { url, failed }
}

// ---------- 仅测试用：重置模块缓存（隔离用例；生产不调） ----------
export function __resetPreviewCacheForTest() {
  for (const e of CACHE.values()) {
    clearIdle(e)
    if (e.url) URL.revokeObjectURL(e.url)
  }
  CACHE.clear()
}
