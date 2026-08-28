import { describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { mount } from '@vue/test-utils'
import CanvasBoard from './CanvasBoard.vue'

// vue-flow 真实渲染依赖 DOM 布局（jsdom 缺 ResizeObserver/DOMRect），mock 掉只测 prop wiring。
// vi.mock 会被提升到文件顶部，stub 定义须放 vi.hoisted 里才在 mock 工厂执行时已初始化。
const VueFlowStub = vi.hoisted(() => ({
  name: 'VueFlow',
  props: ['nodes', 'edges', 'nodeTypes', 'edgeTypes', 'defaultEdgeOptions', 'connectionLineStyle',
    'snapToGrid', 'snapGrid', 'fitViewOnInit', 'deleteKeyCode', 'panOnDrag', 'selectionKeyCode'],
  emits: ['update:nodes', 'update:edges', 'selection-end', 'connect', 'connect-start', 'connect-end',
    'node-click', 'node-context-menu', 'node-drag-stop', 'edge-click', 'pane-click'],
  render: () => null
}))
// 修复VII Chunk2：onSelectionEnd 读 getSelectedNodes.value（ref 语义），mock 成 ref 形状
// 并暴露可变源——测试里设选中集再 emit selection-end，驱动多选/单选路径。默认 [] 不影响既有用例。
const selState = vi.hoisted(() => ({ nodes: [] as { id: string }[] }))
vi.mock('@vue-flow/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@vue-flow/core')>()
  return {
    ...actual,
    VueFlow: VueFlowStub,
    useVueFlow: () => ({
      // 修复VII Chunk4：pasteSubgraph 落点计算调 project（identity 直返，测试可断言落点）
      project: vi.fn((p: { x: number; y: number }) => p),
      zoomIn: vi.fn(),
      zoomOut: vi.fn(),
      fitView: vi.fn(),
      getViewport: vi.fn(() => ({ x: 0, y: 0, zoom: 1 })),
      getSelectedNodes: { get value() { return selState.nodes } },
      // 2x 四轮 S9：包围盒视口跟踪（真实 onMove 是 vue-flow 事件钩子；测试环境无拖拽，空实现够用）
      onMove: vi.fn(),
      vueFlowRef: { value: null }
    })
  }
})
vi.mock('@vue-flow/background', () => ({ Background: { name: 'Background', render: () => null } }))

function flowProps(wrapper: ReturnType<typeof mount>) {
  return wrapper.getComponent(VueFlowStub).props() as unknown as { panOnDrag: boolean; selectionKeyCode: boolean | string }
}

function boardVm(wrapper: { vm: unknown }) {
  return wrapper.vm as unknown as { dragMode: 'pan' | 'select'; setDragMode: (m: 'pan' | 'select') => void }
}

describe('CanvasBoard 交互模式（拖拽画布 vs 框选节点）', () => {
  it('默认 pan 模式：panOnDrag=true / selectionKeyCode="Shift"（左键拖=平移，Shift+拖=临时框选）', () => {
    const wrapper = mount(CanvasBoard)
    expect(boardVm(wrapper).dragMode).toBe('pan')
    expect(flowProps(wrapper).panOnDrag).toBe(true)
    expect(flowProps(wrapper).selectionKeyCode).toBe('Shift')
  })

  it('切 select 模式：panOnDrag=false / selectionKeyCode=true（左键拖=Windows 式框选，免 Shift）', async () => {
    const wrapper = mount(CanvasBoard)
    await wrapper.find('button[title^="框选节点模式"]').trigger('click')
    expect(boardVm(wrapper).dragMode).toBe('select')
    expect(flowProps(wrapper).panOnDrag).toBe(false)
    expect(flowProps(wrapper).selectionKeyCode).toBe(true)
  })

  it('select 切回 pan：prop 翻转回来；aria-pressed 跟随激活态', async () => {
    const wrapper = mount(CanvasBoard)
    // aria-pressed 按钮=模式×2 + S5「只看关联」×1（无选中时 disabled）
    const btns = wrapper.findAll('button[aria-pressed]')
    expect(btns).toHaveLength(3)
    expect(btns[2].attributes('disabled')).toBeDefined() // 只看关联：无节点选中禁用
    await btns[1].trigger('click')
    await wrapper.find('button[title^="拖拽画布模式"]').trigger('click')
    expect(boardVm(wrapper).dragMode).toBe('pan')
    expect(flowProps(wrapper).panOnDrag).toBe(true)
    expect(flowProps(wrapper).selectionKeyCode).toBe('Shift')
    const pressed = btns.map(b => b.attributes('aria-pressed'))
    expect(pressed).toEqual(['true', 'false', 'false'])
  })
})

