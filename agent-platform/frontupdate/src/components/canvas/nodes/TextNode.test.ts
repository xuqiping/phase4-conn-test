import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TextNode from './TextNode.vue'

/**
 * C6-① 节点头显 label（CanvasNodeBase 加 label 显示位）。
 * body 不再兜底显 label（避免头/体重复），仅显 prompt 占位。
 */
describe('TextNode C6 节点头 label 显示', () => {
  // 节点用 vue-flow <Handle>，独立 mount 缺 VueFlow 上下文 → stub 掉 Handle
  const mountNode = (data: Record<string, unknown>) =>
    mount(TextNode, { props: { data }, global: { stubs: { Handle: true } } })

  it('AC-C6-1 有 label → 头部显节点名（非空样式）', () => {
    const w = mountNode({ label: '主角设定', prompt: '' })
    const label = w.find('.canvas-node__label')
    expect(label.exists()).toBe(true)
    expect(label.text()).toBe('主角设定')
    expect(label.classes()).not.toContain('canvas-node__label--empty')
  })

  it('AC-C6-2 无 label → 头部显「未命名」灰字', () => {
    const w = mountNode({})
    const empty = w.find('.canvas-node__label--empty')
    expect(empty.exists()).toBe(true)
    expect(empty.text()).toBe('未命名')
  })

  it('AC-C6-3 body 不再兜底显 label（prompt 空 → 显占位，不含节点名）', () => {
    const w = mountNode({ label: '主角设定', prompt: '' })
    expect(w.find('.text-node__prompt').text()).toBe('双击右侧面板编辑提示词')
  })

  it('AC-C6-4 有 prompt → body 显 prompt（label 仅在头部）', () => {
    const w = mountNode({ label: '主角', prompt: '写一段描写' })
    expect(w.find('.text-node__prompt').text()).toBe('写一段描写')
    expect(w.find('.canvas-node__label').text()).toBe('主角')
  })
})
