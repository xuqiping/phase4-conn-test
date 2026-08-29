import { describe, expect, it } from 'vitest'
import {
  decideDropTarget,
  groupEndpointOf,
  groupIdOf,
  isGroupEndpoint,
  mergeSnapshotEdges,
  resolveEdgesForFlow,
  splitSnapshotEdges,
  type GroupRectLike
} from './groupEdges'
import type { CanvasEdge, CanvasGroup } from '@/types/canvas'

// 修复VIII（2x 增补：组整体拉线）：组边数据层纯函数单测（plan A1 验证 + A3 decideDropTarget 全分支）。

function edge(source: string, target: string, id?: string): CanvasEdge {
  return { id: id ?? `e-${source}-${target}`, source, target } as CanvasEdge
}
function group(id: string, memberIds: string[]): CanvasGroup {
  return { id, name: id, memberIds, color: '#5b8def' }
}
const rect = (id: string, l: number, t: number, r: number, b: number): GroupRectLike =>
  ({ id, left: l, top: t, right: r, bottom: b })

describe('groupEdges · 伪 id 判定', () => {
  it('isGroupEndpoint/groupIdOf/groupEndpointOf 往返；节点 id 不误判', () => {
    expect(isGroupEndpoint('group:g1')).toBe(true)
    expect(isGroupEndpoint('node-123-0')).toBe(false)
    expect(isGroupEndpoint('')).toBe(false)
    expect(groupIdOf('group:g1')).toBe('g1')
    expect(groupEndpointOf('g1')).toBe('group:g1')
    expect(groupIdOf(groupEndpointOf('g-xxx'))).toBe('g-xxx')
  })
})

describe('resolveEdgesForFlow · 广播+聚合展开（VIII-1 ⑥）', () => {
  it('普通边直通（source/target 原样，数量 1:1）', () => {
    const out = resolveEdgesForFlow([edge('a', 'b')], [])
    expect(out).toHaveLength(1)
    expect(out[0]).toMatchObject({ source: 'a', target: 'b' })
  })

  // 修复VIII P4 人工反馈：id 口径——普通边原样直通（CanvasBoard 关联高亮按 edgeIds.has(e.id)
  // 匹配，加后缀会让普通边全部误判无关变暗）；组边展开仍加 ~seq 防同源多条撞 id。
  it('id 口径（P4）：普通边 id 原样直通；组边展开 id 带 ~seq 后缀', () => {
    const mixed = resolveEdgesForFlow(
      [edge('a', 'b', 'plain-1'), edge('group:g1', 'ext', 'ge-1')],
      [group('g1', ['m1', 'm2'])]
    )
    expect(mixed.find(e => e.source === 'a' && e.target === 'b')!.id).toBe('plain-1')
    expect(mixed.filter(e => e.source !== 'a').map(e => e.id).sort()).toEqual(['ge-1~0', 'ge-1~1'])
  })

  it('广播：外部→组 = 组全员各收一条（N 条）', () => {
    const out = resolveEdgesForFlow([edge('ext', 'group:g1')], [group('g1', ['m1', 'm2', 'm3'])])
    expect(out.map(e => [e.source, e.target])).toEqual([
      ['ext', 'm1'], ['ext', 'm2'], ['ext', 'm3']
    ])
  })

  it('聚合：组→外部 = 组全员产物各喂一条（N 条）', () => {
    const out = resolveEdgesForFlow([edge('group:g1', 'ext')], [group('g1', ['m1', 'm2'])])
    expect(out.map(e => [e.source, e.target])).toEqual([
      ['m1', 'ext'], ['m2', 'ext']
    ])
  })

  it('组→组 = 成员×成员（2×3=6）', () => {
    const out = resolveEdgesForFlow(
      [edge('group:g1', 'group:g2')],
      [group('g1', ['a1', 'a2']), group('g2', ['b1', 'b2', 'b3'])]
    )
    expect(out).toHaveLength(6)
    expect(out.every(e => e.source === 'a1' || e.source === 'a2')).toBe(true)
    expect(out.every(e => ['b1', 'b2', 'b3'].includes(e.target))).toBe(true)
  })

  it('去重：组→组展开边与既有成员边同向 → 只留一条（6 不变 7）', () => {
    const out = resolveEdgesForFlow(
      [edge('group:g1', 'group:g2'), edge('a1', 'b1')],
      [group('g1', ['a1', 'a2']), group('g2', ['b1', 'b2', 'b3'])]
    )
    expect(out).toHaveLength(6)
    expect(out.filter(e => e.source === 'a1' && e.target === 'b1')).toHaveLength(1)
  })

  it('空组/已删组 → 该边跳过（防悬挂广播）', () => {
    const groups = [group('g1', ['m1']), group('gEmpty', [])]
    expect(resolveEdgesForFlow([edge('group:gEmpty', 'ext')], groups)).toHaveLength(0)
    expect(resolveEdgesForFlow([edge('ext', 'group:gGone')], groups)).toHaveLength(0)
    expect(resolveEdgesForFlow([edge('group:g1', 'ext')], groups)).toHaveLength(1)
  })

  it('展开自环丢弃：组→自己成员（广播产生 m→m 无数据流意义）', () => {
    expect(resolveEdgesForFlow([edge('group:g1', 'm1')], [group('g1', ['m1', 'm2'])]))
      .toEqual([expect.objectContaining({ source: 'm2', target: 'm1' })])
  })

  it('同向重复普通边去重（只留第一条）', () => {
    const out = resolveEdgesForFlow([edge('a', 'b', 'first'), edge('a', 'b', 'second')], [])
    expect(out).toHaveLength(1)
    expect(out[0].id).toContain('first')
  })
})

