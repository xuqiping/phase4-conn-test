import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { nextTick } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { mount } from '@vue/test-utils'
import CanvasBoard from './CanvasBoard.vue'
import { keepLinksOnCopy, setKeepLinksOnCopy } from '@/utils/canvasPrefs'

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
// 修复VIII：onConnectEnd/组端口松手的坐标换算读 vueFlowRef.value.getBoundingClientRect——
// hoisted 可变源让用例按需挂假容器（默认 null 维持既有用例的回落分支口径）。
const vfState = vi.hoisted(() => ({
  el: null as null | { getBoundingClientRect: () => { left: number; top: number; width: number; height: number } }
}))
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
      vueFlowRef: {
        get value() { return vfState.el },
        set value(v: unknown) { vfState.el = v as typeof vfState.el }
      }
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
    // aria-pressed 按钮=模式×2 + S5「只看关联」×1 + IX-2「连线保留」×1（修复IX-2 新增 ⛓）
    const btns = wrapper.findAll('button[aria-pressed]')
    expect(btns).toHaveLength(4)
    expect(btns[2].attributes('disabled')).toBeDefined() // 只看关联：无节点选中禁用
    await btns[1].trigger('click')
    await wrapper.find('button[title^="拖拽画布模式"]').trigger('click')
    expect(boardVm(wrapper).dragMode).toBe('pan')
    expect(flowProps(wrapper).panOnDrag).toBe(true)
    expect(flowProps(wrapper).selectionKeyCode).toBe('Shift')
    const pressed = btns.map(b => b.attributes('aria-pressed'))
    expect(pressed).toEqual(['true', 'false', 'false', 'true']) // ⛓ 默认开
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

  it('② Ctrl+V → 节点+2、内边+1 端点重映射到新 id；structure-changed 恰 1 次', async () => {
    setKeepLinksOnCopy(false) // 修复IX-2：本用例锁「诱导边」口径（跨集边另测）
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

// 修复IX-2（2x 增补④，Q4 拍板）：连线保留总开关——一个开关治理「复制粘贴跨集边」与
// 「创建副本连线克隆」两处；开关=toolbar ⛓（canvasPrefs singleton，localStorage 持久化，默认开）。
describe('CanvasBoard · 连线保留开关（修复IX-2 Q4）', () => {
  type BoardVm10 = ReturnType<typeof boardVm> & {
    loadSnapshot: (s: { nodes: unknown[]; edges: unknown[] }) => void
    getSnapshot: () => {
      nodes: { id: string; position: { x: number; y: number }; data: Record<string, unknown> }[]
      edges: { id: string; source: string; target: string }[]
    }
    undo: () => void
    canUndo: boolean
    clipboard: { items: unknown[]; crossEdges: unknown[]; pasteCount: number } | null
  }
  const vm = (w: ReturnType<typeof mount>) => boardVm(w) as unknown as BoardVm10
  const key = (k: string, ctrl = true) =>
    window.dispatchEvent(new KeyboardEvent('keydown', { key: k, ctrlKey: ctrl, bubbles: true }))
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
  const copyAB = async (w: ReturnType<typeof mount>) => {
    await select(w, ['a', 'b'])
    key('c')
  }

  // 每用例显式定开关初值（singleton 跨用例存活，别靠默认）
  beforeEach(() => setKeepLinksOnCopy(true))

  it('① 开关开（默认）→ 粘贴带跨集边：集内端换新 id、集外端保原 id（b→out 克隆成 newB→out）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    await copyAB(wrapper)
    key('v')
    const snap = vm(wrapper).getSnapshot()
    expect(snap.edges).toHaveLength(4) // e1+e2 原有 + 内边克隆 + 跨集边克隆
    const newB = snap.nodes.filter(n => !['a', 'b', 'out'].includes(n.id))
      .find(n => String(n.data.label).startsWith('B'))!.id
    const cross = snap.edges.filter(e => e.id !== 'e1' && e.id !== 'e2')
      .find(e => e.target === 'out' || e.source === 'out')!
    expect(cross.source).toBe(newB)
    expect(cross.target).toBe('out')
  })

  it('② 平行重复边允许并存：跨集克隆 newB→out 与原 e2(b→out) 同存不去重', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    await copyAB(wrapper)
    key('v')
    const snap = vm(wrapper).getSnapshot()
    expect(snap.edges.filter(e => e.target === 'out').length).toBe(2)
  })

  it('③ 开关关 → 粘贴零跨集边（仅诱导边）；复制时点恒收集（剪贴板 crossEdges 仍在）', async () => {
    setKeepLinksOnCopy(false)
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    await copyAB(wrapper)
    expect(vm(wrapper).clipboard!.crossEdges).toHaveLength(1)
    key('v')
    const snap = vm(wrapper).getSnapshot()
    expect(snap.edges).toHaveLength(3) // e1+e2 + 仅内边克隆
    expect(snap.edges.filter(e => e.id !== 'e1' && e.id !== 'e2').every(
      e => e.source !== 'out' && e.target !== 'out')).toBe(true)
  })

  it('④ 粘贴时点判定：复制（开）→ 切关 → 粘贴不带跨集边；再切开再粘贴带', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    await copyAB(wrapper)
    setKeepLinksOnCopy(false)
    key('v')
    expect(vm(wrapper).getSnapshot().edges).toHaveLength(3)
    setKeepLinksOnCopy(true)
    key('v')
    // 第二贴：内边再 +1、跨集边再 +1（out 端共 3 条：e2 + 两贴各一）
    expect(vm(wrapper).getSnapshot().edges).toHaveLength(5)
  })

  it('⑤ 悬挂防护：复制后删掉集外节点 out → 开关开粘贴也丢跨集边（不产断边）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    await copyAB(wrapper)
    // 复制后 out 被删（连同其边 e2）——loadSnapshot 换成无 out 的图
    const g2 = graph()
    g2.nodes = g2.nodes.filter(n => n.id !== 'out')
    g2.edges = g2.edges.filter(e => e.source !== 'out' && e.target !== 'out')
    vm(wrapper).loadSnapshot(g2)
    key('v')
    const snap = vm(wrapper).getSnapshot()
    expect(snap.edges).toHaveLength(2) // 原有 e1 + 仅内边克隆（跨集边被悬挂防护丢弃）
    expect(snap.edges.every(e => e.source !== 'out' && e.target !== 'out')).toBe(true)
  })

  it('⑥ 工具条 ⛓ 按钮：aria-pressed 跟随开关、点击切换并持久化 localStorage', async () => {
    setKeepLinksOnCopy(true)
    const wrapper = mount(CanvasBoard)
    const btn = wrapper.findAll('button').find(b =>
      (b.attributes('aria-label') ?? '').includes('连线保留开关'))!
    expect(btn.attributes('aria-pressed')).toBe('true')
    await btn.trigger('click')
    expect(keepLinksOnCopy.value).toBe(false)
    expect(btn.attributes('aria-pressed')).toBe('false')
    expect(localStorage.getItem('canvas.keepLinksOnCopy')).toBe('false')
    await btn.trigger('click')
    expect(btn.attributes('aria-pressed')).toBe('true')
  })
})

