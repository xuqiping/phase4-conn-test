import { describe, expect, it, beforeEach, vi } from 'vitest'

// 修复IX-2 B2：连线保留开关——singleton ref + localStorage 持久化 + 非法回落。
// 模块 singleton 首值在 import 时烤定 → 每用例 resetModules 动态重导入，模拟新会话冷读。
describe('canvasPrefs · 连线保留开关（修复IX-2 Q4）', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetModules()
  })

  async function load() {
    return await import('./canvasPrefs')
  }

  it('默认开（修复VI「连线克隆一份」口径延续）', async () => {
    const { keepLinksOnCopy } = await load()
    expect(keepLinksOnCopy.value).toBe(true)
  })

  it('set 写 ref + localStorage 双落；toggle 翻转', async () => {
    const { keepLinksOnCopy, setKeepLinksOnCopy, toggleKeepLinksOnCopy } = await load()
    setKeepLinksOnCopy(false)
    expect(keepLinksOnCopy.value).toBe(false)
    expect(localStorage.getItem('canvas.keepLinksOnCopy')).toBe('false')

    toggleKeepLinksOnCopy()
    expect(keepLinksOnCopy.value).toBe(true)
    expect(localStorage.getItem('canvas.keepLinksOnCopy')).toBe('true')
  })

  it('存量持久化：上一会话关 → 新会话冷读仍关', async () => {
    const first = await load()
    first.setKeepLinksOnCopy(false)
    // 新"会话"：模块图重置后重新 import，readStored 从 localStorage 冷读
    vi.resetModules()
    const second = await load()
    expect(second.keepLinksOnCopy.value).toBe(false)
  })

  it('非法存量值（非 boolean）回落开', async () => {
    localStorage.setItem('canvas.keepLinksOnCopy', '"not-a-bool"')
    const { keepLinksOnCopy } = await load()
    expect(keepLinksOnCopy.value).toBe(true)
  })
})
