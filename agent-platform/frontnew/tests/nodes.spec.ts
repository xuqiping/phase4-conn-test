import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TextNode from '@/components/canvas/nodes/TextNode.vue'
import ImageNode from '@/components/canvas/nodes/ImageNode.vue'
import VideoNode from '@/components/canvas/nodes/VideoNode.vue'
import AudioNode from '@/components/canvas/nodes/AudioNode.vue'
import ScriptNode from '@/components/canvas/nodes/ScriptNode.vue'
import StoryboardNode from '@/components/canvas/nodes/StoryboardNode.vue'
import type { MockNodeData } from '@/mocks/types'

// 6 节点组件：各自渲染出类型类名 + 类型特有预览区
const data = (kind: MockNodeData['kind'], extra: Partial<MockNodeData> = {}): MockNodeData => ({
  kind,
  status: 'idle',
  label: '测试',
  ...extra
})

describe('6 类节点组件', () => {
  it('TextNode：摘要渲染', () => {
    const w = mount(TextNode, { props: { data: data('text', { prompt: '写一段旁白' }) } })
    expect(w.find('.node-card--text').exists()).toBe(true)
    expect(w.text()).toContain('写一段旁白')
  })

  it('ImageNode：16:9 缩略图占位 + 取景框角标', () => {
    const w = mount(ImageNode, { props: { data: data('image') } })
    expect(w.find('.node-card--image').exists()).toBe(true)
    expect(w.find('.image-node__thumb').exists()).toBe(true)
    expect(w.findAll('.image-node__corner')).toHaveLength(4)
  })

  it('VideoNode：遮幅缩略图 + 时长徽标', () => {
    const w = mount(VideoNode, { props: { data: data('video', { durationSec: 8 }) } })
    expect(w.find('.node-card--video').exists()).toBe(true)
    expect(w.text()).toContain('8s')
  })

  it('AudioNode：波形条渲染', () => {
    const w = mount(AudioNode, { props: { data: data('audio') } })
    expect(w.find('.audio-node__wave').exists()).toBe(true)
    expect(w.findAll('.audio-node__wave i').length).toBeGreaterThan(10)
  })

  it('ScriptNode：行数徽标 + 首行', () => {
    const w = mount(ScriptNode, { props: { data: data('script', { lines: 42, firstLine: '# 标题' }) } })
    expect(w.text()).toContain('42 行')
    expect(w.text()).toContain('# 标题')
  })

  it('StoryboardNode：镜头数 + 2×2 图阵', () => {
    const w = mount(StoryboardNode, { props: { data: data('storyboard', { shots: 6 }) } })
    expect(w.text()).toContain('6 个镜头')
    expect(w.findAll('.storyboard-node__grid i')).toHaveLength(4)
  })
})