describe('splitSnapshotEdges / mergeSnapshotEdges · 快照合并-拆分往返（VIII-1 ②）', () => {
  it('按端点拆分：任一端伪 id 即组边', () => {
    const { flowEdges, groupEdges } = splitSnapshotEdges([
      edge('a', 'b'),
      edge('group:g1', 'b'),
      edge('a', 'group:g2'),
      edge('group:g1', 'group:g2')
    ])
    expect(flowEdges.map(e => e.id)).toEqual(['e-a-b'])
    expect(groupEdges.map(e => e.id)).toEqual([
      'e-group:g1-b', 'e-a-group:g2', 'e-group:g1-group:g2'
    ])
  })

  it('合并→拆分往返一致（数量与成员逐边相等）', () => {
    const flow = [edge('a', 'b', 'f1'), edge('b', 'c', 'f2')]
    const grp = [edge('group:g1', 'c', 'g1'), edge('a', 'group:g2', 'g2')]
    const merged = mergeSnapshotEdges(flow, grp)
    expect(merged).toHaveLength(4)
    const again = splitSnapshotEdges(merged)
    expect(again.flowEdges.map(e => e.id)).toEqual(['f1', 'f2'])
    expect(again.groupEdges.map(e => e.id)).toEqual(['g1', 'g2'])
  })
})

describe('decideDropTarget · 连线松手落点分派（VIII-2 + VIII-1 ③ 全分支）', () => {
  /** 造一棵 vue-flow DOM：node[data-id] > handle。 */
  function makeNodeEl(id: string, withHandle: boolean): HTMLElement {
    const node = document.createElement('div')
    node.className = 'vue-flow__node'
    node.dataset.id = id
    if (withHandle) {
      const h = document.createElement('div')
      h.className = 'vue-flow__handle'
      node.appendChild(h)
      document.body.appendChild(node)
      return h // 返回 handle（落点在最深处）
    }
    document.body.appendChild(node)
    return node
  }
  const rects = [rect('g1', -100, -100, 100, 100), rect('g2', 200, 200, 300, 300)]

  it('落 handle → kind=handle（带所属节点 id；库 onConnect 原路径）', () => {
    const handleEl = makeNodeEl('n1', true)
    expect(decideDropTarget({ target: handleEl, flowPos: { x: 0, y: 0 }, groupRects: rects }))
      .toEqual({ kind: 'handle', nodeId: 'n1' })
  })

  it('落节点本体（非 handle）→ kind=node（VIII-2 两方向直连入口）', () => {
    const nodeEl = makeNodeEl('n2', false)
    expect(decideDropTarget({ target: nodeEl, flowPos: { x: 0, y: 0 }, groupRects: rects }))
      .toEqual({ kind: 'node', nodeId: 'n2' })
  })

  it('落组包围盒内空白（target=pane 穿透）→ kind=group（坑 3 坐标判定）', () => {
    const pane = document.createElement('div')
    expect(decideDropTarget({ target: pane, flowPos: { x: 0, y: 0 }, groupRects: rects }))
      .toEqual({ kind: 'group', groupId: 'g1' })
  })

  it('落组头（可点元素，非 vue-flow 节点）→ 按坐标归组（联动点 6）', () => {
    const head = document.createElement('div')
    head.className = 'canvas-board__groupbox-head'
    expect(decideDropTarget({ target: head, flowPos: { x: 250, y: 250 }, groupRects: rects }))
      .toEqual({ kind: 'group', groupId: 'g2' })
  })

  it('坐标在组外 → kind=pane（quick-add 现状分支）', () => {
    const pane = document.createElement('div')
    expect(decideDropTarget({ target: pane, flowPos: { x: 150, y: 150 }, groupRects: rects }))
      .toEqual({ kind: 'pane' })
  })

  it('target=null（无 DOM 上下文）→ 走坐标判定', () => {
    expect(decideDropTarget({ target: null, flowPos: { x: -50, y: -50 }, groupRects: rects }))
      .toEqual({ kind: 'group', groupId: 'g1' })
    expect(decideDropTarget({ target: null, flowPos: { x: 999, y: 999 }, groupRects: rects }))
      .toEqual({ kind: 'pane' })
  })

  it('落自身节点本体由调用方防自环（此处只证明能拿到 nodeId 供比对）', () => {
    const nodeEl = makeNodeEl('me', false)
    const d = decideDropTarget({ target: nodeEl, flowPos: { x: 0, y: 0 }, groupRects: [] })
    expect(d).toEqual({ kind: 'node', nodeId: 'me' })
  })
})
