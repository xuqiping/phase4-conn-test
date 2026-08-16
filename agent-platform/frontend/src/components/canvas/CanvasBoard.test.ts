import { describe, expect, it, vi } from 'vitest'
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
      getViewport: vi.fn(),
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
    const btns = wrapper.findAll('button[aria-pressed]')
    expect(btns).toHaveLength(2)
    await btns[1].trigger('click')
    await wrapper.findAll('button[aria-pressed]')[0].trigger('click')
    expect(boardVm(wrapper).dragMode).toBe('pan')
    expect(flowProps(wrapper).panOnDrag).toBe(true)
    expect(flowProps(wrapper).selectionKeyCode).toBe('Shift')
    const pressed = wrapper.findAll('button[aria-pressed]').map(b => b.attributes('aria-pressed'))
    expect(pressed).toEqual(['true', 'false'])
  })
})
