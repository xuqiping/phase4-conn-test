import { describe, expect, it } from 'vitest'
import { expandGroupCandidates, nextGroupColor, GROUP_COLORS, MAX_GROUP_MEMBERS } from './groupCandidates'
import type { CanvasGroup, CanvasNode } from '@/types/canvas'

/** 造节点（position 必填）。 */
function nd(id: string): CanvasNode {
  return { id, type: 'text', position: { x: 0, y: 0 }, data: { label: id } }
}

/** 造组。 */
function gp(id: string, name: string, memberIds: string[], color = '#60a5fa'): CanvasGroup {
  return { id, name, memberIds, color }
}

const labelOf = (n: CanvasNode) => String(n.data.label ?? n.id)

describe('expandGroupCandidates', () => {
  it('无组 → 纯祖先候选（原逻辑不变）', () => {
    const nodes = [nd('a'), nd('b'), nd('c')]
    const out = expandGroupCandidates(new Set(['a', 'b']), nodes, [], labelOf)
    expect(out.map(c => c.id)).toEqual(['a', 'b'])
    expect(out.every(c => c.groupId === undefined)).toBe(true)
  })

  it('菱形：组内一成员是祖先 → 组全员进候选（含非祖先成员）', () => {
    // 菱形 a→b→d, a→c→d；组 g 含 b（祖先）+ e（非祖先）
    const nodes = [nd('a'), nd('b'), nd('c'), nd('d'), nd('e')]
    const groups = [gp('g1', '角色组', ['b', 'e'])]
    const out = expandGroupCandidates(new Set(['a', 'b', 'c']), nodes, groups, labelOf)
    // a/c 散节点在前；g1 分节全员（b+e）
    expect(out.map(c => `${c.id}${c.groupId ? `@${c.groupLabel}` : ''}`))
      .toEqual(['a', 'c', 'b@角色组', 'e@角色组'])
    const grouped = out.filter(c => c.groupId === 'g1')
    expect(grouped.every(c => c.groupColor === '#60a5fa')).toBe(true)
  })

  it('孤立组（无成员是祖先）→ 整组不进候选（防越权引用，规格 §10.3）', () => {
    const nodes = [nd('a'), nd('x'), nd('y')]
    const groups = [gp('g1', '孤立组', ['x', 'y'])]
    const out = expandGroupCandidates(new Set(['a']), nodes, groups, labelOf)
    expect(out.map(c => c.id)).toEqual(['a'])
  })

  it('组存在但未命中 → 祖先照常列散节点，组员全不进候选', () => {
    const nodes = [nd('a'), nd('x'), nd('y')]
    const groups = [gp('g1', '未命中组', ['x', 'y'])]
    const out = expandGroupCandidates(new Set(['a']), nodes, groups, labelOf)
    expect(out.map(c => c.id)).toEqual(['a'])
    expect(out.every(c => c.groupId === undefined)).toBe(true)
  })

  it('多组同时命中 → 各组独立分节，按 groups 数组序', () => {
    const nodes = [nd('a'), nd('b'), nd('c'), nd('d'), nd('p'), nd('q')]
    const groups = [gp('g1', '组一', ['b']), gp('g2', '组二', ['p', 'q'])]
    const out = expandGroupCandidates(new Set(['a', 'b', 'p']), nodes, groups, labelOf)
    expect(out.map(c => `${c.id}${c.groupId ? `@${c.groupLabel}` : ''}`))
      .toEqual(['a', 'b@组一', 'p@组二', 'q@组二'])
  })

  it('组员 id 悬挂（节点已删）→ 跳过不崩', () => {
    const nodes = [nd('a')]
    const groups = [gp('g1', '残组', ['a', 'gone'])]
    const out = expandGroupCandidates(new Set(['a']), nodes, groups, labelOf)
    expect(out.map(c => c.id)).toEqual(['a'])
  })
})

describe('组色轮转', () => {
  it('按组数轮转 8 色 + 上限常量', () => {
    expect(MAX_GROUP_MEMBERS).toBe(50)
    const groups: CanvasGroup[] = []
    expect(nextGroupColor(groups)).toBe(GROUP_COLORS[0])
    groups.push(gp('g1', 'a', [], GROUP_COLORS[0]))
    expect(nextGroupColor(groups)).toBe(GROUP_COLORS[1])
    // 第 9 组回到首色
    for (let i = 0; i < 7; i++) groups.push(gp(`g${i + 2}`, 'x', []))
    expect(nextGroupColor(groups)).toBe(GROUP_COLORS[0])
  })
})
