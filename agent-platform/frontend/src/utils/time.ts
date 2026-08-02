/**
 * 时间格式化工具（M1）。
 * formatRelativeTime：微信风格相对时间（刚刚 / N分钟前 / N小时前 / 昨天 / 前天 / MM-DD / YYYY-MM-DD）。
 * formatAbsoluteTime：绝对时间（相对时间 hover tooltip 用）。
 */

/** 绝对时间（zh-CN 本地化），失败回退原串。 */
export function formatAbsoluteTime(iso: string | null | undefined): string {
  if (!iso) return '-'
  try {
    return new Date(iso).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return iso
  }
}

/**
 * 相对时间（微信风格）：
 *   < 60s        → 刚刚
 *   < 60min      → N分钟前
 *   < 24h        → N小时前
 *   昨天 (T-1)   → 昨天
 *   前天 (T-2)   → 前天
 *   同年         → MM-DD
 *   跨年         → YYYY-MM-DD
 * 同一天且 diff<0（时钟漂移/未来时间）→ 刚刚。
 */
export function formatRelativeTime(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso

  const now = new Date()
  const diffMs = now.getTime() - d.getTime()
  const diffMin = Math.floor(diffMs / 60000)

  // 日历日差（按当地 0 点），用于 昨天/前天/同天判断
  const startOf = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const dayDiff = Math.round((startOf(now) - startOf(d)) / 86400000)

  if (diffMs < 0) return '刚刚'                              // 未来时间/时钟漂移
  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin}分钟前`
  const diffHour = Math.floor(diffMin / 60)
  if (diffHour < 24 && dayDiff < 1) return `${diffHour}小时前`
  if (dayDiff === 1) return '昨天'
  if (dayDiff === 2) return '前天'
  if (d.getFullYear() === now.getFullYear()) {
    const mm = String(d.getMonth() + 1).padStart(2, '0')
    const dd = String(d.getDate()).padStart(2, '0')
    return `${mm}-${dd}`
  }
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
