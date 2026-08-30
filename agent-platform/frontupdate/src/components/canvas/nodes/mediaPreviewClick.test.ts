import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

// 修复IV B1（C-1 两段式）：媒体节点两段式点击——未选中第一击只选中（放行冒泡，不弹预览），
// 已选中后点媒体本体才经 canvasMediaPreview 弹 Lightbox；拖拽尾（位移>5px）不成点击。
// useNode 需 vue-flow 节点上下文，裸挂 mock 注入假节点（同 CanvasNodeBase.test 范式）。
vi.mock('@vue-flow/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@vue-flow/core')>()
  return {
    ...actual,
    useNode: () => ({ node: { id: 'n1', data: {} } })
  }
})
// node-resizer 控件依赖 d3-drag + 真实 store，stub 掉（选中态渲染不走真拖拽）
vi.mock('@vue-flow/node-resizer', () => ({
  NodeResizeControl: { name: 'NodeResizeControl', props: ['variant', 'position', 'minWidth', 'minHeight'], render: () => null },
  ResizeControlVariant: { Handle: 'handle' as const, Line: 'line' as const }
}))

import ImageNode from './ImageNode.vue'
import VideoNode from './VideoNode.vue'

/** 挂媒体节点：global.provide 注入 preview 桩；Handle 需 VueFlow 上下文 → stub */
function mountMedia(component: typeof ImageNode, selected: boolean, preview?: (id: string) => void) {
  return mount(component, {
    props: { selected, data: { previewUrl: 'blob:x' } } as never,
    global: {
      stubs: { Handle: true },
      provide: preview ? { canvasMediaPreview: preview } : undefined
    }
  })
}

describe('修复IV B1（C-1 两段式）· 媒体区点击', () => {
  it('未选中点缩略图 → 只选中不弹预览（preview 回调不触发）', async () => {
    const preview = vi.fn()
    const wrapper = mountMedia(ImageNode, false, preview)
    await wrapper.find('.image-node__thumb').trigger('click')
    expect(preview).not.toHaveBeenCalled()
  })

  it('已选中点缩略图 → 弹预览（回调带节点 id）', async () => {
    const preview = vi.fn()
    const wrapper = mountMedia(ImageNode, true, preview)
    await wrapper.find('.image-node__thumb').trigger('click')
    expect(preview).toHaveBeenCalledWith('n1')
  })

  it('已选中但 pointerdown→click 位移 >5px（拖拽尾）→ 不弹', async () => {
    const preview = vi.fn()
    const wrapper = mountMedia(ImageNode, true, preview)
    await wrapper.find('.image-node__thumb').trigger('pointerdown', { clientX: 0, clientY: 0 })
    await wrapper.find('.image-node__thumb').trigger('click', { clientX: 30, clientY: 0 })
    expect(preview).not.toHaveBeenCalled()
  })

  it('视频本体同口径：未选中不弹 / 已选中弹', async () => {
    const preview = vi.fn()
    const unselected = mountMedia(VideoNode, false, preview)
    await unselected.find('.video-node__clip').trigger('click')
    expect(preview).not.toHaveBeenCalled()

    const selected = mountMedia(VideoNode, true, preview)
    await selected.find('.video-node__clip').trigger('click')
    expect(preview).toHaveBeenCalledWith('n1')
  })

  it('无 provide（异常裸挂）→ 已选中点不炸（链式守卫 no-op）', async () => {
    const wrapper = mountMedia(ImageNode, true)
    await wrapper.find('.image-node__thumb').trigger('click')
    expect(wrapper.find('.image-node__thumb').exists()).toBe(true)
  })
})
