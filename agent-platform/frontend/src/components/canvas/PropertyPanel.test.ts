import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
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

// 修复III C2（2x-2）：图片模型目录 mock（defaultModel 标记驱动默认选中；estimate 失败静默不干扰断言）
vi.mock('@/api/media', () => ({
  mediaApi: {
    listImageModels: vi.fn().mockResolvedValue({
      data: {
        data: [
          { modelId: 'seedream-4.0', displayName: 'Seedream 4.0', providerName: 'Ark', capability: {} },
          { modelId: 'seedream-lite', displayName: 'Seedream Lite', providerName: 'Ark', defaultModel: true, capability: {} }
        ].map(m => ({ ...m, capability: m.capability as unknown as import('@/api/media').ImageModelCapability }))
      }
    }),
    estimatePreview: vi.fn().mockRejectedValue(new Error('estimate off'))
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

// D2（2x-8）：上游节点面板（BFS 分层卡片 / 双击插 @引用 / 无上游空态）
describe('PropertyPanel · D2 上游面板', () => {
  function mkUp(id: string, type: string, label: string, data: Record<string, unknown> = {}) {
    // label 归入 data（组件模板读 u.node.data.label）
    return { id, type, data: { label, ...data } }
  }

  it('无 upstream → 显「无上游节点」空态', () => {
    const wrapper = mountPanel(mkNode({ prompt: 'x' }))
    expect(wrapper.text()).toContain('无上游节点')
  })

  it('修复IV B2：上游全直显——depth>1 不折叠，卡带 ·N 层级号与类型配色 data-kind', () => {
    const far = mkUp('u-far', 'text', '更上游文案', { outputText: '很早的产出' })
    const near = mkUp('u-img', 'image', '主视觉图', { prompt: '一个红色屋顶的小屋，冬夜雪景' })
    const wrapper = mount(PropertyPanel, {
      props: {
        node: mkNode({ prompt: 'p' }),
        upstream: { items: [{ node: near as unknown as CanvasNode, depth: 1 }, { node: far as unknown as CanvasNode, depth: 2 }], truncated: false }
      }
    })
    const text = wrapper.text()
    expect(text).toContain('主视觉图')
    expect(text).toContain('图片')
    expect(text).toContain('一个红色屋顶的小屋')
    // 更上游同区直显（不再折叠按钮/隐藏）
    expect(text).toContain('更上游文案')
    expect(text).toContain('文本·2')
    const cards = wrapper.findAll('.prop-panel__up-card')
    expect(cards).toHaveLength(2)
    expect(cards[1].classes()).toContain('prop-panel__up-card--far')
    // 类型徽标配色（data-kind 着色钩子）
    expect(cards[0].find('.prop-panel__up-kind').attributes('data-kind')).toBe('image')
    expect(cards[1].find('.prop-panel__up-kind').attributes('data-kind')).toBe('text')
  })

  it('双击直接上游卡 → @token 追加进本节点 prompt', async () => {
    const node = mkNode({ prompt: '扩写' })
    const near = mkUp('u-1', 'text', '上游文本', {})
    const wrapper = mount(PropertyPanel, {
      props: { node, upstream: { items: [{ node: near as unknown as CanvasNode, depth: 1 }], truncated: false } }
    })
    await wrapper.find('.prop-panel__up-card').trigger('dblclick')
    expect(node.data.prompt).toBe('扩写 @{{node:u-1}} ')
  })

  it('truncated → 显截断提示', () => {
    const wrapper = mount(PropertyPanel, {
      props: { node: mkNode({ prompt: 'p' }), upstream: { items: [], truncated: true } }
    })
    expect(wrapper.text()).toContain('已截断')
  })

  it('图片上游有 previewUrl → 渲染缩略 img 且可点（zoom-in）', () => {
    const near = mkUp('u-img', 'image', '图', { previewUrl: 'blob:img' })
    const wrapper = mount(PropertyPanel, {
      props: { node: mkNode({ prompt: 'p' }), upstream: { items: [{ node: near as unknown as CanvasNode, depth: 1 }], truncated: false } }
    })
    const thumb = wrapper.find('.prop-panel__up-thumb')
    expect(thumb.find('img').attributes('src')).toBe('blob:img')
    expect(thumb.classes()).toContain('is-clickable')
  })
})

// 修复IV B4（C-6）：属性面板左缘拖宽 260-560 + localStorage 持久化 + 非法值回落
describe('PropertyPanel · 修复IV B4 面板拖宽', () => {
  const KEY = 'canvas.propPanel.width'
  const widthOf = (w: ReturnType<typeof mountPanel>) => w.find('.prop-panel').attributes('style') ?? ''

  beforeEach(() => localStorage.removeItem(KEY))
  afterEach(() => localStorage.removeItem(KEY))

  it('无存储值 → 默认 260px；separator 角色与 aria 阈值齐', () => {
    const wrapper = mountPanel(mkNode({ prompt: 'p' }))
    expect(widthOf(wrapper)).toContain('width: 260px')
    const gutter = wrapper.find('.prop-panel__gutter')
    expect(gutter.attributes('role')).toBe('separator')
    expect(gutter.attributes('aria-valuemin')).toBe('260')
    expect(gutter.attributes('aria-valuemax')).toBe('560')
  })

  it('拖左缘向左 100px → 360px；松手写 localStorage', async () => {
    const wrapper = mountPanel(mkNode({ prompt: 'p' }))
    await wrapper.find('.prop-panel__gutter').trigger('pointerdown', { clientX: 500 })
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 400 })) // dx=-100 → 变宽
    await wrapper.vm.$nextTick()
    expect(widthOf(wrapper)).toContain('width: 360px')
    window.dispatchEvent(new MouseEvent('pointerup'))
    expect(localStorage.getItem(KEY)).toBe('360')
  })

  it('拖出界钳制：dx=-9999 → 封顶 560，不越下界', async () => {
    const wrapper = mountPanel(mkNode({ prompt: 'p' }))
    await wrapper.find('.prop-panel__gutter').trigger('pointerdown', { clientX: 500 })
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 9999 })) // dx=+9499 → 压到下界 260
    await wrapper.vm.$nextTick()
    expect(widthOf(wrapper)).toContain('width: 260px')
    window.dispatchEvent(new MouseEvent('pointerup'))
  })

  it('存储非法/越界值 → 挂载即回落 260/560', () => {
    localStorage.setItem(KEY, 'abc')
    expect(widthOf(mountPanel(mkNode({ prompt: 'p' })))).toContain('width: 260px')
    localStorage.setItem(KEY, '9999')
    expect(widthOf(mountPanel(mkNode({ prompt: 'p' })))).toContain('width: 560px')
  })
})

