import { describe, expect, it, vi } from 'vitest'
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
vi.mock('@vue-flow/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@vue-flow/core')>()
  return {
    ...actual,
    VueFlow: VueFlowStub,
    useVueFlow: () => ({
      project: vi.fn(),
      zoomIn: vi.fn(),
      zoomOut: vi.fn(),
      fitView: vi.fn(),
      getViewport: vi.fn(() => ({ x: 0, y: 0, zoom: 1 })),
      getSelectedNodes: vi.fn(() => []),
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
