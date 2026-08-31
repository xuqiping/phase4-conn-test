import { describe, expect, it } from 'vitest'
import { buildCopySet, planLabels, planPastePositions, remapCrossEdges, remapEdges } from './canvasClipboard'
import type { CanvasEdge, CanvasNode } from '@/types/canvas'

// 修复VII Chunk3：子图剪贴板纯函数（plan 验证 ①-⑥）。
function mkNode(id: string, over: Partial<CanvasNode> & { data?: Record<string, unknown> } = {}): CanvasNode {
  return {
    id, type: 'text',
    position: { x: 0, y: 0 },
    data: { label: id },
    ...over
  } as CanvasNode
}
function mkEdge(source: string, target: string): CanvasEdge {
  return { id: `e-${source}-${target}`, source, target, type: 'deletable' } as CanvasEdge
}

describe('buildCopySet · 复制集构建', () => {
  it('① 3 节点 2 内边 1 外边 → items=3、innerEdges=2（诱导边口径，Q1）', () => {
    const nodes = [mkNode('a'), mkNode('b'), mkNode('c'), mkNode('out')]
    const edges = [mkEdge('a', 'b'), mkEdge('b', 'c'), mkEdge('c', 'out')]
    const clip = buildCopySet(nodes, edges, ['a', 'b', 'c'])
    expect(clip).not.toBeNull()
    expect(clip!.items).toHaveLength(3)
    expect(clip!.innerEdges).toHaveLength(2)
  })

  it('② 脱钩口径与创建副本同源：RESET_KEYS 清零 + 产物保留 + status 重算', () => {
    const nodes = [
      mkNode('a', {
        type: 'image',
        data: {
          label: '主图', previewUrl: 'blob:a', fileId: 'f1', width: 320, height: 320,
          taskId: 't1', assetId: 9, assetName: '库内', errorMsg: '旧错', status: 'running'
        }
      })
    ]
    const clip = buildCopySet(nodes, [], ['a'])!
    const d = clip.items[0].data as Record<string, unknown>
    expect(d.taskId).toBeUndefined()
    expect(d.assetId).toBeUndefined()
    expect(d.errorMsg).toBeUndefined()
    expect(d.previewUrl).toBe('blob:a')
    expect(d.status).toBe('success')
  })

  it('失效 id（节点已删）被过滤；全失效 → null（调用方清剪贴板恢复图片粘贴通道）', () => {
    expect(buildCopySet([mkNode('a')], [], ['ghost'])).toBeNull()
    expect(buildCopySet([], [], [])).toBeNull()
  })

  it('自环边保留（粘贴体自身成环，同 cloneEdgesForDuplicate 口径）', () => {
    const clip = buildCopySet([mkNode('a')], [mkEdge('a', 'a')], ['a'])!
    expect(clip.innerEdges).toHaveLength(1)
  })

  it('包围盒 = 最小圈住矩形（含节点尺寸，image 320×320 参与计算）', () => {
    const nodes = [
      mkNode('a', { type: 'image', position: { x: 0, y: 0 }, data: { label: 'a', width: 320, height: 320 } }),
      mkNode('b', { position: { x: 100, y: 500 }, data: { label: 'b' } })
    ]
    const clip = buildCopySet(nodes, [], ['a', 'b'])!
    expect(clip.bbox).toEqual({ left: 0, top: 0, width: 400, height: 680 })
  })
})

describe('planPastePositions · 落点平移（Q2）', () => {
  const nodes = [
    mkNode('a', { position: { x: 0, y: 0 } }),
    mkNode('b', { position: { x: 400, y: 200 }, data: { label: 'b', width: 320, height: 320 } })
  ]
  it('③ bbox 中心对齐鼠标；pasteCount=1/2 整体 +32/+64；坐标 16 网格对齐', () => {
    // bbox: left0 top0 w720 h520 → 中心(360,260)；目标(1000,780) → dx=640 dy=520
    const base = buildCopySet(nodes, [], ['a', 'b'])!
    const p0 = planPastePositions(base, { x: 1000, y: 780 })
    const a0 = p0.find(p => p.key === 'a')!
    expect(a0.x).toBe(640)
    expect(a0.y).toBe(528) // raw 520 → snap round(520/16)*16=528
    expect(p0.every(p => p.x % 16 === 0 && p.y % 16 === 0)).toBe(true)
    const once = { ...base, pasteCount: 1 }
    const p1 = planPastePositions(once, { x: 1000, y: 780 })
    expect(p1.find(p => p.key === 'a')!.x).toBe(672)
    const twice = { ...base, pasteCount: 2 }
    const p2 = planPastePositions(twice, { x: 1000, y: 780 })
    expect(p2.find(p => p.key === 'a')!.y).toBe(592) // raw 584 → snap 592
  })
})

