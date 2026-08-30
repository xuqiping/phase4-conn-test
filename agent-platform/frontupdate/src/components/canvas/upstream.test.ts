import { describe, expect, it } from 'vitest'
import { collectUpstream } from './upstream'
import type { CanvasEdge, CanvasNode } from '@/types/canvas'

function mkNode(id: string, type = 'text', label = id): CanvasNode {
  return { id, type, position: { x: 0, y: 0 }, data: { label } }
}
function mkEdge(source: string, target: string): CanvasEdge {
  return { id: `e-${source}-${target}`, source, target }
}

/**
 * D2（2x-8）上游收集纯函数：
 * 菱形 D←B/C←A（B、C 皆直接上游；A 深度 2 只收一次）。
 */
const diamondNodes = [mkNode('a'), mkNode('b'), mkNode('c'), mkNode('d')]
const diamondEdges = [mkEdge('a', 'b'), mkEdge('a', 'c'), mkEdge('b', 'd'), mkEdge('c', 'd')]

describe('collectUpstream · D2 上游收集', () => {
  it('菱形依赖：直接上游 depth=1，共同祖先 depth=2 去重只收一次', () => {
    const { items, truncated } = collectUpstream('d', diamondNodes, diamondEdges)
    expect(truncated).toBe(false)
    const byId = new Map(items.map(u => [u.node.id, u.depth]))
    expect(byId.get('b')).toBe(1)
    expect(byId.get('c')).toBe(1)
    expect(byId.get('a')).toBe(2)
    expect(items).toHaveLength(3)
  })

  it('深度分层排序：depth 升序（1 层在前，2 层在后）', () => {
    const { items } = collectUpstream('d', diamondNodes, diamondEdges)
    const depths = items.map(u => u.depth)
    expect([...depths].sort((x, y) => x - y)).toEqual(depths)
  })

  it('环安全：A→B→A 成环不无限循环，各自只收一次', () => {
    const nodes = [mkNode('a'), mkNode('b')]
    const edges = [mkEdge('a', 'b'), mkEdge('b', 'a')]
    const { items } = collectUpstream('a', nodes, edges)
    expect(items).toHaveLength(1)
    expect(items[0].node.id).toBe('b')
    expect(items[0].depth).toBe(1)
  })

  it('无上游（孤立节点/无入边）→ 空结果', () => {
    expect(collectUpstream('x', [mkNode('x')], []).items).toHaveLength(0)
    // 有出边不算上游（方向：只沿 target→source 上行）
    expect(collectUpstream('a', diamondNodes, diamondEdges).items).toHaveLength(0)
  })

  it('seed 不在 nodes 中 → 空结果（节点刚删防御）', () => {
    expect(collectUpstream('ghost', diamondNodes, diamondEdges).items).toHaveLength(0)
  })

  it('超上限截断：cap=2 时第 3 个上游丢弃且 truncated=true', () => {
    const { items, truncated } = collectUpstream('d', diamondNodes, diamondEdges, 2)
    expect(items).toHaveLength(2)
    expect(truncated).toBe(true)
  })

  it('seed 自环（自己连自己）不进结果', () => {
    const nodes = [mkNode('a')]
    const { items } = collectUpstream('a', nodes, [mkEdge('a', 'a')])
    expect(items).toHaveLength(0)
  })
})
