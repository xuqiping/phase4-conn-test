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
