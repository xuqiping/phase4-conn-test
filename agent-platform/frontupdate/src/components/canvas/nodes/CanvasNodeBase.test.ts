import { describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { mount } from '@vue/test-utils'

// 2x 四轮 S2：useNode 需 vue-flow 节点上下文，裸挂时 mock 注入假节点（Handle/Position 用真实现）
const fakeNodeData = vi.hoisted(() => ({ data: {} as Record<string, unknown> }))
vi.mock('@vue-flow/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@vue-flow/core')>()
  return {
    ...actual,
    useNode: () => ({ node: { data: fakeNodeData.data } })
  }
})
// node-resizer 控件依赖 d3-drag+真实 store，stub 掉（只验数据写入，不验拖拽）
vi.mock('@vue-flow/node-resizer', () => ({
  NodeResizeControl: { name: 'NodeResizeControl', props: ['variant', 'position', 'minWidth', 'minHeight'], render: () => null },
  ResizeControlVariant: { Handle: 'handle' as const, Line: 'line' as const }
}))

import CanvasNodeBase from './CanvasNodeBase.vue'

/** 2x 四轮 S2：拖角柄松手 → 宽高（四舍五入）写 node.data（快照持久化真源）。 */
describe('CanvasNodeBase · resize-end 落宽高（S2）', () => {
  // Handle 需 VueFlow 上下文 → stub（同 TextNode.test 范式）
  const mountBase = (props: { selected: boolean }) =>
    mount(CanvasNodeBase, { props: { kind: 'text', kindLabel: '文本', ...props }, global: { stubs: { Handle: true } } })

  it('onResizeEnd 写 data.width/height（取整）；--resized 类跟随 data.height', async () => {
    fakeNodeData.data = reactive({}) // 真源是 vue-flow GraphNode 的 reactive data，这里对齐
    const wrapper = mountBase({ selected: true })
    const vm = wrapper.vm as unknown as {
      onResizeEnd: (p: { params: { width: number; height: number } }) => void
    }
    vm.onResizeEnd({ params: { width: 319.6, height: 88.4 } })
    await Promise.resolve()
    expect(fakeNodeData.data).toMatchObject({ width: 320, height: 88 })
    expect(wrapper.find('.canvas-node').classes()).toContain('canvas-node--resized')
  })

  it('未拉过高度（data.height 无）→ 不加 --resized（文本保持 line-clamp）', () => {
    fakeNodeData.data = {}
    const wrapper = mountBase({ selected: false })
    expect(wrapper.find('.canvas-node').classes()).not.toContain('canvas-node--resized')
  })
})