// 修复X（2x 未解决③，X-3，Q3 拍板）：连线保留模式下组边随 ⛓ 保留——新节点连原组
// （组=外部对端，不入组员）；组解散丢边；appendEdges 混合批次分流。
describe('CanvasBoard · 组边随 ⛓ 保留（修复X X-3）', () => {
  type BoardVm11 = ReturnType<typeof boardVm> & {
    loadSnapshot: (s: { nodes?: unknown[]; edges?: unknown[]; groups?: unknown[] }) => void
    getSnapshot: () => {
      nodes: { id: string; data: Record<string, unknown> }[]
      edges: { id: string; source: string; target: string; class?: string }[]
    }
    getEdges: () => { id: string; source: string; target: string }[]
    getGroupEdges: () => { id: string; source: string; target: string; class?: string }[]
    appendEdges: (list: unknown[]) => void
    ungroupGroup: (id: string) => void
    undo: () => void
    clipboard: { crossEdges: unknown[] } | null
  }
  const vm = (w: ReturnType<typeof mount>) => boardVm(w) as unknown as BoardVm11
  const key = (k: string, ctrl = true) =>
    window.dispatchEvent(new KeyboardEvent('keydown', { key: k, ctrlKey: ctrl, bubbles: true }))
  const node = (id: string, x = 0, y = 0) => ({ id, type: 'text', position: { x, y }, data: { label: id } })
  /** b（组外节点）与组 g1 双向连：b→group:g1（聚合）+ group:g1→b（广播）。 */
  const graph = () => ({
    nodes: [node('m1'), node('m2', 50, 50), node('b', 600, 0)],
    edges: [
      { id: 'ge1', source: 'b', target: 'group:g1' },
      { id: 'ge2', source: 'group:g1', target: 'b' }
    ],
    groups: [{ id: 'g1', name: '组1', memberIds: ['m1', 'm2'], color: '#5b8def' }]
  })
  const selectB = async (w: ReturnType<typeof mount>) => {
    selState.nodes = [{ id: 'b' }]
    w.getComponent(VueFlowStub).vm.$emit('selection-end')
    await flushPromises()
  }

  beforeEach(() => setKeepLinksOnCopy(true))

  it('① 开关开：复制连组节点 b → 粘贴组边两向落组池（新节点连原组），v-model 零伪 id', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    expect(vm(wrapper).getGroupEdges()).toHaveLength(2)
    await selectB(wrapper)
    key('c')
    expect(vm(wrapper).clipboard!.crossEdges).toHaveLength(2)
    key('v')
    const ge = vm(wrapper).getGroupEdges()
    expect(ge).toHaveLength(4) // 原 ge1/ge2 + 两向克隆
    const newB = vm(wrapper).getSnapshot().nodes.find(n => n.data.label === String('b 2'))!.id
    expect(ge.some(e => e.source === newB && e.target === 'group:g1')).toBe(true) // 聚合向
    expect(ge.some(e => e.source === 'group:g1' && e.target === newB)).toBe(true) // 广播向
    expect(vm(wrapper).getEdges()).toHaveLength(0) // 伪 id 绝不进 v-model
    // 新节点不入组员（组=外部对端口径）
    expect(vm(wrapper).getSnapshot().nodes).toHaveLength(4) // m1/m2/b/newB
  })

  it('② 开关关：粘贴零组边（组池维持原 2 条），复制时点恒收集不变', async () => {
    setKeepLinksOnCopy(false)
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    await selectB(wrapper)
    key('c')
    key('v')
    expect(vm(wrapper).getGroupEdges()).toHaveLength(2)
    expect(vm(wrapper).getSnapshot().nodes).toHaveLength(4) // 节点照贴，边不带
  })

  it('③ 复制后解散组再粘贴 → 组边被悬挂防护丢弃（不产断边）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    await selectB(wrapper)
    key('c')
    vm(wrapper).ungroupGroup('g1') // 解散级联清组边（VIII-1 ⑦）
    expect(vm(wrapper).getGroupEdges()).toHaveLength(0)
    key('v')
    expect(vm(wrapper).getGroupEdges()).toHaveLength(0) // aliveGroups 空 → 两向全丢
  })

  it('④ Ctrl+Z 一步撤：粘贴的组边随快照齐消（组池回 2）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    await selectB(wrapper)
    key('c')
    key('v')
    expect(vm(wrapper).getGroupEdges()).toHaveLength(4)
    vm(wrapper).undo()
    expect(vm(wrapper).getGroupEdges()).toHaveLength(2)
  })

  it('⑤ appendEdges 混合批次：普通边进 v-model、组边进组池且剥会话 class（副本链）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(graph())
    const before = (wrapper.emitted('structure-changed') ?? []).length
    vm(wrapper).appendEdges([
      { id: 'x1', source: 'm1', target: 'm2', type: 'deletable' },
      { id: 'x2', source: 'b', target: 'group:g1', type: 'deletable', class: 'canvas-edge--selected' }
    ])
    expect(vm(wrapper).getEdges().map(e => e.id)).toEqual(['x1'])
    const ge = vm(wrapper).getGroupEdges()
    expect(ge).toHaveLength(3)
    const added = ge.find(e => e.id !== 'ge1' && e.id !== 'ge2')!
    expect(added.target).toBe('group:g1')
    expect(added.class).toBeUndefined() // 剥会话 class（loadSnapshot 同口径）
    expect((wrapper.emitted('structure-changed') ?? []).length - before).toBe(1)
  })
})

