import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { NInput } from 'naive-ui'
import PropertyPanel from './PropertyPanel.vue'
import ModelSelector from '@/components/chat/ModelSelector.vue'
import type { CanvasNode } from '@/types/canvas'

// FR-006：属性面板挂了 ModelSelector（拉可用模型列表），测试环境不打真请求
vi.mock('@/api/llm', () => ({
  llmApi: { listAvailableModels: vi.fn().mockResolvedValue({ data: { data: [] } }) }
}))

function mkNode(data: Record<string, unknown>): CanvasNode {
  return { id: 'node-1', type: 'text', position: { x: 0, y: 0 }, data: { label: 'n', ...data } }
}

function mountPanel(node: CanvasNode | null) {
  return mount(PropertyPanel, { props: { node } })
}

describe('PropertyPanel (S12-c 资产库区)', () => {
  it('无节点 → 不渲染资产区', () => {
    const wrapper = mountPanel(null)
    expect(wrapper.text()).not.toContain('资产库')
  })

  it('未绑定节点 → 渲染入库/选择两按钮，无徽标无更新按钮', () => {
    const wrapper = mountPanel(mkNode({ prompt: 'x' }))
    const text = wrapper.text()
    expect(text).toContain('存入资产库')
    expect(text).toContain('从库选择')
    expect(text).not.toContain('来自资产')
    expect(text).not.toContain('检查更新')
  })

  it('已绑定节点 → 渲染徽标 + 检查更新/更新到最新版（有新版时高亮）', () => {
    const wrapper = mountPanel(mkNode({
      prompt: 'x',
      assetId: 88, assetName: '老板娘', assetVersion: 2, assetHasUpdate: true
    }))
    const text = wrapper.text()
    expect(text).toContain('来自资产 · 老板娘 v2')
    expect(text).toContain('有新版')
    expect(text).toContain('检查更新')
    expect(text).toContain('更新到最新版')
  })

  it('有新版=false → 更新到最新版 禁用', () => {
    const wrapper = mountPanel(mkNode({
      prompt: 'x', assetId: 88, assetVersion: 2, assetHasUpdate: false
    }))
    const updateBtn = wrapper.findAll('button').find((b) => b.text().includes('更新到最新版'))!
    expect(updateBtn.attributes('disabled')).toBeDefined()
  })

  it('点击「存入资产库」→ emit save-to-asset 带 node', async () => {
    const node = mkNode({ prompt: 'x' })
    const wrapper = mountPanel(node)
    const btn = wrapper.findAll('button').find((b) => b.text().includes('存入资产库'))!
    await btn.trigger('click')
    expect(wrapper.emitted('save-to-asset')).toBeTruthy()
    expect((wrapper.emitted('save-to-asset')![0][0] as CanvasNode).id).toBe('node-1')
  })

  it('点击「从库选择」→ emit pick-from-asset', async () => {
    const wrapper = mountPanel(mkNode({ prompt: 'x' }))
    const btn = wrapper.findAll('button').find((b) => b.text().includes('从库选择'))!
    await btn.trigger('click')
    expect(wrapper.emitted('pick-from-asset')).toBeTruthy()
  })

  it('点击「检查更新/更新到最新版」→ 分别 emit check-update / update-asset', async () => {
    const wrapper = mountPanel(mkNode({
      prompt: 'x', assetId: 88, assetVersion: 1, assetHasUpdate: true
    }))
    const checkBtn = wrapper.findAll('button').find((b) => b.text().includes('检查更新'))!
    await checkBtn.trigger('click')
    expect(wrapper.emitted('check-update')).toBeTruthy()

    const updateBtn = wrapper.findAll('button').find((b) => b.text().includes('更新到最新版'))!
    await updateBtn.trigger('click')
    expect(wrapper.emitted('update-asset')).toBeTruthy()
  })
})

describe('PropertyPanel (S13 @引用 / 重命名查重)', () => {
  it('传 candidates → 文本节点提示词渲染 MentionTextarea', () => {
    const wrapper = mount(PropertyPanel, {
      props: {
        node: mkNode({ prompt: '@{{node:n1}}' }),
        candidates: [{ kind: 'node', id: 'n1', label: '上游' }]
      }
    })
    expect(wrapper.findComponent({ name: 'MentionTextarea' }).exists()).toBe(true)
  })

  it('brokenMentions 非空 → 渲染断链提示', () => {
    const wrapper = mount(PropertyPanel, {
      props: {
        node: mkNode({ prompt: '@{{node:gone}}' }),
        brokenMentions: ['@{{node:gone}}']
      }
    })
    expect(wrapper.text()).toContain('断链引用')
    expect(wrapper.text()).toContain('@{{node:gone}}')
  })

  it('brokenMentions 空 → 不渲染断链提示', () => {
    const wrapper = mountPanel(mkNode({ prompt: 'x' }))
    expect(wrapper.text()).not.toContain('断链引用')
  })

  it('重命名撞名 → 失焦自动追加序号（L9）', async () => {
    const node = mkNode({ prompt: 'x' })
    node.data.label = '图片'
    const wrapper = mount(PropertyPanel, {
      // allLabels = 其他节点 label（按 id 剔除自身，含另一「文本」节点）
      props: { node, allLabels: ['文本'] }
    })
    // 改成与已有「文本」撞名
    node.data.label = '文本'
    // 触发名称输入框 blur（首个 NInput 即名称字段）
    await wrapper.findComponent(NInput).vm.$emit('blur')
    await wrapper.vm.$nextTick()
    expect(node.data.label).toBe('文本 2')
  })

  it('视频节点 prompt 亦用 MentionTextarea', () => {
    const node = mkNode({ prompt: 'p' })
    node.type = 'video'
    const wrapper = mount(PropertyPanel, { props: { node } })
    expect(wrapper.findComponent({ name: 'MentionTextarea' }).exists()).toBe(true)
  })
})

describe('PropertyPanel (FR-006 模型选择器)', () => {
  it('文本/脚本节点渲染模型选择器，视频节点不渲染', () => {
    const textWrapper = mountPanel(mkNode({ prompt: 'x' }))
    expect(textWrapper.findComponent(ModelSelector).exists()).toBe(true)

    const scriptWrapper = mountPanel({ ...mkNode({ synopsis: 's' }), type: 'script' })
    expect(scriptWrapper.findComponent(ModelSelector).exists()).toBe(true)

    const videoWrapper = mountPanel({ ...mkNode({ prompt: 'p' }), type: 'video' })
    expect(videoWrapper.findComponent(ModelSelector).exists()).toBe(false)
  })

  it('选模型 → node.data.model 落值 + emit data-changed（父组件 scheduleSave 落库）', async () => {
    const node = mkNode({ prompt: 'x' })
    const wrapper = mountPanel(node)
    wrapper.findComponent(ModelSelector).vm.$emit('update:modelValue', 'gpt-test')
    await wrapper.vm.$nextTick()
    expect(node.data.model).toBe('gpt-test')
    expect(wrapper.emitted('data-changed')).toBeTruthy()
  })

  it('清空（emit 空串）→ 删 node.data.model 回退默认', async () => {
    const node = mkNode({ prompt: 'x', model: 'gpt-test' })
    const wrapper = mountPanel(node)
    wrapper.findComponent(ModelSelector).vm.$emit('update:modelValue', '')
    await wrapper.vm.$nextTick()
    expect('model' in node.data).toBe(false)
    expect(wrapper.emitted('data-changed')).toBeTruthy()
  })
})
