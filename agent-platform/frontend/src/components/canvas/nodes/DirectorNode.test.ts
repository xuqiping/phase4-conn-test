import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import DirectorNode from './DirectorNode.vue'
import { DIRECTOR_BRIDGE_KEY } from '../directorBridge'
import { createElement, createCamera, emptyScene } from '@/director/sceneModel'

/** 节点用 vue-flow <Handle>，独立 mount 缺 VueFlow 上下文 → stub 掉（同 TextNode.test 范式）。 */
function mountNode(data: Record<string, unknown>, id = 'node-d1') {
  const bridge = { openEditor: vi.fn() }
  const wrapper = mount(DirectorNode, {
    props: { id, data },
    global: {
      stubs: { Handle: true },
      provide: { [DIRECTOR_BRIDGE_KEY as symbol]: bridge }
    }
  })
  return { wrapper, bridge }
}

describe('DirectorNode 卡片（Step 7）', () => {
  it('空场景（无 directorScene）→ 引导文案 + 无摘要', () => {
    const { wrapper } = mountNode({ label: '导演台' })
    expect(wrapper.find('.director-node__empty').exists()).toBe(true)
    expect(wrapper.find('.director-node__summary').exists()).toBe(false)
    expect(wrapper.text()).toContain('空场景')
  })

  it('有场景 → 摘要「N 元素 · M 机位」（经 parseScene 白名单解析）', () => {
    const scene = emptyScene()
    scene.elements = [createElement('figure', 0), createElement('box', 1), createElement('table', 2)]
    scene.cameras = [createCamera([6, 4, 8], [0, 1, 0], 0)]
    const { wrapper } = mountNode({ label: '机位A', directorScene: scene })
    expect(wrapper.find('.director-node__summary').text()).toBe('3 元素 · 1 机位')
    expect(wrapper.find('.director-node__empty').exists()).toBe(false)
  })

  it('非法 directorScene（字符串乱值）→ 按空场景兜底，不崩', () => {
    const { wrapper } = mountNode({ label: '导演台', directorScene: 'not-a-scene' })
    expect(wrapper.find('.director-node__empty').exists()).toBe(true)
  })

  it('「打开导演台」按钮 → inject 桥 openEditor(节点id)', async () => {
    const { wrapper, bridge } = mountNode({ label: '导演台' })
    await wrapper.find('.director-node__open').trigger('click')
    expect(bridge.openEditor).toHaveBeenCalledWith('node-d1')
    expect(bridge.openEditor).toHaveBeenCalledTimes(1)
  })

  it('封面预览：有 coverPreviewUrl → 显封面图；有场景无封面 → 显提示文案', () => {
    const scene = emptyScene()
    scene.cameras = [createCamera([6, 4, 8], [0, 1, 0], 0)]
    const raw = scene
    const withCover = mountNode({ label: '导演台', directorScene: raw, coverPreviewUrl: 'blob:cover' }).wrapper
    expect(withCover.find('.director-node__cover img').attributes('src')).toBe('blob:cover')
    expect(withCover.find('.director-node__nocover').exists()).toBe(false)
    const noCover = mountNode({ label: '导演台', directorScene: raw }).wrapper
    expect(noCover.find('.director-node__nocover').exists()).toBe(true)
    expect(noCover.find('.director-node__cover').exists()).toBe(false)
  })
})
