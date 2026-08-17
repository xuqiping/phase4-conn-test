import { beforeEach, describe, expect, it } from 'vitest'

import { createBlobLruCache } from './blobLruCache'

/** 指定字节的占位 Blob（size 即真实占位长度，测试全用小值不耗内存）。 */
function blobOf(size: number): Blob {
  return new Blob([new Uint8Array(size)])
}

describe('createBlobLruCache（6x#2 文件预览 LRU）', () => {
  it('命中返回 Blob 且未命中返回 null', () => {
    const c = createBlobLruCache(3, 1000)
    const b = blobOf(4)
    c.put('a', b)
    expect(c.get('a')).toBe(b)
    expect(c.get('nope')).toBeNull()
  })

  it('满条数从最旧逐出（LRU 序非插入序）', () => {
    const c = createBlobLruCache(2, 1000)
    c.put('a', blobOf(1))
    c.put('b', blobOf(1))
    c.get('a') // 触碰 a → b 成最旧
    c.put('c', blobOf(1)) // 挤掉 b
    expect(c.get('b')).toBeNull()
    expect(c.get('a')).not.toBeNull()
    expect(c.get('c')).not.toBeNull()
    expect(c.size).toBe(2)
  })

  it('满字节从最旧逐出（新 blob 加上后超上限）', () => {
    const c = createBlobLruCache(10, 10)
    c.put('x', blobOf(6))
    c.put('y', blobOf(6)) // 6+6>10 → 挤掉 x
    expect(c.get('x')).toBeNull()
    expect(c.bytes).toBe(6)
  })

  it('单条超过字节上限不入池（防一条巨物清空整池）', () => {
    const c = createBlobLruCache(10, 10)
    c.put('big', blobOf(20))
    c.put('ok', blobOf(5))
    expect(c.get('big')).toBeNull()
    expect(c.size).toBe(1)
    expect(c.bytes).toBe(5)
  })

  it('重复键覆盖且字节记账修正', () => {
    const c = createBlobLruCache(10, 1000)
    c.put('a', blobOf(4))
    c.put('a', blobOf(6))
    expect(c.size).toBe(1)
    expect(c.bytes).toBe(6)
  })

  it('clear 清空条目与字节', () => {
    const c = createBlobLruCache(10, 1000)
    c.put('a', blobOf(4))
    c.clear()
    expect(c.size).toBe(0)
    expect(c.bytes).toBe(0)
    expect(c.get('a')).toBeNull()
  })

  it('逐出后字节记账不漂移（多轮进出）', () => {
    const c = createBlobLruCache(3, 1000)
    for (let i = 0; i < 9; i++) c.put(`k${i}`, blobOf(7))
    expect(c.size).toBe(3)
    expect(c.bytes).toBe(21)
    expect(c.get('k0')).toBeNull() // 最旧已逐出
    expect(c.get('k8')).not.toBeNull() // 最新保留
  })
})

describe('createBlobLruCache 逐出用例隔离', () => {
  beforeEach(() => {})
  it('空池 put 不逐出（size>0 守卫）', () => {
    const c = createBlobLruCache(1, 1) // 上限 1 字节
    c.put('fit', blobOf(1))
    expect(c.size).toBe(1)
  })
})
