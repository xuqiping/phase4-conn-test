import { describe, expect, it } from 'vitest'
import { computeAutoLayout } from './autoLayout'
import type { CanvasEdge, CanvasNode } from '@/types/canvas'

// 修复VII Chunk1：autoLayout 纯函数单测（plan 验证 ①-⑦）。
function mkNode(id: string, over: Partial<CanvasNode> = {}): CanvasNode {
  return {
    id, type: 'text',
    position: { x: Math.random() * 400, y: Math.random() * 400 },
    data: { label: id },
    ...over
  } as CanvasNode
}
function mkEdge(source: string, target: string): CanvasEdge {
  return { id: `e-${source}-${target}`, source, target } as CanvasEdge
}

describe('computeAutoLayout · 全图模式', () => {
  it('① LR 序不变量：任一边 u→v 满足 x(u)+width(u) ≤ x(v)', () => {
    const nodes = [mkNode('a'), mkNode('b'), mkNode('c')]
    const edges = [mkEdge('a', 'b'), mkEdge('b', 'c')]
    const pos = computeAutoLayout(nodes, edges)
    expect(pos.get('a')!.x + 300).toBeLessThanOrEqual(pos.get('b')!.x)
    expect(pos.get('b')!.x + 300).toBeLessThanOrEqual(pos.get('c')!.x)
  })

  it('② 不连通子图布局后包围盒不相交', () => {
    const nodes = [mkNode('a'), mkNode('b'), mkNode('c'), mkNode('d')]
    const edges = [mkEdge('a', 'b'), mkEdge('c', 'd')]
    const pos = computeAutoLayout(nodes, edges)
    // 每个连通分量的 2 节点 bbox（text 300×180）
    const box1 = { l: pos.get('a')!.x, r: pos.get('b')!.x + 300, t: pos.get('a')!.y, b: pos.get('b')!.y + 180 }
    const box2 = { l: pos.get('c')!.x, r: pos.get('d')!.x + 300, t: pos.get('c')!.y, b: pos.get('d')!.y + 180 }
    const separated = box1.r < box2.l || box2.r < box1.l || box1.b < box2.t || box2.b < box1.t
    expect(separated).toBe(true)
  })

  it('③ 有环图不挂死、坐标有限（dagre 自动断环）', () => {
    const nodes = [mkNode('a'), mkNode('b'), mkNode('c')]
    const edges = [mkEdge('a', 'b'), mkEdge('b', 'c'), mkEdge('c', 'a'), mkEdge('a', 'a')]
    const pos = computeAutoLayout(nodes, edges)
    expect(pos.size).toBe(3)
    for (const p of pos.values()) {
      expect(Number.isFinite(p.x)).toBe(true)
      expect(Number.isFinite(p.y)).toBe(true)
    }
  })

  it('⑤ 确定性：同输入两次调用结果全等', () => {
    const nodes = [mkNode('a'), mkNode('b'), mkNode('c', { type: 'image', data: { label: 'c', width: 320, height: 320 } })]
    const edges = [mkEdge('a', 'c'), mkEdge('b', 'c')]
    const r1 = computeAutoLayout(nodes, edges)
    const r2 = computeAutoLayout(nodes, edges)
    expect([...r1.entries()]).toEqual([...r2.entries()])
  })

  it('⑥ 全部坐标对齐 16 网格（防松手 snap 跳格）', () => {
    const nodes = [mkNode('a'), mkNode('b'), mkNode('c')]
    const edges = [mkEdge('a', 'b'), mkEdge('a', 'c')]
    const pos = computeAutoLayout(nodes, edges)
    for (const p of pos.values()) {
      expect(p.x % 16).toBe(0)
      expect(p.y % 16).toBe(0)
    }
  })

  it('⑦ 空集返回空 Map；单节点返回该节点', () => {
    expect(computeAutoLayout([], []).size).toBe(0)
    const single = computeAutoLayout([mkNode('solo')], [])
    expect(single.size).toBe(1)
    expect(single.get('solo')!.x).toBeGreaterThanOrEqual(0)
  })

  it('尺寸取 data.width/height（媒体节点 320×320 参与分层计算）', () => {
    const nodes = [
      mkNode('a', { type: 'image', data: { label: 'a', width: 320, height: 320 } }),
      mkNode('b')
    ]
    const pos = computeAutoLayout(nodes, [mkEdge('a', 'b')])
    expect(pos.get('a')!.x + 320).toBeLessThanOrEqual(pos.get('b')!.x)
  })
})

describe('computeAutoLayout · 子图模式（includeIds）', () => {
  it('④ 集外节点不在返回 Map；集内子图新 bbox 左上角=原子图左上角（最小漂移锚定）', () => {
    const nodes = [
      mkNode('a', { position: { x: 100, y: 100 } }),
      mkNode('b', { position: { x: 500, y: 100 } }),
      mkNode('c', { position: { x: 900, y: 100 } }) // 集外
    ]
    const edges = [mkEdge('a', 'b'), mkEdge('b', 'c')] // b→c 是跨集边，不参排
    const pos = computeAutoLayout(nodes, edges, { includeIds: new Set(['a', 'b']) })
    expect(pos.has('c')).toBe(false)
    expect(pos.size).toBe(2)
    // 原子图 bbox 左上角 = (100,100)；锚定后集合最小 x/y=100 取整 96（round(100/16)*16=96）。
    // 断 min 而非具体节点——列内上下序由 dagre 定，哪个节点在最左上不确定。
    const xs = [...pos.values()].map(p => p.x)
    const ys = [...pos.values()].map(p => p.y)
    expect(Math.min(...xs)).toBe(96)
    expect(Math.min(...ys)).toBe(96)
  })

  it('诱导边口径：只有两端都在集内的边参与（跨集边不拉扯集内布局）', () => {
    const nodes = [mkNode('a'), mkNode('b'), mkNode('out')]
    const pos = computeAutoLayout(nodes, [mkEdge('a', 'out'), mkEdge('out', 'b')], { includeIds: new Set(['a', 'b']) })
    // a、b 间无边 → 各自独立分量，不强制分层序
    expect(pos.size).toBe(2)
  })

  it('TB 方向：上游在上（y 更小）', () => {
    const nodes = [mkNode('a'), mkNode('b')]
    const pos = computeAutoLayout(nodes, [mkEdge('a', 'b')], { direction: 'TB' })
    expect(pos.get('a')!.y + 180).toBeLessThanOrEqual(pos.get('b')!.y)
  })
})