describe('planLabels / remapEdges', () => {
  it('④ label 三级去重：画布已有同名 + 批内互撞 → 追加序号', () => {
    const nodes = [
      mkNode('a', { data: { label: '图1' } }),
      mkNode('b', { data: { label: '图1' } })
    ]
    const clip = buildCopySet(nodes, [], ['a', 'b'])!
    const labels = planLabels(clip, ['图1', '图1 2'])
    expect(labels[0]).toBe('图1 3')
    expect(labels[1]).toBe('图1 4')
  })

  it('⑤ 边重映射：端点全换新 id、id 唯一、handles/样式保留', () => {
    const nodes = [mkNode('a'), mkNode('b')]
    const edge: CanvasEdge = {
      id: 'old', source: 'a', target: 'b', type: 'deletable',
      sourceHandle: 'out-1', style: { stroke: 'red' }
    }
    const clip = buildCopySet(nodes, [edge], ['a', 'b'])!
    const map = new Map([['a', 'new-a'], ['b', 'new-b']])
    const [remapped] = remapEdges(clip, map)
    expect(remapped.source).toBe('new-a')
    expect(remapped.target).toBe('new-b')
    expect(remapped.id).not.toBe('old')
    expect(remapped.sourceHandle).toBe('out-1')
    expect(remapped.style).toEqual({ stroke: 'red' })
  })

  it('⑥ 批量重映射 id 不撞（同毫秒多条边）', () => {
    const nodes = [mkNode('a'), mkNode('b')]
    const edges = [mkEdge('a', 'b'), mkEdge('b', 'a')]
    const clip = buildCopySet(nodes, edges, ['a', 'b'])!
    const out = remapEdges(clip, new Map([['a', 'x'], ['b', 'y']]))
    expect(new Set(out.map(e => e.id)).size).toBe(2)
  })

  it('⑦ P4 review Y1：边快照断引用——复制后改源边对象，剪贴板不受影响', () => {
    const nodes = [mkNode('a'), mkNode('b')]
    const edge: CanvasEdge = { id: 'old', source: 'a', target: 'b', type: 'deletable' }
    const clip = buildCopySet(nodes, [edge], ['a', 'b'])!
    // 复制后源边被点选注入会话 class（粘贴时刻烤进新边的场景）
    edge.class = 'canvas-edge--selected'
    const [remapped] = remapEdges(clip, new Map([['a', 'x'], ['b', 'y']]))
    expect(remapped.class).toBeUndefined()
  })

  it('⑧ P4 review Y1：remapEdges 剥会话 class（复制时刻即带选中态也一并剥掉）', () => {
    const nodes = [mkNode('a'), mkNode('b')]
    const edge: CanvasEdge = { id: 'old', source: 'a', target: 'b', type: 'deletable', class: 'canvas-edge--selected' }
    const clip = buildCopySet(nodes, [edge], ['a', 'b'])!
    const [remapped] = remapEdges(clip, new Map([['a', 'x'], ['b', 'y']]))
    expect(remapped.class).toBeUndefined()
    expect(remapped.type).toBe('deletable')
  })
})

// 修复VIII（VIII-1 ⑧）：组边不带出 Ctrl+C/V 复制粘贴——伪 id 端点显式兜底过滤。
describe('buildCopySet · 修复VIII 组边排除', () => {
  it('伪 id 端点边不进 innerEdges（选中集是节点 id 天然排除，兜底显式过滤）', () => {
    const nodes = [mkNode('a'), mkNode('b'), mkNode('ext')]
    // group:g1→b / b→group:g2 端点含伪 id（就算选中集含 b 也不带）；a→b 普通诱导边保留
    const edges = [
      mkEdge('group:g1', 'b'),
      mkEdge('b', 'group:g2'),
      mkEdge('a', 'b')
    ]
    const clip = buildCopySet(nodes, edges, ['a', 'b'])
    expect(clip).not.toBeNull()
    expect(clip!.innerEdges).toHaveLength(1)
    expect(clip!.innerEdges[0]).toMatchObject({ source: 'a', target: 'b' })
  })
})