// 修复VIII（2x 增补：组整体拉线 + 本体松手直连）——组边数据层接线/级联/撤回/v-model 隔离/连接手势分派。
describe('CanvasBoard · 组边与本体直连（修复VIII VIII-1/2）', () => {
  type BoardVm9 = ReturnType<typeof boardVm> & {
    loadSnapshot: (s: { nodes?: unknown[]; edges?: unknown[]; groups?: unknown[] }) => void
    getSnapshot: () => { edges: { id: string; source: string; target: string }[]; groups?: { id: string; memberIds: string[] }[] }
    createGroup: (name: string, ids: string[]) => { ok: boolean }
    ungroupGroup: (id: string) => void
    getGroups: () => { id: string; memberIds: string[] }[]
    getGroupEdges: () => { id: string; source: string; target: string }[]
    addEdge: (s: string, t: string) => void
    removeNodes: (ids: string[]) => void
    undo: () => void
    canUndo: boolean
  }
  const vm = (w: ReturnType<typeof mount>) => boardVm(w) as unknown as BoardVm9
  const node = (id: string, x = 0, y = 0) => ({ id, type: 'text', position: { x, y }, data: { label: id } })
  const group = (id: string, memberIds: string[]) => ({ id, name: id, memberIds, color: '#5b8def' })
  /** v-model prop 须等重渲染落定，读前先 flush 一帧。 */
  const flowEdgesOf = async (w: ReturnType<typeof mount>) => {
    await nextTick()
    return w.getComponent(VueFlowStub).props('edges') as { id: string; source: string; target: string }[]
  }
  /** 挂假 vue-flow 容器（project mock=identity → flowPos=clientXY）并返回还原函数，防跨用例泄漏。 */
  function withFakeVf() {
    const prev = vfState.el
    vfState.el = { getBoundingClientRect: () => ({ left: 0, top: 0, width: 1000, height: 800 }) }
    return () => { vfState.el = prev }
  }
  /** 真事件实例（clientX/Y+target 俱全）：在 el 上 dispatch 一次让 target 落定，再交 stub emit。 */
  function pointerEventOn(el: HTMLElement, clientX: number, clientY: number): MouseEvent {
    const ev = new MouseEvent('pointerup', { clientX, clientY, bubbles: false })
    el.dispatchEvent(ev)
    return ev
  }
  const nodeEl = (id: string) => {
    const el = document.createElement('div')
    el.className = 'vue-flow__node'
    el.dataset.id = id
    document.body.appendChild(el)
    return el
  }
  const paneEl = () => {
    const el = document.createElement('div')
    document.body.appendChild(el)
    return el
  }
  /** 组包围盒（画布坐标）：m1(0,0)+m2(50,50) 默认 200×120 → rect=[-12,-12,262,182]。 */
  const groupedSnap = () => ({
    nodes: [node('m1'), node('m2', 50, 50), node('ext', 600, 0)],
    edges: [],
    groups: [group('g1', ['m1', 'm2'])]
  })
  const rafFlush = () => new Promise<void>(r => requestAnimationFrame(() => r()))

  it('① 快照往返：载入含组边快照 → v-model 只有普通边（坑 1 零伪 id）、getSnapshot 合并还原全量', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({
      nodes: [node('a'), node('b'), node('c', 400, 0)],
      edges: [
        { id: 'e1', source: 'a', target: 'b' },
        { id: 'ge1', source: 'group:g1', target: 'c' }
      ],
      groups: [group('g1', ['a', 'b'])]
    })
    expect((await flowEdgesOf(wrapper)).map(e => e.id)).toEqual(['e1'])
    expect(vm(wrapper).getGroupEdges().map(e => e.id)).toEqual(['ge1'])
    const snap = vm(wrapper).getSnapshot()
    expect(snap.edges.map(e => e.id).sort()).toEqual(['e1', 'ge1']) // 合并落库
    expect(snap.groups).toHaveLength(1)
  })

  it('② 解散组 → 该组组边级联删；其它组组边保留（VIII-1 ⑦）', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({
      nodes: [node('a'), node('b'), node('c'), node('d'), node('ext', 800, 0)],
      groups: [group('g1', ['a', 'b']), group('g2', ['c', 'd'])],
      edges: []
    })
    vm(wrapper).addEdge('group:g1', 'ext')
    vm(wrapper).addEdge('group:g2', 'ext')
    expect(vm(wrapper).getGroupEdges()).toHaveLength(2)
    vm(wrapper).ungroupGroup('g1')
    const ge = vm(wrapper).getGroupEdges()
    expect(ge).toHaveLength(1)
    expect(ge[0].source).toBe('group:g2')
    expect(vm(wrapper).getSnapshot().edges.every(e => e.source !== 'group:g1')).toBe(true)
  })

  it('③ 删对端节点 → 其名下组边级联删；同组其他组边不动（VIII-1 ⑦）', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({
      nodes: [node('a'), node('b'), node('c', 400, 0), node('d', 600, 0)],
      groups: [group('g1', ['a', 'b'])],
      edges: []
    })
    vm(wrapper).addEdge('group:g1', 'c')
    vm(wrapper).addEdge('group:g1', 'd')
    vm(wrapper).removeNodes(['c'])
    expect(vm(wrapper).getGroupEdges().map(e => e.target)).toEqual(['d'])
  })

  it('④ 组成员全删 → 组自解散 + 组边级联删（联动点 1）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({ ...groupedSnap(), edges: [] })
    vm(wrapper).addEdge('group:g1', 'ext')
    expect(vm(wrapper).getGroupEdges()).toHaveLength(1)
    vm(wrapper).removeNodes(['m1', 'm2'])
    await flushPromises()
    expect(vm(wrapper).getGroups()).toHaveLength(0)
    expect(vm(wrapper).getGroupEdges()).toHaveLength(0)
  })

  it('⑤ Ctrl+Z 撤回解散组 → 组+组边随快照齐恢复（坑 10 合并口径）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({ ...groupedSnap(), edges: [] })
    expect(vm(wrapper).createGroup('G', ['m1', 'm2']).ok).toBe(true)
    const gid = vm(wrapper).getGroups()[0].id
    vm(wrapper).addEdge(`group:${gid}`, 'ext')
    expect(vm(wrapper).getGroupEdges()).toHaveLength(1)
    vm(wrapper).ungroupGroup(gid)
    expect(vm(wrapper).getGroupEdges()).toHaveLength(0)
    vm(wrapper).undo()
    expect(vm(wrapper).getGroups()).toHaveLength(1)
    expect(vm(wrapper).getGroupEdges()).toHaveLength(1)
  })

  it('⑥ 拖拽 onConnect 补校验：自环拒、同向重复拒（VIII-2 顺带收口）', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({ nodes: [node('a'), node('b')], edges: [] })
    const vf = wrapper.getComponent(VueFlowStub)
    vf.vm.$emit('connect', { source: 'a', target: 'a' })
    vf.vm.$emit('connect', { source: 'a', target: 'b' })
    vf.vm.$emit('connect', { source: 'a', target: 'b' })
    const edges = vm(wrapper).getSnapshot().edges
    expect(edges).toHaveLength(1)
    expect(edges[0]).toMatchObject({ source: 'a', target: 'b' })
  })

  it('⑦ 本体松手直连（VIII-2）：source 起拖落 B 本体→a→b；target 起拖→b→a；落自身静默不弹窗', () => {
    const restore = withFakeVf()
    try {
      const wrapper = mount(CanvasBoard)
      vm(wrapper).loadSnapshot({ nodes: [node('a'), node('b')], edges: [] })
      const vf = wrapper.getComponent(VueFlowStub)
      const bEl = nodeEl('b')
      const aEl = nodeEl('a')
      const fire = (start: { nodeId: string; handleType: string }, el: HTMLElement, x = 1, y = 1) => {
        vf.vm.$emit('connect-start', start)
        vf.vm.$emit('connect-end', pointerEventOn(el, x, y))
      }
      fire({ nodeId: 'a', handleType: 'source' }, bEl)
      fire({ nodeId: 'a', handleType: 'target' }, bEl)
      fire({ nodeId: 'a', handleType: 'source' }, aEl) // 落自身：静默
      const edges = vm(wrapper).getSnapshot().edges
      expect(edges).toHaveLength(2)
      expect(edges.find(e => e.source === 'a' && e.target === 'b')).toBeTruthy()
      expect(edges.find(e => e.source === 'b' && e.target === 'a')).toBeTruthy()
      expect(wrapper.emitted('quick-add')).toBeUndefined() // 落自身不触发 quick-add
    } finally {
      restore()
    }
  })

  it('⑧ 外部→组：source 句柄拖线落组包围盒空白 → 组边；重复落点同向去重；target 起拖落组不建', async () => {
    const restore = withFakeVf()
    try {
      const wrapper = mount(CanvasBoard)
      vm(wrapper).loadSnapshot({ ...groupedSnap(), edges: [] })
      const vf = wrapper.getComponent(VueFlowStub)
      const pane = paneEl() // 组框体穿透 → target 是 pane，坐标 ∩ 判定（坑 3）
      const fire = (handleType: string, x: number, y: number) => {
        vf.vm.$emit('connect-start', { nodeId: 'ext', handleType })
        vf.vm.$emit('connect-end', pointerEventOn(pane, x, y))
      }
      fire('source', 10, 10) // (10,10) ∈ g1 rect [-12,-12,262,182]
      fire('source', 10, 10) // 同向去重
      expect(vm(wrapper).getGroupEdges()).toHaveLength(1)
      expect(vm(wrapper).getGroupEdges()[0]).toMatchObject({ source: 'ext', target: 'group:g1' })
      expect(await flowEdgesOf(wrapper)).toHaveLength(0) // 不进 v-model
      fire('target', 10, 10) // target 句柄落组：不建反向边
      expect(vm(wrapper).getGroupEdges()).toHaveLength(1)
    } finally {
      restore()
    }
  })

  it('⑨ 落空白（组外）→ quick-add 现状不变（携起点 id，2x-6 原语义）', () => {
    const restore = withFakeVf()
    try {
      const wrapper = mount(CanvasBoard)
      vm(wrapper).loadSnapshot({ ...groupedSnap(), edges: [] })
      const vf = wrapper.getComponent(VueFlowStub)
      vf.vm.$emit('connect-start', { nodeId: 'ext', handleType: 'source' })
      vf.vm.$emit('connect-end', pointerEventOn(paneEl(), 999, 999))
      expect(wrapper.emitted('quick-add')).toEqual([[{ x: 999, y: 999 }, 'ext']])
    } finally {
      restore()
    }
  })

  it('⑩ 程序化 addEdge 伪 id 端点 → 入组边集合（quick-add 组→新节点链）+ 同向去重；v-model 零伪 id', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({ ...groupedSnap(), edges: [] })
    vm(wrapper).addEdge('group:g1', 'ext')
    vm(wrapper).addEdge('group:g1', 'ext')
    expect(vm(wrapper).getGroupEdges()).toHaveLength(1)
    expect(await flowEdgesOf(wrapper)).toHaveLength(0)
  })

  it('⑪ 组端口渲染（source/target 各一）+ 组边 SVG 路径 + 中点 × 删除落库（VIII-1 ①⑤）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({ ...groupedSnap(), edges: [] })
    await nextTick()      // watch → scheduleGroupBounds
    await rafFlush()      // rAF 合帧算包围盒/组边几何
    await nextTick()      // 重渲染
    const ports = wrapper.findAll('.canvas-board__groupbox-port')
    expect(ports).toHaveLength(2)
    expect(ports.filter(p => p.classes().includes('canvas-board__groupbox-port--source'))).toHaveLength(1)
    expect(ports.filter(p => p.classes().includes('canvas-board__groupbox-port--target'))).toHaveLength(1)

    vm(wrapper).addEdge('group:g1', 'ext')
    await nextTick()
    await rafFlush()
    await nextTick()
    expect(wrapper.findAll('.canvas-board__groupedge-path')).toHaveLength(1)

    const before = (wrapper.emitted('structure-changed') ?? []).length
    await wrapper.find('.canvas-board__groupedge-del').trigger('click')
    expect(vm(wrapper).getGroupEdges()).toHaveLength(0)
    expect((wrapper.emitted('structure-changed') ?? []).length - before).toBe(1)
  })

  // 修复VIII P4 人工反馈：组→下游节点组边存在时，点下游节点——组成员不得被误判无关而半透明
  // （闭包输入须含组边展开集，group:{id} 伪端点普通 BFS 摸不到）；无关节点仍正常变暗。
  it('⑫ 点组边下游节点 → 组成员不半透明（闭包含组边展开），无关节点仍变暗', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({
      ...groupedSnap(),
      nodes: [...groupedSnap().nodes, node('far', 900, 400)] // 无关节点=反例锚点
    })
    vm(wrapper).addEdge('group:g1', 'ext') // 聚合组边：m1/m2 → ext
    await flushPromises()
    wrapper.getComponent(VueFlowStub).vm.$emit('node-click', { node: { id: 'ext' } })
    await flushPromises()
    await nextTick() // watch(relatedInfo) → applyVisualClasses 重渲染
    const cls = (wrapper.getComponent(VueFlowStub).props('nodes') as { id: string; class: string }[])
      .reduce<Record<string, string>>((m, n) => (m[n.id] = n.class ?? '', m), {})
    expect(cls.m1).not.toContain('canvas-node--dimmed')
    expect(cls.m2).not.toContain('canvas-node--dimmed')
    expect(cls.ext).not.toContain('canvas-node--dimmed')
    expect(cls.far).toContain('canvas-node--dimmed') // 普通边 id 直通口径下无关边/节点照常暗
  })
})

