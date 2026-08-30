import { describe, expect, it } from 'vitest'
import {
  ancestors, parseMentions, interpolate, findBrokenMentions, uniqueLabel,
  type EdgeLike
} from './interpolate'

const E = (source: string, target: string): EdgeLike => ({ source, target })

describe('ancestors 反向 BFS', () => {
  it('直链 A→B→C：C 的祖先含 A、B', () => {
    const edges = [E('A', 'B'), E('B', 'C')]
    expect(ancestors('C', edges)).toEqual(new Set(['A', 'B']))
  })

  it('菱形 D 两条上游路径，祖先集去重', () => {
    // A→B, A→C, B→D, C→D
    const edges = [E('A', 'B'), E('A', 'C'), E('B', 'D'), E('C', 'D')]
    const s = ancestors('D', edges)
    expect(s.has('A')).toBe(true)
    expect(s.has('B')).toBe(true)
    expect(s.has('C')).toBe(true)
    expect(s.size).toBe(3)
  })

  it('成环 A→B→A：不死循环，自身不自引用', () => {
    const edges = [E('A', 'B'), E('B', 'A')]
    const sA = ancestors('A', edges)
    // A 的入边来自 B，B 的入边来自 A（已是自身，跳过）
    expect(sA.has('B')).toBe(true)
    expect(sA.has('A')).toBe(false)
  })

  it('无入边 → 空集', () => {
    expect(ancestors('X', [E('A', 'B')])).toEqual(new Set())
  })
})

describe('parseMentions', () => {
  it('抽 node / asset 两类占位符', () => {
    const ms = parseMentions('前 @{{node:n1}} 中 @{{asset:a9}} 后')
    expect(ms).toHaveLength(2)
    expect(ms[0]).toMatchObject({ kind: 'node', id: 'n1', raw: '@{{node:n1}}' })
    expect(ms[1]).toMatchObject({ kind: 'asset', id: 'a9', raw: '@{{asset:a9}}' })
  })

  it('无占位符 → 空', () => {
    expect(parseMentions('普通文本 {{slot}}')).toEqual([])
  })

  it('同一占位符重复出现都抽到', () => {
    const ms = parseMentions('@{{node:n1}} 和 @{{node:n1}}')
    expect(ms).toHaveLength(2)
  })

  it('空串安全', () => {
    expect(parseMentions('')).toEqual([])
  })
})

describe('interpolate 不递归替换', () => {
  it('节点占位符 → 上游产出文本', () => {
    const out = interpolate('扩写：@{{node:n1}}', (k, id) => (k === 'node' && id === 'n1' ? '老板娘出场' : undefined))
    expect(out).toBe('扩写：老板娘出场')
  })

  it('不递归：返回串里的 @占位符不再展开', () => {
    // n1 的产出本身含一个占位符，不应被二次解析（防 A@B、B 含 @ 死循环）
    const out = interpolate('@{{node:n1}}', () => '@{{node:n2}}')
    expect(out).toBe('@{{node:n2}}')
  })

  it('断链（resolve 返 undefined）→ 降级标记', () => {
    const out = interpolate('用 @{{node:ghost}} 素材', () => undefined)
    expect(out).toBe('用 【断链】 素材')
  })

  it('同一占位符多次出现全部替换', () => {
    const out = interpolate('@{{node:n1}}-@{{node:n1}}', () => 'X')
    expect(out).toBe('X-X')
  })

  it('无占位符原样返回', () => {
    expect(interpolate('啥都没有', () => 'X')).toBe('啥都没有')
  })
})

describe('findBrokenMentions 断链检测', () => {
  it('exists 返假的占位符被列出', () => {
    const broken = findBrokenMentions('@{{node:n1}} @{{node:gone}}', (_k, id) => id === 'n1')
    expect(broken).toEqual(['@{{node:gone}}'])
  })

  it('全在 → 空', () => {
    expect(findBrokenMentions('@{{node:n1}}', () => true)).toEqual([])
  })
})

describe('uniqueLabel L9 三入口查重', () => {
  it('不冲突原样返回', () => {
    expect(uniqueLabel('图片', ['文本', '视频'])).toBe('图片')
  })

  it('冲突加序号 2', () => {
    expect(uniqueLabel('图片', ['图片', '文本'])).toBe('图片 2')
  })

  it('2 也占 → 3', () => {
    expect(uniqueLabel('图片', ['图片', '图片 2'])).toBe('图片 3')
  })

  it('空列表原样', () => {
    expect(uniqueLabel('图片', [])).toBe('图片')
  })
})