/** 2x 四轮 S2：data.width/height ↔ wrapper style 推导与剥离（单一真相源=data）。 */
describe('CanvasBoard 节点宽高持久化（S2）', () => {
  type BoardVm = ReturnType<typeof boardVm> & {
    loadSnapshot: (s: { nodes: unknown[]; edges: unknown[] }) => void
    getSnapshot: () => { nodes: { data: Record<string, unknown>; style?: Record<string, string> }[] }
    addNode: (p: { type?: string; data?: Record<string, unknown> }) => string
  }
  const vm = (w: ReturnType<typeof mount>) => boardVm(w) as unknown as BoardVm

  it('loadSnapshot：有宽高字段 → wrapper style 带.px；老节点无字段 → 默认宽 200、高缺省', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({
      nodes: [
        { id: 'n1', type: 'text', position: { x: 0, y: 0 }, data: { label: 'A', width: 320, height: 120 } },
        { id: 'n2', type: 'text', position: { x: 0, y: 0 }, data: { label: 'B' } }
      ],
      edges: []
    })
    const snap = vm(wrapper).getSnapshot()
    // style 已剥（持久化不含会话态）；data 保留宽高
    expect(snap.nodes[0].data).toMatchObject({ width: 320, height: 120 })
    expect(snap.nodes[0].style).toBeUndefined()
    // 老节点：data 无宽高，快照原样（默认 200 只进会话 style，不写回 data）
    expect(snap.nodes[1].data).not.toHaveProperty('width')
    expect(snap.nodes[1].style).toBeUndefined()
  })

  it('getSnapshot：剥 wrapper style 只留 data（resizer 实时改写的 style 不入库）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({
      nodes: [{ id: 'n1', type: 'text', position: { x: 0, y: 0 }, data: { label: 'A', width: 320 } }],
      edges: []
    })
    // loadSnapshot 换的是 board 内部 ref，stub 拿到的 prop 要等重渲染才跟上
    await flushPromises()
    // 模拟 resizer 拖动中改写 style（updateStyle 路径）
    const flowNodes = (wrapper.getComponent(VueFlowStub).props('nodes') as { style?: Record<string, string> }[])
    expect(flowNodes).toHaveLength(1)
    flowNodes[0].style = { width: '999px', height: '88px' }
    const snap = vm(wrapper).getSnapshot()
    expect(snap.nodes[0].style).toBeUndefined()
    expect(snap.nodes[0].data).toMatchObject({ width: 320 })
  })

  it('addNode：新节点 style 默认宽 200；携带 width/height 的 data 沿用', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).addNode({ type: 'text', data: { label: '新', width: 260, height: 100 } })
    vm(wrapper).addNode({ type: 'text', data: { label: '默认' } })
    const flowNodes = (wrapper.getComponent(VueFlowStub).props('nodes') as { style?: Record<string, string>; data: Record<string, unknown> }[])
    expect(flowNodes[0].style).toEqual({ width: '260px', height: '100px' })
    expect(flowNodes[1].style).toEqual({ width: '200px' })
  })
})