/** 修复XI A2（2x 未解决①，spec XI-1）：画布空白右键菜单——判定/两组项/落点/接线/Esc 逐层退。 */
describe('CanvasBoard · 画布右键菜单（修复XI A2）', () => {
  type BoardVmXI = ReturnType<typeof boardVm> & {
    loadSnapshot: (s: { nodes: unknown[]; edges: unknown[] }) => void
    getSnapshot: () => { nodes: { id: string; position: { x: number; y: number } }[] }
    clipboard: { items: unknown[]; pasteCount: number; bbox: { left: number; top: number; width: number; height: number } } | null
    canUndo: boolean
  }
  const vm = (w: ReturnType<typeof mount>) => boardVm(w) as unknown as BoardVmXI
  const key = (k: string, ctrl = true) =>
    window.dispatchEvent(new KeyboardEvent('keydown', { key: k, ctrlKey: ctrl, bubbles: true }))
  /** 假容器：project 为 identity mock，rect 全零 → flow 坐标 == client 坐标（落点可精确断言）。 */
  const fakeEl = () => ({ getBoundingClientRect: () => ({ left: 0, top: 0, width: 800, height: 600 }) })
  /** 在根内挂一个指定 class 的真实子元素再派发原生 contextmenu（target.closest 走真实 DOM）。 */
  const rightClickOnClass = (w: ReturnType<typeof mount>, cls: string, x = 300, y = 220) => {
    const el = document.createElement('div')
    el.className = cls
    w.element.appendChild(el)
    el.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: x, clientY: y }))
    return el
  }
  const openAt = async (w: ReturnType<typeof mount>, x = 300, y = 220) => {
    await w.find('.canvas-board').trigger('contextmenu', { clientX: x, clientY: y })
  }
  const menuItems = (w: ReturnType<typeof mount>) => w.findAll('button[role="menuitem"]')

  beforeEach(() => { vfState.el = fakeEl() })
  afterEach(() => { vfState.el = null })

  it('① 空白右键开菜单：role=menu + 两组 11 项（添加节点 7 类 + 粘贴/撤销/重做/整理）；默认粘贴/撤销/重做/整理全禁用', async () => {
    const wrapper = mount(CanvasBoard)
    await openAt(wrapper, 300, 220)
    const menu = wrapper.find('.canvas-board__ctx-menu')
    expect(menu.exists()).toBe(true)
    expect(menu.attributes('role')).toBe('menu')
    expect(menuItems(wrapper)).toHaveLength(11)
    expect(menuItems(wrapper)[0].text()).toContain('文本')
    // 空画布：粘贴（无剪贴板）/撤销重做（无历史）/整理（无节点）全 disabled
    for (const i of [7, 8, 9, 10]) {
      expect(menuItems(wrapper)[i].attributes('disabled')).toBeDefined()
      expect(menuItems(wrapper)[i].attributes('aria-disabled')).toBe('true')
    }
  })

  it('② 点节点类型项：节点落在右键点 flow 坐标（project identity=client 坐标直通）+菜单即关', async () => {
    const wrapper = mount(CanvasBoard)
    await openAt(wrapper, 300, 220)
    await menuItems(wrapper)[0].trigger('click') // 文本
    expect(wrapper.find('.canvas-board__ctx-menu').exists()).toBe(false)
    const snap = vm(wrapper).getSnapshot()
    expect(snap.nodes).toHaveLength(1)
    expect(snap.nodes[0].position).toEqual({ x: 300, y: 220 })
  })

  it('③ 节点/普通边/工具条/组框右键：拦默认菜单但不开自绘菜单（节点右键=存资产库链不叠加；组框分支细化1 先立）', () => {
    const wrapper = mount(CanvasBoard)
    for (const cls of ['vue-flow__node', 'vue-flow__edge', 'canvas-board__toolbar', 'canvas-board__groupbox']) {
      const el = rightClickOnClass(wrapper, cls)
      expect(wrapper.find('.canvas-board__ctx-menu').exists()).toBe(false)
      expect(el.dataset).toBeDefined() // 占位断言：每类都走到
    }
  })

  it('④ 粘贴项=Ctrl+V 同链且落点强制右键点：单节点剪贴板粘贴 → 新节点恰在 (300,220)', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({ nodes: [{ id: 'n1', type: 'text', position: { x: 50, y: 50 }, data: { label: 'A' } }], edges: [] })
    selState.nodes = [{ id: 'n1' }]
    wrapper.getComponent(VueFlowStub).vm.$emit('selection-end')
    await flushPromises()
    key('c')
    expect(vm(wrapper).clipboard).not.toBeNull()
    await openAt(wrapper, 300, 220)
    expect(menuItems(wrapper)[7].attributes('disabled')).toBeUndefined() // 有剪贴板 → 可点
    await menuItems(wrapper)[7].trigger('click')
    expect(wrapper.find('.canvas-board__ctx-menu').exists()).toBe(false)
    const snap = vm(wrapper).getSnapshot()
    expect(snap.nodes).toHaveLength(2)
    expect(snap.nodes.find(n => n.id !== 'n1')!.position).toEqual((() => {
      // 落点契约=planPastePositions 同式：右键点为 bbox 中心锚（非左上角），snap 16 网格
      const clip = vm(wrapper).clipboard!
      const snap16 = (v: number) => Math.round(v / 16) * 16
      return {
        x: snap16(50 + 300 - (clip.bbox.left + clip.bbox.width / 2)),
        y: snap16(50 + 220 - (clip.bbox.top + clip.bbox.height / 2))
      }
    })())
  })

  it('⑤ 撤销/整理接线：添加后撤销可用（撤回到空）、整理随之可用——disabled 态实时跟随', async () => {
    const wrapper = mount(CanvasBoard)
    await openAt(wrapper, 100, 100)
    await menuItems(wrapper)[0].trigger('click') // 添加「文本」
    await openAt(wrapper, 120, 120)
    expect(menuItems(wrapper)[8].attributes('disabled')).toBeUndefined() // 撤销可点
    expect(menuItems(wrapper)[10].attributes('disabled')).toBeUndefined() // 整理可点
    await menuItems(wrapper)[8].trigger('click') // 撤销
    expect(vm(wrapper).getSnapshot().nodes).toHaveLength(0)
    expect(vm(wrapper).canUndo).toBe(false)
  })

  it('⑥ Esc 逐层退：开着只关菜单不外传（外层 document 监听收不到）；关了以后 Esc 放行', async () => {
    const wrapper = mount(CanvasBoard)
    await openAt(wrapper, 300, 220)
    const outerHits: number[] = []
    const outer = () => outerHits.push(1)
    document.addEventListener('keydown', outer)
    // 真实传播路径：body 派发冒泡 → window 捕获层先拦（stopPropagation）→ document 监听不触发
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await nextTick() // v-if 卸载是异步渲染，等一拍再断 DOM
    expect(wrapper.find('.canvas-board__ctx-menu').exists()).toBe(false)
    expect(outerHits).toHaveLength(0)
    // 菜单已关：监听已摘，同事件外层照常收到（不再吞 Esc）
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    expect(outerHits).toHaveLength(1)
    document.removeEventListener('keydown', outer)
  })

  it('⑦ 菜单开着再右键空白=挪位置不叠两层；左键点 overlay 他处=关', async () => {
    const wrapper = mount(CanvasBoard)
    await openAt(wrapper, 300, 220)
    // 右键打在 overlay 上：拦默认后冒泡到根 handler → 坐标覆写=挪位置（spec ⑦）
    await wrapper.find('.canvas-board__ctx-overlay').trigger('contextmenu', { clientX: 500, clientY: 400 })
    expect(wrapper.find('.canvas-board__ctx-menu').exists()).toBe(true)
    expect(wrapper.find('.canvas-board__ctx-menu').attributes('style')).toContain('left: 500px')
    await wrapper.find('.canvas-board__ctx-overlay').trigger('click')
    expect(wrapper.find('.canvas-board__ctx-menu').exists()).toBe(false)
  })

  it('⑧ 卸载兜底：菜单开着卸载组件 → window 捕获监听已摘，Esc 不再被吞（修复X P4 教训）', async () => {
    const wrapper = mount(CanvasBoard)
    await openAt(wrapper, 300, 220)
    wrapper.unmount()
    const outerHits: number[] = []
    const outer = () => outerHits.push(1)
    document.addEventListener('keydown', outer)
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    expect(outerHits).toHaveLength(1)
    document.removeEventListener('keydown', outer)
  })
})

