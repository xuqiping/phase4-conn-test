import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { NInput } from 'naive-ui'
import PropertyPanel from './PropertyPanel.vue'
import type { CanvasNode } from '@/types/canvas'

vi.mock('@/api/llm', () => ({
  llmApi: {
    listAvailableModels: vi.fn().mockResolvedValue({
      data: { data: [{ modelId: 'm1', displayName: 'M1', providerName: 'ChatProvider', source: 'global' }] }
    }),
    listVideoModels: vi.fn().mockResolvedValue({
      data: { data: [{ modelId: 'seedance', displayName: 'Seedance', providerName: 'Ark', source: 'global' }] }
    })
  }
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

// AC-C5：节点选模型（text/script=chat 模型；video=MEDIA 视频模型）
describe('PropertyPanel C5 节点选模型', () => {
  it('AC-C5-1 文本节点渲染「模型」下拉', () => {
    const wrapper = mount(PropertyPanel, { props: { node: mkNode({ prompt: 'p' }) } })
    const labels = wrapper.findAll('label').map(l => l.text())
    expect(labels).toContain('模型')
  })

  it('AC-C5-2 脚本节点渲染「模型」下拉', () => {
    const node = mkNode({ synopsis: '剧本' })
    node.type = 'script'
    const wrapper = mount(PropertyPanel, { props: { node } })
    expect(wrapper.findAll('label').map(l => l.text())).toContain('模型')
  })

  it('AC-C5-3 视频节点渲染「视频模型」下拉', () => {
    const node = mkNode({ prompt: 'p' })
    node.type = 'video'
    const wrapper = mount(PropertyPanel, { props: { node } })
    expect(wrapper.findAll('label').map(l => l.text())).toContain('视频模型')
  })
})

// 计划6 Step3：视频反推区 + 本土化转绘入口（画布 PropertyPanel）
describe('PropertyPanel 计划6 视频反推 / 本土化转绘', () => {
  function mkVideo(data: Record<string, unknown> = {}): CanvasNode {
    const node = mkNode({ prompt: 'p', ...data })
    node.type = 'video'
    return node
  }

  it('视频节点渲染反推区（三勾选默认关键帧 + 帧数/阈值高级项）', () => {
    const wrapper = mountPanel(mkVideo({ fileId: 'f1' }))
    const text = wrapper.text()
    expect(text).toContain('反推（关键帧 / 分镜表 / 剧本）')
    expect(text).toContain('关键帧')
    expect(text).toContain('分镜表')
    expect(text).toContain('剧本')
  })

  it('无 fileId（视频未生成）→ 开始反推禁用', () => {
    const wrapper = mountPanel(mkVideo({}))
    const btn = wrapper.findAll('button').find(b => b.text().includes('开始反推'))!
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('reversing=true → 取消按钮出现', () => {
    const wrapper = mount(PropertyPanel, { props: { node: mkVideo({ fileId: 'f1' }), reversing: true } })
    expect(wrapper.findAll('button').some(b => b.text().includes('取消'))).toBe(true)
  })

  it('点击开始反推 → emit reverse-analyze（默认 modes=[KEYFRAMES]，未填高级项不传）', async () => {
    const node = mkVideo({ fileId: 'f1' })
    const wrapper = mount(PropertyPanel, { props: { node } })
    const btn = wrapper.findAll('button').find(b => b.text().includes('开始反推'))!
    await btn.trigger('click')
    const events = wrapper.emitted('reverse-analyze')
    expect(events).toHaveLength(1)
    expect(events![0][0]).toMatchObject({ node, modes: ['KEYFRAMES'] })
    const payload = events![0][0] as { maxFrames?: number; sceneThreshold?: number }
    expect(payload.maxFrames).toBeUndefined()
    expect(payload.sceneThreshold).toBeUndefined()
  })

  it('script 节点渲染「本土化转绘」按钮；synopsis 空 → 禁用', () => {
    const node = mkNode({ synopsis: '' })
    node.type = 'script'
    const wrapper = mountPanel(node)
    const btn = wrapper.findAll('button').find(b => b.text().includes('本土化转绘'))!
    expect(btn).toBeTruthy()
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('storyboard 节点有描述 → 转绘按钮可用', () => {
    const node = mkNode({ description: '#1 0-5s 远景 开场' })
    node.type = 'storyboard'
    const wrapper = mountPanel(node)
    const btn = wrapper.findAll('button').find(b => b.text().includes('本土化转绘'))!
    expect(btn.attributes('disabled')).toBeUndefined()
  })

  it('转绘产物节点 data 带 changeLog → 面板渲染替换清单逐条核对；warning 展示', () => {
    const node = mkNode({
      synopsis: '{"scenes":[]}',
      changeLog: [
        { from: '筷子', to: '刀叉', scene: '第2场' },
        { from: '红灯笼', to: '感恩节彩灯', scene: '第2场' }
      ],
      localizeWarning: '场景数不一致：原 2 现 1，改写结果仅供参考，请人工核对'
    })
    node.type = 'script'
    const wrapper = mountPanel(node)
    const text = wrapper.text()
    expect(text).toContain('替换清单（changeLog，2 处）')
    expect(text).toContain('筷子 → 刀叉（第2场）')
    expect(text).toContain('红灯笼 → 感恩节彩灯（第2场）')
    expect(text).toContain('场景数不一致')
  })

  it('普通 script 节点（无 changeLog）→ 不渲染替换清单', () => {
    const node = mkNode({ synopsis: '普通剧本' })
    node.type = 'script'
    const wrapper = mountPanel(node)
    expect(wrapper.text()).not.toContain('替换清单')
  })
})