// 修复IX-2 B1（Q4 拍板）：跨集边恒收集 + 单侧重映射 + 悬挂防护。
describe('crossEdges · 修复IX-2 跨集边（Q4）', () => {
  it('① 恒收集：恰一端在集内进 crossEdges（不看开关，粘贴时点判定）；组边排除', () => {
    const nodes = [mkNode('a'), mkNode('b'), mkNode('ext1'), mkNode('ext2')]
    const edges = [
      mkEdge('a', 'b'),        // 诱导边 → innerEdges
      mkEdge('a', 'ext1'),     // 跨集边（source 在集内）
      mkEdge('ext2', 'b'),     // 跨集边（target 在集内）
      mkEdge('ext1', 'ext2'),  // 两端都在集外 → 不收
      mkEdge('group:g', 'a')   // 组边 → 不收
    ]
    const clip = buildCopySet(nodes, edges, ['a', 'b'])!
    expect(clip.crossEdges).toHaveLength(2)
    expect(clip.crossEdges.map(e => `${e.source}>${e.target}`).sort()).toEqual(['a>ext1', 'ext2>b'])
  })

  it('② 单侧重映射：集内端换新 id、集外端保原 id；id 全新不撞原边', () => {
    const nodes = [mkNode('a'), mkNode('ext')]
    const edges = [mkEdge('a', 'ext'), mkEdge('ext', 'a')]
    const clip = buildCopySet(nodes, edges, ['a'])!
    const map = new Map([['a', 'new-a']])
    const out = remapCrossEdges(clip, map, new Set(['new-a', 'ext']))
    expect(out).toHaveLength(2)
    for (const e of out) {
      expect(e.id).toMatch(/^edge-/)
      expect(e.id).not.toBe('e-a-ext')
      expect(e.class).toBeUndefined()
    }
    expect(out[0]).toMatchObject({ source: 'new-a', target: 'ext' })
    expect(out[1]).toMatchObject({ source: 'ext', target: 'new-a' })
  })

  it('③ 悬挂防护：集外端点已删（不在 aliveNodeIds）→ 丢边不产断边', () => {
    const nodes = [mkNode('a'), mkNode('ext')]
    const clip = buildCopySet(nodes, [mkEdge('a', 'ext'), mkEdge('ext', 'a')], ['a'])!
    // ext 已删：alive 只剩 new-a
    const out = remapCrossEdges(clip, new Map([['a', 'new-a']]), new Set(['new-a']))
    expect(out).toHaveLength(0)
  })

  it('④ 允许平行重复边：同 source/target 与既有边并存，不去重（Q4 口径）', () => {
    const nodes = [mkNode('a'), mkNode('ext')]
    const clip = buildCopySet(nodes, [mkEdge('a', 'ext')], ['a'])!
    // aliveNodeIds 含既有边端点组合（new-a>ext 已存在一条）→ 重映射仍产出（并存）
    const out = remapCrossEdges(clip, new Map([['a', 'new-a']]), new Set(['new-a', 'ext']))
    expect(out).toHaveLength(1)
    expect(out[0]).toMatchObject({ source: 'new-a', target: 'ext' })
  })

  it('⑤ 退化防护：两端都在/都不在映射集（复制后结构变化）→ 按 null 丢', () => {
    const nodes = [mkNode('a'), mkNode('b')]
    const clip = buildCopySet(nodes, [mkEdge('a', 'b')], ['a', 'b'])!
    // innerEdge a>b 手工塞进 crossEdges 模拟退化（恒收集时点保证恰一端，此处测双保险）
    const degenerate: typeof clip = { ...clip, crossEdges: [mkEdge('a', 'b'), mkEdge('x', 'y')] }
    const out = remapCrossEdges(degenerate, new Map([['a', 'n1'], ['b', 'n2']]), new Set(['n1', 'n2', 'x', 'y']))
    expect(out).toHaveLength(0)
  })
})

