import { describe, it, expect } from 'vitest'
import { demoNodes, demoEdges, genStressNodes, resolveNodeCount } from '@/mocks/canvas'

// mock 数据健康度：连线不悬空、类型/状态覆盖、压测生成器边界
describe('canvas mock', () => {
  it('演示流 ≥10 节点，覆盖 6 类型', () => {
    expect(demoNodes.length).toBeGreaterThanOrEqual(10)
    const kinds = new Set(demoNodes.map((n) => n.type))
    expect(kinds.size).toBe(6)
  })

  it('演示流覆盖 4 种数据状态', () => {
    const statuses = new Set(demoNodes.map((n) => n.data.status))
    expect(statuses).toEqual(new Set(['idle', 'running', 'success', 'failed']))
  })

  it('连线无悬空（source/target 都存在）', () => {
    const ids = new Set(demoNodes.map((n) => n.id))
    for (const e of demoEdges) {
      expect(ids.has(e.source), `${e.id} source 悬空`).toBe(true)
      expect(ids.has(e.target), `${e.id} target 悬空`).toBe(true)
    }
  })

  it('resolveNodeCount：null/非法 → null', () => {
    expect(resolveNodeCount(null)).toBeNull()
    expect(resolveNodeCount('abc')).toBeNull()
    expect(resolveNodeCount('-5')).toBeNull()
  })

  it('resolveNodeCount：>500 截 500', () => {
    expect(resolveNodeCount('9999')).toBe(500)
  })

  it('genStressNodes：数量正确且连线不悬空', () => {
    const { nodes, edges } = genStressNodes(100)
    expect(nodes).toHaveLength(100)
    const ids = new Set(nodes.map((n) => n.id))
    for (const e of edges) {
      expect(ids.has(e.source)).toBe(true)
      expect(ids.has(e.target)).toBe(true)
    }
  })
})