/** 修复XI B3（spec XI-2⑤ 细化4）：官方库插入链落点与失败回滚。 */
describe('CanvasBoard · 官方库插入链（修复XI B3）', () => {
  type BoardVmB3 = ReturnType<typeof boardVm> & {
    loadSnapshot: (s: { nodes: unknown[]; edges: unknown[] }) => void
    getSnapshot: () => { nodes: { id: string; position: { x: number; y: number } }[]; edges: { id: string }[] }
    addNodeAtCenter: (p: { type?: string; data?: Record<string, unknown> }) => string
    abortNodeAdd: (id: string) => void
    addEdge: (source: string, target: string) => string
    canUndo: boolean
    undo: () => void
  }
  const vm = (w: ReturnType<typeof mount>) => boardVm(w) as unknown as BoardVmB3

  beforeEach(() => {
    vfState.el = { getBoundingClientRect: () => ({ left: 40, top: 60, width: 800, height: 600 }) }
  })
  afterEach(() => { vfState.el = null })

  it('① addNodeAtCenter：落点=视口中心（rect 中心换算，project identity），返回新 id 可 getNode', () => {
    const wrapper = mount(CanvasBoard)
    const id = vm(wrapper).addNodeAtCenter({ type: 'image', data: { label: '官方资产' } })
    const snap = vm(wrapper).getSnapshot()
    expect(snap.nodes).toHaveLength(1)
    expect(id).toBe(snap.nodes[0].id)
    expect(snap.nodes[0].position).toEqual({ x: 400, y: 300 }) // width/2, height/2（project identity）
  })

  it('② abortNodeAdd：静默删节点、不入撤销栈且弹出该次 add 历史步（undo 链无空步）', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({ nodes: [{ id: 'keep', type: 'text', position: { x: 0, y: 0 }, data: { label: 'K' } }], edges: [] })
    const id = vm(wrapper).addNodeAtCenter({ type: 'text', data: { label: '官方' } })
    expect(vm(wrapper).canUndo).toBe(true) // add 的历史步在
    const before = (wrapper.emitted('structure-changed') ?? []).length

    vm(wrapper).abortNodeAdd(id)

    const snap = vm(wrapper).getSnapshot()
    expect(snap.nodes.map((n) => n.id)).toEqual(['keep']) // 节点删净
    expect(vm(wrapper).canUndo).toBe(false) // 该次 add 步被弹出（loadSnapshot 清栈后无其他历史）
    expect((wrapper.emitted('structure-changed') ?? []).length - before).toBe(1) // 落库照发防残留
  })

  it('③ abortNodeAdd 连带边：与被删节点相连的边一并清（快照态边，无历史步参与）', () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot({
      nodes: [
        { id: 'keep', type: 'text', position: { x: 0, y: 0 }, data: { label: 'K' } },
        { id: 'victim', type: 'text', position: { x: 9, y: 9 }, data: { label: 'V' } }
      ],
      edges: [{ id: 'e1', source: 'victim', target: 'keep' }]
    })
    vm(wrapper).abortNodeAdd('victim')
    const snap = vm(wrapper).getSnapshot()
    expect(snap.nodes.map((n) => n.id)).toEqual(['keep'])
    expect(snap.edges).toHaveLength(0)
  })
})

