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

  it('选 b（中段）→ 上行 a + 下行 d；兄弟支 c 不入（S5 修复：定向 BFS 不折返，旧实现按连通分量算会误纳 c）', () => {
    const r = relatedClosure(['b'], diamond)!
    expect([...r.nodeIds].sort()).toEqual(['a', 'b', 'd'])
    // e2(a→c)、e4(c→d) 一端在闭包外 → 不入诱导边集
    expect([...r.edgeIds].sort()).toEqual(['e1', 'e3'])
  })

  it('选 a（源点）→ 全下游 a,b,c,d + 4 边（前向 BFS）', () => {
    const r = relatedClosure(['a'], diamond)!
    expect([...r.nodeIds].sort()).toEqual(['a', 'b', 'c', 'd'])
    expect(r.edgeIds.size).toBe(4)
  })

  it('多选 {b,x} → 两支并集整体求关联（b 支 ∪ x→y 旁支；c 仍不入）', () => {
    const r = relatedClosure(['b', 'x'], diamond)!
    expect([...r.nodeIds].sort()).toEqual(['a', 'b', 'd', 'x', 'y'])
    expect([...r.edgeIds].sort()).toEqual(['e1', 'e3', 'e5'])
  })

  it('S5 回归（用户实测场景）：选汇点分镜，同源另一支的兄弟节点不入闭包 → 会淡化', () => {
    // 女主→文本3 / 女主→分镜1（同源双支）：选分镜1 只应亮 {女主,分镜1}，文本3 淡化
    const fan = [
      { id: 'f1', source: '女主', target: '文本3' },
      { id: 'f2', source: '女主', target: '分镜1' }
    ]
    const r = relatedClosure(['分镜1'], fan)!
    expect([...r.nodeIds].sort()).toEqual(['分镜1', '女主'])
    expect(r.edgeIds.has('f1')).toBe(false)
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