/** 2x 五轮：撤回/重做——结构快照 50 步；同 tag <800ms 合并（脚本拆分镜批量=一步）；loadSnapshot 清栈开新时间线。 */
describe('CanvasBoard 撤回/重做', () => {
  type BoardVm = ReturnType<typeof boardVm> & {
    loadSnapshot: (s: { nodes: unknown[]; edges: unknown[] }) => void
    getSnapshot: () => { nodes: { id: string }[]; edges: { id: string }[] }
    addNode: (p: { type?: string; data?: Record<string, unknown> }) => string
    addEdge: (p: { source: string; target: string }) => string
    removeNodes: (ids: string[]) => void
    undo: () => void
    redo: () => void
    canUndo: boolean
    canRedo: boolean
  }
  const vm = (w: ReturnType<typeof mount>) => boardVm(w) as unknown as BoardVm

  it('新增→撤回→节点消失；重做→节点回来；栈空后再撤回无操作', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({ nodes: [{ id: 'n1', type: 'text', position: { x: 0, y: 0 }, data: { label: 'A' } }], edges: [] })
    const id = vm(wrapper).addNode({ type: 'text', data: { label: 'B' } })
    expect(vm(wrapper).getSnapshot().nodes).toHaveLength(2)
    expect(vm(wrapper).canUndo).toBe(true)

    vm(wrapper).undo()
    expect(vm(wrapper).getSnapshot().nodes.map(n => n.id)).toEqual(['n1'])
    expect(vm(wrapper).canRedo).toBe(true)

    vm(wrapper).redo()
    expect(vm(wrapper).getSnapshot().nodes.map(n => n.id)).toContain(id)

    // 撤干栈：undo 到底后 canUndo=false，不再抛错/变化
    vm(wrapper).undo()
    vm(wrapper).undo()
    expect(vm(wrapper).canUndo).toBe(false)
    expect(vm(wrapper).getSnapshot().nodes).toHaveLength(1)
  })

  it('同 tag <800ms 合并成一步：两次 addNode 一次撤回全消失（脚本拆分镜批量场景）', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).addNode({ type: 'text', data: { label: 'A' } })
    vm(wrapper).addNode({ type: 'text', data: { label: 'B' } })
    vm(wrapper).undo()
    expect(vm(wrapper).getSnapshot().nodes).toHaveLength(0)
  })

  it('删节点可撤回；连边可撤回（remove/edge 各自独立步）', () => {
    const wrapper = mount(CanvasBoard)
    const a = vm(wrapper).addNode({ type: 'text', data: { label: 'A' } })
    const b = vm(wrapper).addNode({ type: 'text', data: { label: 'B' } })
    vm(wrapper).addEdge({ source: a, target: b })
    vm(wrapper).removeNodes([b])
    expect(vm(wrapper).getSnapshot().nodes).toHaveLength(1)
    vm(wrapper).undo() // 撤 remove → B 回来
    expect(vm(wrapper).getSnapshot().nodes).toHaveLength(2)
    vm(wrapper).undo() // 撤 edge（与 remove 不同 tag 不同帧，各自成步）
    expect(vm(wrapper).getSnapshot().edges).toHaveLength(0)
  })

  it('loadSnapshot 清空两栈：外部载入=新时间线，之前历史不可再撤', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).addNode({ type: 'text', data: { label: 'A' } })
    expect(vm(wrapper).canUndo).toBe(true)
    vm(wrapper).loadSnapshot({ nodes: [], edges: [] })
    expect(vm(wrapper).canUndo).toBe(false)
    expect(vm(wrapper).canRedo).toBe(false)
  })

  it('工具条撤回/重做按钮随栈态禁用', async () => {
    const wrapper = mount(CanvasBoard)
    const undoBtn = wrapper.find('button[title^="撤回"]')
    const redoBtn = wrapper.find('button[title^="重做"]')
    expect(undoBtn.attributes('disabled')).toBeDefined()
    expect(redoBtn.attributes('disabled')).toBeDefined()
    vm(wrapper).addNode({ type: 'text', data: { label: 'A' } })
    await flushPromises()
    expect(undoBtn.attributes('disabled')).toBeUndefined()
  })
})