// 修复IV C1b/C1c（C-4 缺口2/3）：文本失焦报存 + 参数变更即报存
describe('PropertyPanel · 修复IV C1b/C1c 变更即保存', () => {
  it('C1b：名称框 blur → emit data-changed（改名即报存，L10 关键档）', async () => {
    const node = mkNode({ prompt: 'x' })
    node.data.label = '节点A'
    const wrapper = mountPanel(node)
    await wrapper.findComponent(NInput).vm.$emit('blur')
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('data-changed')).toBeTruthy()
  })

  it('C1b：MentionTextarea blur-committed 上抛为 data-changed', async () => {
    const node = mkNode({ prompt: 'p' })
    const wrapper = mountPanel(node)
    await wrapper.findComponent({ name: 'MentionTextarea' }).vm.$emit('blur-committed')
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('data-changed')).toBeTruthy()
  })

  it('C1c：视频改分辨率 → data 写入 + data-changed 恰一次', async () => {
    const node = mkNode({ prompt: 'p' })
    node.type = 'video'
    node.data.resolution = '720p'
    const wrapper = mountPanel(node)
    await flushPromises() // 先让模型目录加载等挂载期 watcher 落定
    const before = (wrapper.emitted('data-changed') ?? []).length
    // 定位分辨率下拉：options 含 4K 的唯一 NSelect（比例/来源/模型选项集不同）
    const { NSelect } = await import('naive-ui')
    const resSelect = wrapper.findAllComponents(NSelect)
      .find(s => ((s.props('options') as { value: string }[] | undefined) ?? []).some(o => o.value === '4K'))
    expect(resSelect).toBeTruthy()
    await resSelect!.vm.$emit('update:value', '1080p')
    await wrapper.vm.$nextTick()
    expect(node.data.resolution).toBe('1080p')
    expect((wrapper.emitted('data-changed') ?? []).length).toBe(before + 1)
  })
})

// C2（2x-2）：图片节点未选模型 → 目录加载后补默认（管理员默认标记 ?? 第一个）
describe('PropertyPanel · C2 默认生图模型', () => {
  it('未选模型 + 目录含 defaultModel 标记 → 自动选标记项并 emit data-changed', async () => {
    const node = mkNode({ prompt: 'p' })
    node.type = 'image'
    const wrapper = mount(PropertyPanel, { props: { node } })
    await flushPromises()
    expect(node.data.model).toBe('seedream-lite')
    expect(wrapper.emitted('data-changed')).toBeTruthy()
    wrapper.unmount()
  })

  it('已显式选模型 → 不被默认覆盖', async () => {
    const node = mkNode({ prompt: 'p', model: 'seedream-4.0' })
    node.type = 'image'
    const wrapper = mount(PropertyPanel, { props: { node } })
    await flushPromises()
    expect(node.data.model).toBe('seedream-4.0')
    wrapper.unmount()
  })

  it('目录无 defaultModel 标记（未配置/失效）→ 回落第一个', async () => {
    const { mediaApi } = await import('@/api/media')
    // 运行时形状 {data:{data:[…]}}（axios 拦截器已剥外层 code/message；与声明类型的差异以 cast 对齐）
    vi.mocked(mediaApi.listImageModels).mockResolvedValueOnce({
      data: {
        data: [{
          modelId: 'only-model', displayName: 'Only', providerName: 'Ark',
          capability: {} as unknown as import('@/api/media').ImageModelCapability
        }]
      }
    } as unknown as Awaited<ReturnType<typeof mediaApi.listImageModels>>)
    const node = mkNode({ prompt: 'p' })
    node.type = 'image'
    const wrapper = mount(PropertyPanel, { props: { node } })
    await flushPromises()
    expect(node.data.model).toBe('only-model')
    wrapper.unmount()
  })
})

// C4（2x-4）：创建副本按钮
describe('PropertyPanel · C4 创建副本', () => {
  it('点击「创建副本」→ emit clone-node 带当前节点', async () => {
    const node = mkNode({ prompt: 'x' })
    const wrapper = mountPanel(node)
    const btn = wrapper.findAll('button').find(b => b.text().includes('创建副本'))!
    await btn.trigger('click')
    const events = wrapper.emitted('clone-node')
    expect(events).toHaveLength(1)
    expect((events![0][0] as CanvasNode).id).toBe('node-1')
  })
})
