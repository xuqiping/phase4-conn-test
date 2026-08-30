// 7x#1：uuid 安全上下文回落（http 部署 crypto.randomUUID undefined 不崩）
import { afterEach, describe, expect, it } from 'vitest'
import { uuid } from './uuid'

describe('uuid · 7x#1 http 环境回落', () => {
  const orig = globalThis.crypto
  afterEach(() => {
    Object.defineProperty(globalThis, 'crypto', { value: orig, configurable: true, writable: true })
  })

  it('原生 randomUUID 可用 → 走原生', () => {
    const id = uuid()
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })

  it('randomUUID undefined（http 非安全上下文）→ 回落 v4 形态不抛错', () => {
    Object.defineProperty(globalThis, 'crypto', { value: {}, configurable: true, writable: true })
    const id = uuid()
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(uuid()).not.toBe(id)
  })

  it('crypto 整体缺失 → 回落不抛错', () => {
    Object.defineProperty(globalThis, 'crypto', { value: undefined, configurable: true, writable: true })
    expect(() => uuid()).not.toThrow()
  })
})