// 修复III C5（2x-5）：媒体节点完成定型统一 320×320 盒（收口 updateNodeData；手拉过则尊重）
describe('CanvasBoard · C5 媒体结果节点定型盒', () => {
  type BoardVm5 = ReturnType<typeof boardVm> & {
    loadSnapshot: (s: { nodes: unknown[]; edges: unknown[] }) => void
    getSnapshot: () => { nodes: { id: string; type: string; data: Record<string, unknown> }[] }
    updateNodeData: (id: string, patch: Record<string, unknown>) => void
  }
  const vm = (w: ReturnType<typeof mount>) => boardVm(w) as unknown as BoardVm5
  const img = (id: string, extra: Record<string, unknown> = {}) =>
    ({ id, type: 'image', position: { x: 0, y: 0 }, data: { label: '图', ...extra } })

  it('图片/视频完成且未手拉 → 定型 320×320（16:9 与 9:16 同盒）', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({ nodes: [img('i1'), { ...img('v1'), type: 'video' }], edges: [] })
    vm(wrapper).updateNodeData('i1', { status: 'success', previewUrl: 'blob:a' })
    vm(wrapper).updateNodeData('v1', { status: 'success', previewUrl: 'blob:v' })
    const nodes = vm(wrapper).getSnapshot().nodes
    expect(nodes.find(n => n.id === 'i1')!.data.width).toBe(320)
    expect(nodes.find(n => n.id === 'i1')!.data.height).toBe(320)
    expect(nodes.find(n => n.id === 'v1')!.data.height).toBe(320)
  })

  it('用户手拉过（data.height 已存在）→ 完成不覆盖手拉尺寸', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({ nodes: [img('i2', { width: 400, height: 260 })], edges: [] })
    vm(wrapper).updateNodeData('i2', { status: 'success' })
    const n = vm(wrapper).getSnapshot().nodes.find(x => x.id === 'i2')!
    expect(n.data.width).toBe(400)
    expect(n.data.height).toBe(260)
  })

  it('文本节点完成不定型（口径仅媒体）', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({ nodes: [{ id: 't1', type: 'text', position: { x: 0, y: 0 }, data: { label: 'T' } }], edges: [] })
    vm(wrapper).updateNodeData('t1', { status: 'success', outputText: '产出' })
    const n = vm(wrapper).getSnapshot().nodes.find(x => x.id === 't1')!
    expect(n.data.width).toBeUndefined()
    expect(n.data.height).toBeUndefined()
  })
})

/** 修复IV C1a/C2（C-4 缺口1 / C-7）：新增节点即报存 + 媒体节点新建即定型 320×320。 */
describe('CanvasBoard · 修复IV C1a/C2 新增链', () => {
  type BoardVm = ReturnType<typeof boardVm> & {
    addNode: (p: { type?: string; data?: Record<string, unknown> }) => string
    getSnapshot: () => { nodes: { id: string; type: string; data: Record<string, unknown> }[] }
  }
  const vm = (w: ReturnType<typeof mount>) => boardVm(w) as unknown as BoardVm

  it('C1a：addNode → emit structure-changed（三路新增统一进自动保存）', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).addNode({ type: 'text', data: { label: '新' } })
    const events = wrapper.emitted('structure-changed')
    expect(events?.length).toBeGreaterThanOrEqual(1)
  })

  it('C2：image/video 新建即预置 320×320（data+style 同拍）', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).addNode({ type: 'image', data: { label: '图' } })
    vm(wrapper).addNode({ type: 'video', data: { label: '视' } })
    const snap = vm(wrapper).getSnapshot()
    expect(snap.nodes[0].data).toMatchObject({ width: 320, height: 320 })
    expect(snap.nodes[1].data).toMatchObject({ width: 320, height: 320 })
  })

  it('C2：文本节点不预置（默认 200 自适应）；携带宽高的入口（副本/粘贴）不覆盖', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).addNode({ type: 'text', data: { label: 'T' } })
    vm(wrapper).addNode({ type: 'image', data: { label: '副本', width: 400, height: 260 } })
    const snap = vm(wrapper).getSnapshot()
    expect(snap.nodes[0].data.width).toBeUndefined()
    expect(snap.nodes[1].data).toMatchObject({ width: 400, height: 260 })
  })
})

