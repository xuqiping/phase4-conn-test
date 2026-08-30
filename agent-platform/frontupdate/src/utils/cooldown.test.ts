/**
 * 12x-1 C3：发码倒计时持久化工具测试。
 * happy-dom 自带 localStorage；vi.useFakeTimers 控制 Date.now 过 deadline。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { saveCooldown, restoreCooldown } from './cooldown'

describe('12x-1 C3 cooldown 持久化', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('保存后立即恢复=全量秒数', () => {
    saveCooldown('mailcode:cd:a@b.com', 60)
    expect(restoreCooldown('mailcode:cd:a@b.com')).toBe(60)
  })

  it('过期（deadline 已过）→ 0 且清 key', () => {
    saveCooldown('sms:cd:13800138000', 30)
    vi.advanceTimersByTime(31_000)
    expect(restoreCooldown('sms:cd:13800138000')).toBe(0)
    expect(localStorage.getItem('sms:cd:13800138000')).toBeNull()
  })

  it('无 key / 脏数据 → 0 不抛', () => {
    expect(restoreCooldown('mailcode:cd:none@x.com')).toBe(0)
    localStorage.setItem('mailcode:cd:bad@x.com', 'not-a-number')
    expect(restoreCooldown('mailcode:cd:bad@x.com')).toBe(0)
  })

  it('不同账号 key 互不串扰（多账号同机）', () => {
    saveCooldown('mailcode:cd:a@b.com', 60)
    expect(restoreCooldown('mailcode:cd:other@b.com')).toBe(0)
    expect(restoreCooldown('mailcode:cd:a@b.com')).toBe(60)
  })

  it('中途恢复=剩余秒（向上取整）', () => {
    saveCooldown('mailcode:cd:a@b.com', 60)
    vi.advanceTimersByTime(10_500)
    const remain = restoreCooldown('mailcode:cd:a@b.com')
    expect(remain).toBe(50)
  })
})