// 修复XI（2x 未解决④，D1）：组框点选分层——点组框空白=选整组（高亮），与节点选中/框选/Esc
// 互斥；Delete 组选态无动作（Q5 拍板：组选只接拖动+复制，不接删除）。
describe('CanvasBoard · 组框点选分层（修复XI D1）', () => {
  type BoardVm12 = ReturnType<typeof boardVm> & {
    loadSnapshot: (s: { nodes?: unknown[]; edges?: unknown[]; groups?: unknown[] }) => void
    getSnapshot: () => { nodes: { id: string }[] }
  }
  const vm = (w: ReturnType<typeof mount>) => boardVm(w) as unknown as BoardVm12
  const node = (id: string, x = 0, y = 0) => ({ id, type: 'text', position: { x, y }, data: { label: id } })
  const groupedSnap = () => ({
    nodes: [node('m1'), node('m2', 50, 50), node('ext', 600, 0)],
    edges: [],
    groups: [{ id: 'g1', name: '组1', memberIds: ['m1', 'm2'], color: '#5b8def' }]
  })
  const rafFlush = () => new Promise<void>(r => requestAnimationFrame(() => r()))
  const key = (k: string) =>
    window.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }))

  /** 组框空白点击的全链模拟：pane div 挂进 boardRoot（捕获监听可达）→ pointerdown → pointerup → pane-click。
   * pointerup 必发（D2 起 pointerdown 开拖动会话，不收尾会跨用例泄漏 window 监听）。 */
  const clickGroupBlank = async (w: ReturnType<typeof mount>, x = 10, y = 10) => {
    const pane = document.createElement('div')
    pane.className = 'vue-flow__pane'
    w.element.appendChild(pane)
    pane.dispatchEvent(new MouseEvent('pointerdown', { clientX: x, clientY: y, bubbles: true, button: 0 }))
    window.dispatchEvent(new MouseEvent('pointerup'))
    w.getComponent(VueFlowStub).vm.$emit('pane-click')
    pane.remove()
    await nextTick()
  }
  const boxClass = (w: ReturnType<typeof mount>) => w.find('.canvas-board__groupbox')

  it('① 点组框空白（pane 落点在包围盒内）→ 选中整组高亮 + 属性面板清空', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(groupedSnap())
    // watch(pre) 微任务先于 rAF 排帧：先 flush 让 scheduleGroupBounds 挂上 rAF，再等帧
    await flushPromises()
    await rafFlush()
    expect(boxClass(wrapper).exists()).toBe(true)
    await clickGroupBlank(wrapper)
    expect(boxClass(wrapper).classes()).toContain('canvas-board__groupbox--selected')
    const emits = wrapper.emitted('node-selected')!
    expect(emits[emits.length - 1][0]).toBeNull()
  })

  it('② 点组外空白 → 不选组且组选清（既有清选中链不变）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(groupedSnap())
    // watch(pre) 微任务先于 rAF 排帧：先 flush 让 scheduleGroupBounds 挂上 rAF，再等帧
    await flushPromises()
    await rafFlush()
    await clickGroupBlank(wrapper) // 先选中
    expect(boxClass(wrapper).classes()).toContain('canvas-board__groupbox--selected')
    await clickGroupBlank(wrapper, 900, 500) // ext(600,0) 外远点，不在任何包围盒
    expect(boxClass(wrapper).classes()).not.toContain('canvas-board__groupbox--selected')
  })

  it('③ 点成员节点 → 组选清 + 该节点单选（现状链反清，互斥）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(groupedSnap())
    // watch(pre) 微任务先于 rAF 排帧：先 flush 让 scheduleGroupBounds 挂上 rAF，再等帧
    await flushPromises()
    await rafFlush()
    await clickGroupBlank(wrapper)
    wrapper.getComponent(VueFlowStub).vm.$emit('node-click', { node: { id: 'm1' } })
    await nextTick()
    expect(boxClass(wrapper).classes()).not.toContain('canvas-board__groupbox--selected')
    const emits = wrapper.emitted('node-selected')!
    expect((emits[emits.length - 1][0] as { id: string }).id).toBe('m1')
  })

  it('④ Esc / 框选起手 → 组选清（不吞事件、非模态）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(groupedSnap())
    // watch(pre) 微任务先于 rAF 排帧：先 flush 让 scheduleGroupBounds 挂上 rAF，再等帧
    await flushPromises()
    await rafFlush()
    await clickGroupBlank(wrapper)
    key('Escape')
    await nextTick()
    expect(boxClass(wrapper).classes()).not.toContain('canvas-board__groupbox--selected')

    await clickGroupBlank(wrapper)
    selState.nodes = [{ id: 'm1' }, { id: 'm2' }]
    wrapper.getComponent(VueFlowStub).vm.$emit('selection-end')
    await flushPromises()
    expect(boxClass(wrapper).classes()).not.toContain('canvas-board__groupbox--selected')
  })

  it('⑤ 组头空白（pointerdown.self）→ 选中整组（组头=name/✕ 之外的条带）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(groupedSnap())
    // watch(pre) 微任务先于 rAF 排帧：先 flush 让 scheduleGroupBounds 挂上 rAF，再等帧
    await flushPromises()
    await rafFlush()
    const head = wrapper.find('.canvas-board__groupbox-head')
    expect(head.exists()).toBe(true)
    await head.trigger('pointerdown') // test-utils trigger target=head 自身=self
    expect(boxClass(wrapper).classes()).toContain('canvas-board__groupbox--selected')
  })

  it('⑥ Delete/Backspace 组选态 → 零动作（成员不删、不落库，Q5）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(groupedSnap())
    // watch(pre) 微任务先于 rAF 排帧：先 flush 让 scheduleGroupBounds 挂上 rAF，再等帧
    await flushPromises()
    await rafFlush()
    await clickGroupBlank(wrapper)
    const before = vm(wrapper).getSnapshot().nodes.length
    const changedBefore = (wrapper.emitted('structure-changed') ?? []).length
    key('Delete')
    key('Backspace')
    await nextTick()
    expect(vm(wrapper).getSnapshot().nodes).toHaveLength(before)
    expect((wrapper.emitted('structure-changed') ?? []).length).toBe(changedBefore)
    // 组选态仍在（Delete 不顺手清组选，组选退出只有 Esc/点选切换）
    expect(boxClass(wrapper).classes()).toContain('canvas-board__groupbox--selected')
  })

  it('⑦ 组被解散（✕ 或成员清空）→ 组选态悬挂清理（高亮不残留）', async () => {
    const wrapper = mount(CanvasBoard)
    vm(wrapper).loadSnapshot(groupedSnap())
    // watch(pre) 微任务先于 rAF 排帧：先 flush 让 scheduleGroupBounds 挂上 rAF，再等帧
    await flushPromises()
    await rafFlush()
    await clickGroupBlank(wrapper)
    vm(wrapper).loadSnapshot({ nodes: [node('m1')], edges: [], groups: [] })
    await flushPromises()
    await rafFlush()
    // 组没了框也不在——且选中态已被悬挂清理：重载同 id 组不误亮
    expect(wrapper.find('.canvas-board__groupbox').exists()).toBe(false)
    vm(wrapper).loadSnapshot(groupedSnap())
    await flushPromises()
    await rafFlush()
    expect(boxClass(wrapper).classes()).not.toContain('canvas-board__groupbox--selected')
  })
})

