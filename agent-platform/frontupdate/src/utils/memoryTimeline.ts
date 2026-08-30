/**
 * M2 时间线记忆:value schema 前端解析(镜像后端 MemoryValueTimeline)。
 * value = 标量 + 分号分段 + 行首 ISO 日期前缀(2026-06-25 住萧山;2027-01-01 住拱墅)。
 * 无日期段(老数据单值/非时序)→ date=null。
 */
export interface MemorySegment {
  date: string | null
  content: string
}

const DATED_PREFIX = /^(\d{4}-\d{2}-\d{2})\s+(.+)$/

/** value → 段数组。null/空白 → []。 */
export function parseMemoryValue(v: string | null | undefined): MemorySegment[] {
  if (!v || !v.trim()) return []
  return v
    .split(';')
    .map(s => s.trim())
    .filter(Boolean)
    .map(part => {
      const m = part.match(DATED_PREFIX)
      return m ? { date: m[1], content: m[2].trim() } : { date: null, content: part }
    })
}

/** value 含至少一个 dated 段 → true(value 列据此判是否时间线展示)。 */
export function isTimelineValue(v: string | null | undefined): boolean {
  return parseMemoryValue(v).some(s => s.date !== null)
}