// 修复VII（2x 增补②）：一键整理布局（VII-2）——全图/子图双模式、组整组拉入、单步历史。
describe('CanvasBoard · 一键整理布局（修复VII VII-2）', () => {
  type BoardVm7 = ReturnType<typeof boardVm> & {
    loadSnapshot: (s: { nodes: unknown[]; edges: unknown[]; groups?: unknown[] }) => void
    getSnapshot: () => { nodes: { id: string; position: { x: number; y: number } }[] }
    createGroup: (name: string, memberIds: string[]) => unknown
    undo: () => void
    canUndo: boolean
  }
  const vm = (w: ReturnType<typeof mount>) => boardVm(w) as unknown as BoardVm7
  /** A→B→C 链 + 一条自环：初始坐标故意打乱（整理应重排成 LR 序）。 */
  const chain = () => ({
    nodes: [
      { id: 'a', type: 'text', position: { x: 900, y: 20 }, data: { label: 'A' } },
      { id: 'b', type: 'text', position: { x: 40, y: 340 }, data: { label: 'B' } },
      { id: 'c', type: 'text', position: { x: 460, y: 660 }, data: { label: 'C' } }
    ],
    edges: [
      { id: 'e1', source: 'a', target: 'b' },
      { id: 'e2', source: 'b', target: 'c' },
      { id: 'e3', source: 'a', target: 'a' }
    ]
  })
  const posOf = (w: ReturnType<typeof mount>, id: string) =>
    vm(w).getSnapshot().nodes.find(n => n.id === id)!.position
  const layoutBtn = (w: ReturnType<typeof mount>) => w.find('button[title^="一键整理布局"]')
  const emissions = (w: ReturnType<typeof mount>) =>
    (w.emitted('structure-changed') ?? []).length

  it('① 全图整理：三节点重排为 LR 序（a.x+宽 ≤ b.x ≤ c.x），structure-changed 恰 1 次，可撤回', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(chain())
    await nextTick() // nodes.length 驱动的 disabled 解除要等重渲染
    const before = emissions(wrapper)
    expect(layoutBtn(wrapper).attributes('disabled')).toBeUndefined()
    await layoutBtn(wrapper).trigger('click')
    const pa = posOf(wrapper, 'a')
    const pb = posOf(wrapper, 'b')
    const pc = posOf(wrapper, 'c')
    expect(pa.x + 300).toBeLessThanOrEqual(pb.x) // text 默认宽 300（autoLayout 尺寸表）
    expect(pb.x + 300).toBeLessThanOrEqual(pc.x)
    expect(emissions(wrapper) - before).toBe(1)
    expect(vm(wrapper).canUndo).toBe(true)
  })

  it('② 撤回一步：三个旧坐标全部还原（整理=单历史步）', async () => {
    const wrapper = mount(CanvasBoard)
    const src = chain()
    vm(wrapper).loadSnapshot(src)
    await nextTick()
    const old = { a: { ...posOf(wrapper, 'a') }, b: { ...posOf(wrapper, 'b') }, c: { ...posOf(wrapper, 'c') } }
    await layoutBtn(wrapper).trigger('click')
    vm(wrapper).undo()
    expect(posOf(wrapper, 'a')).toEqual(old.a)
    expect(posOf(wrapper, 'b')).toEqual(old.b)
    expect(posOf(wrapper, 'c')).toEqual(old.c)
  })

  it('③ 框选 a、c 子图整理：只 a/c 动、b 原地不动（联动点 5）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(chain())
    await nextTick()
    const bBefore = { ...posOf(wrapper, 'b') }
    selState.nodes = [{ id: 'a' }, { id: 'c' }]
    wrapper.getComponent(VueFlowStub).vm.$emit('selection-end')
    await flushPromises()
    await layoutBtn(wrapper).trigger('click')
    expect(posOf(wrapper, 'b')).toEqual(bBefore)
    expect(posOf(wrapper, 'a').x).not.toBe(900)
  })

  it('④ 选中含组成员 → 整组拉入：选 b（与 c 同组）→ c 也被排，a 不动（联动点 7）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(chain())
    await nextTick()
    vm(wrapper).createGroup('G1', ['b', 'c'])
    const aBefore = { ...posOf(wrapper, 'a') }
    const cBefore = { ...posOf(wrapper, 'c') }
    selState.nodes = [{ id: 'b' }]
    wrapper.getComponent(VueFlowStub).vm.$emit('selection-end')
    await flushPromises()
    await layoutBtn(wrapper).trigger('click')
    expect(posOf(wrapper, 'a')).toEqual(aBefore)
    expect(posOf(wrapper, 'c').y).not.toBe(cBefore.y)
  })

  it('⑤ 无节点：按钮禁用（aria-disabled 联动）', () => {
    const wrapper = mount(CanvasBoard)
    expect(layoutBtn(wrapper).attributes('disabled')).toBeDefined()
    expect(layoutBtn(wrapper).attributes('aria-disabled')).toBe('true')
  })
})