// 修复XI（2x 未解决④，D2）：整组拖动——选中组拖框空白=成员联动批移（rAF 合帧），
// 松手一次 structure-changed；未选中拖=先选中不拖（Q6 点选分层）；组端口优先不被抢。
describe('CanvasBoard · 整组拖动（修复XI D2）', () => {
  type BoardVmD2 = ReturnType<typeof boardVm> & {
    loadSnapshot: (s: { nodes?: unknown[]; edges?: unknown[]; groups?: unknown[] }) => void
    getSnapshot: () => { nodes: { id: string; position: { x: number; y: number } }[] }
  }
  const vm = (w: ReturnType<typeof mount>) => boardVm(w) as unknown as BoardVmD2
  const node = (id: string, x = 0, y = 0) => ({ id, type: 'text', position: { x, y }, data: { label: id } })
  const groupedSnap = () => ({
    nodes: [node('m1'), node('m2', 50, 50), node('ext', 600, 0)],
    edges: [],
    groups: [{ id: 'g1', name: '组1', memberIds: ['m1', 'm2'], color: '#5b8def' }]
  })
  const rafFlush = () => new Promise<void>(r => requestAnimationFrame(() => r()))

  /** 组框空白拖动全链：pane pointerdown（捕获段开拖动会话）→ window pointermove 序列 → pointerup。 */
  const dragGroupBlank = async (
    w: ReturnType<typeof mount>,
    from: [number, number],
    moves: Array<[number, number]>,
    up: [number, number]
  ) => {
    const pane = document.createElement('div')
    pane.className = 'vue-flow__pane'
    w.element.appendChild(pane)
    pane.dispatchEvent(new MouseEvent('pointerdown', { clientX: from[0], clientY: from[1], bubbles: true, button: 0 }))
    for (const [mx, my] of moves) {
      window.dispatchEvent(new MouseEvent('pointermove', { clientX: mx, clientY: my }))
      await nextTick()
    }
    window.dispatchEvent(new MouseEvent('pointerup', { clientX: up[0], clientY: up[1] }))
    pane.remove()
    await flushPromises()
    await rafFlush()
  }
  const boot = async (w: ReturnType<typeof mount>) => {
    vm(w).loadSnapshot(groupedSnap())
    await flushPromises()
    await rafFlush()
  }
  const pos = (w: ReturnType<typeof mount>, id: string) =>
    vm(w).getSnapshot().nodes.find(n => n.id === id)!.position
  const boxClass = (w: ReturnType<typeof mount>) => w.find('.canvas-board__groupbox')
  /** 点选选中组（D1 全链），供拖动用例起手。 */
  const selectGroup = async (w: ReturnType<typeof mount>) => {
    const pane = document.createElement('div')
    pane.className = 'vue-flow__pane'
    w.element.appendChild(pane)
    pane.dispatchEvent(new MouseEvent('pointerdown', { clientX: 10, clientY: 10, bubbles: true, button: 0 }))
    window.dispatchEvent(new MouseEvent('pointerup'))
    w.getComponent(VueFlowStub).vm.$emit('pane-click')
    pane.remove()
    await nextTick()
  }

  it('① 选中态拖框空白 → 全成员坐标批移 +delta（zoom=1），非成员不动', async () => {
    const wrapper = mount(CanvasBoard)
    await boot(wrapper)
    await selectGroup(wrapper)
    const changedBefore = (wrapper.emitted('structure-changed') ?? []).length
    // 越阈首帧只定基线（吞掉首段，标准拖动节流语义），位移从基线起算：60→110 = +50
    await dragGroupBlank(wrapper, [10, 10], [[60, 10], [110, 10]], [110, 10])
    expect(pos(wrapper, 'm1').x).toBe(50)
    expect(pos(wrapper, 'm1').y).toBe(0)
    expect(pos(wrapper, 'm2').x).toBe(100)
    expect(pos(wrapper, 'ext').x).toBe(600) // 组外成员不联动
    expect((wrapper.emitted('structure-changed') ?? []).length).toBe(changedBefore + 1)
  })

  it('② 未选中拖=先选中不拖：高亮转正、坐标零变、零落库（Q6 点选分层）', async () => {
    const wrapper = mount(CanvasBoard)
    await boot(wrapper)
    await dragGroupBlank(wrapper, [10, 10], [[80, 80]], [80, 80]) // 远超阈值=拖动意图
    expect(boxClass(wrapper).classes()).toContain('canvas-board__groupbox--selected')
    expect(pos(wrapper, 'm1').x).toBe(0)
    expect(pos(wrapper, 'm2').x).toBe(50)
    expect(wrapper.emitted('structure-changed')).toBeUndefined()
  })

  it('③ 未选中拖转正后再次拖=位移（两段式：先选中、后拖动）', async () => {
    const wrapper = mount(CanvasBoard)
    await boot(wrapper)
    await dragGroupBlank(wrapper, [10, 10], [[80, 80]], [80, 80]) // 第一段：只选中
    await dragGroupBlank(wrapper, [10, 10], [[60, 10], [110, 10]], [110, 10]) // 第二段：越阈定基线 60 后 +50
    expect(pos(wrapper, 'm1').x).toBe(50)
    expect(pos(wrapper, 'm2').x).toBe(100)
  })

  it('④ 拖动中零 structure-changed，松手恰好一次（防抖保存链不打扰）', async () => {
    const wrapper = mount(CanvasBoard)
    await boot(wrapper)
    await selectGroup(wrapper)
    const baseline = (wrapper.emitted('structure-changed') ?? []).length
    // 手工驱动序列：down → 越阈 move（起点=该帧）→ 位移 move → rAF 已应用但未松手
    const pane = document.createElement('div')
    pane.className = 'vue-flow__pane'
    wrapper.element.appendChild(pane)
    pane.dispatchEvent(new MouseEvent('pointerdown', { clientX: 10, clientY: 10, bubbles: true, button: 0 }))
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 60, clientY: 10 })) // 越阈，last=60
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 110, clientY: 10 })) // +50 入帧
    await rafFlush() // 帧已应用：位移生效但未 emit
    expect(pos(wrapper, 'm1').x).toBe(50)
    expect((wrapper.emitted('structure-changed') ?? []).length).toBe(baseline)
    window.dispatchEvent(new MouseEvent('pointerup'))
    pane.remove()
    await flushPromises()
    expect((wrapper.emitted('structure-changed') ?? []).length).toBe(baseline + 1)
  })

  it('⑤ rAF 合帧：多 move 一帧一批净位移；帧外新 move 不立即生效', async () => {
    const wrapper = mount(CanvasBoard)
    await boot(wrapper)
    await selectGroup(wrapper)
    const pane = document.createElement('div')
    pane.className = 'vue-flow__pane'
    wrapper.element.appendChild(pane)
    pane.dispatchEvent(new MouseEvent('pointerdown', { clientX: 10, clientY: 10, bubbles: true, button: 0 }))
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 14, clientY: 10 })) // 越阈（4px）
    for (let i = 1; i <= 3; i++) {
      window.dispatchEvent(new MouseEvent('pointermove', { clientX: 14 + i * 10, clientY: 10 })) // +10×3 同帧
    }
    await rafFlush()
    expect(pos(wrapper, 'm1').x).toBe(30) // 三次 move 一帧净 +30（批改 nodes 模型）
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 54, clientY: 10 })) // +10 帧外（上帧末 44）
    expect(pos(wrapper, 'm1').x).toBe(30) // rAF 未到不生效（节流门）
    await rafFlush()
    expect(pos(wrapper, 'm1').x).toBe(40)
    window.dispatchEvent(new MouseEvent('pointerup'))
    pane.remove()
    await flushPromises()
  })

  it('⑥ 组端口拖线优先：端口 pointerdown 不开整组拖动会话（成员零位移）', async () => {
    const wrapper = mount(CanvasBoard)
    await boot(wrapper)
    const port = wrapper.find('.canvas-board__groupbox-port')
    expect(port.exists()).toBe(true)
    await port.trigger('pointerdown', { button: 0, clientX: 200, clientY: 79 })
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 400, clientY: 79 }))
    window.dispatchEvent(new MouseEvent('pointerup', { clientX: 400, clientY: 79 }))
    await flushPromises()
    await rafFlush()
    expect(pos(wrapper, 'm1').x).toBe(0) // 端口拖线不位移成员
    expect(pos(wrapper, 'm2').x).toBe(50)
    expect(boxClass(wrapper).classes()).not.toContain('canvas-board__groupbox--selected') // 也不顺手选组
  })
})
