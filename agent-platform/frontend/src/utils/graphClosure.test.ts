import { describe, expect, it } from 'vitest'
import { relatedClosure } from './graphClosure'

/** 2x 四轮 S5 关联闭包（设计 §8 单测要求：菱形图、环外分支）。 */
describe('relatedClosure（S5 关联高亮集合）', () => {
  /** 菱形 a→(b,c)→d + 旁支 x→y（与菱形无连） */
  const diamond = [
    { id: 'e1', source: 'a', target: 'b' },
    { id: 'e2', source: 'a', target: 'c' },
    { id: 'e3', source: 'b', target: 'd' },
    { id: 'e4', source: 'c', target: 'd' },
    { id: 'e5', source: 'x', target: 'y' }
  ]

  it('选 d（汇点）→ 全菱形节点+4 边；旁支 x/y 不入', () => {
    const r = relatedClosure(['d'], diamond)!
    expect(r).not.toBeNull()
    expect([...r.nodeIds].sort()).toEqual(['a', 'b', 'c', 'd'])
    expect([...r.edgeIds].sort()).toEqual(['e1', 'e2', 'e3', 'e4'])
  })

  it('选 b（中段）→ 传递闭包仍全菱形：b 后代 d、d 祖先 c 均命中（祖先∪后代按传递闭包算）', () => {
    const r = relatedClosure(['b'], diamond)!
    expect([...r.nodeIds].sort()).toEqual(['a', 'b', 'c', 'd'])
    expect([...r.edgeIds].sort()).toEqual(['e1', 'e2', 'e3', 'e4'])
  })

  it('选 a（源点）→ 全下游 a,b,c,d + 4 边（前向 BFS）', () => {
    const r = relatedClosure(['a'], diamond)!
    expect([...r.nodeIds].sort()).toEqual(['a', 'b', 'c', 'd'])
    expect(r.edgeIds.size).toBe(4)
  })

  it('多选 {b,x} → 两支并集整体求关联（菱形 ∪ x→y 旁支）', () => {
    const r = relatedClosure(['b', 'x'], diamond)!
    expect([...r.nodeIds].sort()).toEqual(['a', 'b', 'c', 'd', 'x', 'y'])
    expect([...r.edgeIds].sort()).toEqual(['e1', 'e2', 'e3', 'e4', 'e5'])
  })

  it('环 p→q→p 不死循环：闭包=环上全员', () => {
    const ring = [
      { id: 'r1', source: 'p', target: 'q' },
      { id: 'r2', source: 'q', target: 'p' },
      { id: 'r3', source: 'q', target: 'z' }
    ]
    const r = relatedClosure(['p'], ring)!
    expect([...r.nodeIds].sort()).toEqual(['p', 'q', 'z'])
    expect([...r.edgeIds].sort()).toEqual(['r1', 'r2', 'r3'])
  })

  it('空选集 → null（无高亮态，调用方直接还原）', () => {
    expect(relatedClosure([], diamond)).toBeNull()
    expect(relatedClosure([''], diamond)).toBeNull()
  })
})