// 修复VII（2x 增补①）：节点复制粘贴（VII-1）——多选子图/落点/单步历史/优先级链。
describe('CanvasBoard · 节点复制粘贴（修复VII VII-1）', () => {
  type BoardVm8 = ReturnType<typeof boardVm> & {
    loadSnapshot: (s: { nodes: unknown[]; edges: unknown[] }) => void
    getSnapshot: () => {
      nodes: { id: string; position: { x: number; y: number }; data: Record<string, unknown> }[]
      edges: { id: string; source: string; target: string }[]
    }
    undo: () => void
    canUndo: boolean
    clipboard: { items: unknown[]; pasteCount: number } | null
  }
  const vm = (w: ReturnType<typeof mount>) => boardVm(w) as unknown as BoardVm8
  const key = (k: string, ctrl = true) =>
    window.dispatchEvent(new KeyboardEvent('keydown', { key: k, ctrlKey: ctrl, bubbles: true }))
  /** 两节点一内边一外边（诱导边口径验证素材）。 */
  const graph = () => ({
    nodes: [
      { id: 'a', type: 'text', position: { x: 100, y: 100 }, data: { label: 'A' } },
      { id: 'b', type: 'text', position: { x: 500, y: 100 }, data: { label: 'B' } },
      { id: 'out', type: 'text', position: { x: 900, y: 100 }, data: { label: 'OUT' } }
    ],
    edges: [
      { id: 'e1', source: 'a', target: 'b' },
      { id: 'e2', source: 'b', target: 'out' }
    ]
  })
  const select = async (w: ReturnType<typeof mount>, ids: string[]) => {
    selState.nodes = ids.map(id => ({ id }))
    w.getComponent(VueFlowStub).vm.$emit('selection-end')
    await flushPromises()
  }

  it('① 框选 a、b Ctrl+C → 剪贴板 2 项 + emit nodes-copied(2)', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    await select(wrapper, ['a', 'b'])
    key('c')
    expect(vm(wrapper).clipboard).not.toBeNull()
    expect(vm(wrapper).clipboard!.items).toHaveLength(2)
    expect(wrapper.emitted('nodes-copied')?.[0]).toEqual([2])
  })

  it('② Ctrl+V → 节点+2、内边+1 端点重映射到新 id、外边不带；structure-changed 恰 1 次', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    await select(wrapper, ['a', 'b'])
    key('c')
    const before = (wrapper.emitted('structure-changed') ?? []).length
    key('v')
    const snap = vm(wrapper).getSnapshot()
    expect(snap.nodes).toHaveLength(5) // 3 原有 + 2 粘贴
    expect(snap.edges).toHaveLength(3) // 2 原有 + 1 内边克隆
    const newIds = snap.nodes.filter(n => !['a', 'b', 'out'].includes(n.id)).map(n => n.id)
    const cloned = snap.edges.filter(e => e.id !== 'e1' && e.id !== 'e2')[0]
    expect(newIds).toContain(cloned.source)
    expect(newIds).toContain(cloned.target)
    expect((wrapper.emitted('structure-changed') ?? []).length - before).toBe(1)
    expect(vm(wrapper).canUndo).toBe(true)
  })

  it('③ 连按两次 Ctrl+V：第二次整体 +32 错开（Q2）；label 逐次追加序号', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    await select(wrapper, ['a', 'b'])
    key('c')
    key('v')
    key('v')
    const snap = vm(wrapper).getSnapshot()
    expect(snap.nodes).toHaveLength(7)
    const clones = snap.nodes.filter(n => !['a', 'b', 'out'].includes(n.id))
    // 画布原有 A/B → 第一贴 A 2/B 2、第二贴 A 3/B 3（三级去重：现有+批内）
    expect(clones.map(n => String(n.data.label)).sort()).toEqual(['A 2', 'A 3', 'B 2', 'B 3'])
    // 无鼠标记录 → 落点=视口中心（rect 0,0 → 0,0）+ pasteCount*32；两贴同名节点 x 差 32
    const a1 = clones.find(n => n.data.label === 'A 2')!
    const a2 = clones.find(n => n.data.label === 'A 3')!
    expect(Math.abs(a1.position.x - a2.position.x)).toBe(32)
  })

  it('④ Ctrl+Z 一步整体撤：粘贴的节点+边全消', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    await select(wrapper, ['a', 'b'])
    key('c')
    key('v')
    vm(wrapper).undo()
    const snap = vm(wrapper).getSnapshot()
    expect(snap.nodes).toHaveLength(3)
    expect(snap.edges).toHaveLength(2)
  })

  it('⑤ 焦点在输入框 Ctrl+C/V 放行（不劫持正常文本复制粘贴）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    await select(wrapper, ['a', 'b'])
    key('c')
    expect(vm(wrapper).clipboard).not.toBeNull()
    const input = document.createElement('input')
    document.body.appendChild(input)
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'c', ctrlKey: true, bubbles: true }))
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'v', ctrlKey: true, bubbles: true }))
    // 剪贴板未清、未新粘贴（输入框内两键全放行）
    expect(vm(wrapper).clipboard).not.toBeNull()
    expect(vm(wrapper).getSnapshot().nodes).toHaveLength(3)
    input.remove()
  })

  it('⑥ 无选中 Ctrl+C → 清剪贴板（外部图片粘贴通道恢复）；再 Ctrl+V 不建节点', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    await select(wrapper, ['a', 'b'])
    key('c')
    expect(vm(wrapper).clipboard).not.toBeNull()
    // Esc 清多选后无选中（联动点 1 反向）
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    key('c')
    expect(vm(wrapper).clipboard).toBeNull()
    key('v')
    expect(vm(wrapper).getSnapshot().nodes).toHaveLength(3) // 原生链未拦、未建节点
  })

  it('⑦ 粘贴节点与原节点产物同显、任务/资产脱钩（buildCopySet 口径直查）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({
      nodes: [{
        id: 'a', type: 'image', position: { x: 0, y: 0 },
        data: { label: '主图', previewUrl: 'blob:x', fileId: 'f1', taskId: 't1', assetId: 7, status: 'success' }
      }],
      edges: []
    })
    await select(wrapper, ['a'])
    key('c')
    key('v')
    const pasted = vm(wrapper).getSnapshot().nodes.find(n => n.id !== 'a')!
    expect(pasted.data.previewUrl).toBe('blob:x')
    expect(pasted.data.taskId).toBeUndefined()
    expect(pasted.data.assetId).toBeUndefined()
    expect(pasted.data.status).toBe('success')
  })
})
