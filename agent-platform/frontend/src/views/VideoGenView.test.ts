import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { AxiosResponse } from 'axios'
import VideoGenView from './VideoGenView.vue'
import { mediaApi, type MediaModelVO, type MediaTaskVO } from '@/api/media'
import { useAuthStore } from '@/stores/auth'

const messageMock = { success: vi.fn(), error: vi.fn(), warning: vi.fn() }
const dialogMock = { info: vi.fn(), warning: vi.fn(), success: vi.fn(), error: vi.fn(), create: vi.fn() }

vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  // useDialog 同步 mock：mount 未包 NDialogProvider，组件 setup 里的 useDialog() 会抛 no provider（存量问题，非本 chunk 引入）
  return { ...actual, useMessage: () => messageMock, useDialog: () => dialogMock }
})

vi.mock('@/composables/useBreakpoints', () => ({
  useBreakpoints: () => ({ isMobile: { value: false } })
}))

vi.mock('@/api/file', () => ({ fetchFilePreview: vi.fn() }))

vi.mock('@/api/media', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/media')>()
  return {
    ...actual,
    fetchVideoBlob: vi.fn(),
    fetchMediaText: vi.fn(),
    mediaApi: {
      ...actual.mediaApi,
      listModels: vi.fn(),
      listTasks: vi.fn(),
      getTask: vi.fn(),
      submitVideo: vi.fn(),
      estimatePreview: vi.fn()
    }
  }
})

function response<T>(data: T): AxiosResponse<{ code: number; message: string; data: T }> {
  return {
    data: { code: 200, message: 'ok', data },
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: {} as never }
  }
}

const model: MediaModelVO = {
  modelId: 'seedance-2',
  displayName: 'SeedDance 2.0',
  providerName: 'Ark',
  maxImages: 9,
  maxVideos: 3,
  maxAudios: 3,
  maxAttachments: 12,
  supportedRatios: ['16:9'],
  supportedResolutions: ['720p'],
  minDuration: 4,
  maxDuration: 15,
  supportsGenerateAudio: true,
  videoDataUri: false,
  referenceVideoEnabled: false
}

/** HHX-7：HappyHorse 三形态（t2v 纯文本无附件 / i2v 官方无 ratio）+ HHX-9/10 MiniMax 附属档。 */
function makeModel(overrides: Partial<MediaModelVO> & { modelId: string }): MediaModelVO {
  return { ...model, displayName: overrides.modelId, ...overrides }
}

const hhT2V = makeModel({
  modelId: 'happyhorse-1.1-t2v', providerName: 'HappyHorse',
  maxImages: 0, maxVideos: 0, maxAudios: 0, maxAttachments: 0,
  supportedRatios: ['16:9', '9:16', '1:1'], supportedResolutions: ['720p', '1080p'],
  minDuration: 3, maxDuration: 15, supportsGenerateAudio: false
})
const hhI2V = makeModel({
  modelId: 'happyhorse-1.1-i2v', providerName: 'HappyHorse',
  maxImages: 1, maxVideos: 0, maxAudios: 0, maxAttachments: 1,
  supportedRatios: [], supportedResolutions: ['480p', '720p', '1080p'],
  minDuration: 3, maxDuration: 15, supportsGenerateAudio: false
})
const mmH3 = makeModel({
  modelId: 'minimax-h3', providerName: 'MiniMax',
  supportedRatios: ['16:9', '9:16', '1:1'], supportedResolutions: ['768p', '2k']
})
const mmCtxIr = makeModel({
  modelId: 'minimax-h3-context-ir', providerName: 'MiniMax',
  supportedRatios: ['16:9', '9:16', '1:1'], supportedResolutions: ['768p', '2k']
})
const mmRegen = makeModel({
  modelId: 'minimax-h3-regeneration', providerName: 'MiniMax',
  maxImages: 0, maxVideos: 0, maxAudios: 0, maxAttachments: 0,
  supportedRatios: [], supportedResolutions: ['2k']
})

const historyTask: MediaTaskVO = {
  id: 44,
  status: 'FAILED',
  statusFlag: null,
  taskType: 'IMAGE2VIDEO',
  model: model.modelId,
  prompt: '图1走向窗台，运镜使用视频1',
  duration: 5,
  ratio: '16:9',
  resolution: '720p',
  watermark: false,
  generateAudio: false,
  inputAttachments: [],
  hasReference: false,
  submittedRequest: null,
  providerRequestSnapshot: null,
  tokensCost: null,
  errorMsg: 'test',
  videoUrl: null,
  resultFileId: null,
  imageUrls: null,
  imageFileIds: null,
  generatedImages: null,
  outputTokens: null,
  size: null,
  outputFormat: null,
  createdAt: '2026-08-11T10:00:00+08:00',
  updatedAt: null
}

async function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().userInfo = {
    id: 1,
    username: 'tester',
    email: null,
    avatar: null,
    roles: ['admin'],
    permissions: ['media:gen']
  }
  const wrapper = mount(VideoGenView, { global: { plugins: [pinia] } })
  await flushPromises()
  return wrapper
}

describe('VideoGenView regressions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(mediaApi.listModels).mockResolvedValue(response([model]))
    // 4x#2：listTasks 返回分页包裹结构（PageResult 语义）
    vi.mocked(mediaApi.listTasks).mockResolvedValue(
      response({ records: [historyTask], total: 1, page: 1, size: 10, pages: 1 }))
    vi.mocked(mediaApi.estimatePreview).mockResolvedValue(
      response({ estimatedPoints: 10, affordable: true, balance: 100, personalScope: null }))
    vi.mocked(mediaApi.submitVideo).mockResolvedValue(
      response({ id: 99, status: 'PENDING' }))
  })

  it('AC-VFIX-01 keeps reference-video section visible but disabled when public HTTPS is unconfigured', async () => {
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('参考视频')
    expect(wrapper.text()).toContain('公网 HTTPS 地址和签名密钥')
    const vm = wrapper.vm as unknown as { referenceVideoUsable: boolean }
    expect(vm.referenceVideoUsable).toBe(false)
  })

  it('AC-VFIX-02 keeps the prompt field pinned and populated in narrow history panes', async () => {
    const wrapper = await mountView()
    const vm = wrapper.vm as unknown as {
      history: MediaTaskVO[]
      historyColumns: Array<{ key?: string; fixed?: string; width?: number }>
    }

    expect(vm.history[0].prompt).toBe(historyTask.prompt)
    expect(vm.historyColumns.find(column => column.key === 'prompt')).toMatchObject({
      fixed: 'left',
      width: 260
    })
  })
})

/** HHX：测试方案 M1-M5 联动用例（能力驱动显隐 / 再生成源任务模式 / context-ir 表单与结果）。 */
describe('VideoGenView HHX capability & aux-model linkage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(mediaApi.listModels).mockResolvedValue(response([model, hhT2V, hhI2V, mmH3, mmCtxIr, mmRegen]))
    vi.mocked(mediaApi.listTasks).mockResolvedValue(
      response({ records: [historyTask], total: 1, page: 1, size: 10, pages: 1 }))
    vi.mocked(mediaApi.estimatePreview).mockResolvedValue(
      response({ estimatedPoints: 10, affordable: true, balance: 100, personalScope: null }))
    vi.mocked(mediaApi.submitVideo).mockResolvedValue(
      response({ id: 99, status: 'PENDING' }))
  })

  /** M1+M2：t2v 无附件隐藏上传区；i2v 空比例列表隐藏比例控件；切换越界值收敛到能力首档。 */
  it('m1m2 hides upload area for t2v (maxImages=0) and ratio control for i2v (empty ratios)', async () => {
    const wrapper = await mountView()
    const vm = wrapper.vm as unknown as {
      form: { model: string; ratio: string; resolution: string }
      applyCapabilityConstraints: () => void
      onSubmit: () => Promise<void>
    }

    // M1：t2v 纯文本——参考图/首帧/参考视频上传区整体隐藏
    vm.form.model = hhT2V.modelId
    vm.applyCapabilityConstraints()
    await flushPromises()
    expect(wrapper.text()).not.toContain('首帧（可选')
    expect(wrapper.text()).not.toContain('参考图')

    // M2：i2v 官方无 ratio → 比例控件隐藏；分辨率越界值（720p→1080p 属实）收敛到能力内
    vm.form.model = hhI2V.modelId
    vm.applyCapabilityConstraints()
    await flushPromises()
    expect(wrapper.text()).not.toContain('画面比例')
    expect(wrapper.text()).toContain('分辨率')

    // 提交侧：i2v ratio 省略不发（后端 i2v 能力校验无比例档）
    vm.form.resolution = '1080p'
    await vm.onSubmit()
    const payload = vi.mocked(mediaApi.submitVideo).mock.calls[0][0]
    expect(payload.model).toBe(hhI2V.modelId)
    expect(payload.ratio).toBeUndefined()
    expect(payload.resolution).toBe('1080p')
  })

  /** M3：再生成模式——源任务选择器替代提示词/附件/参数区；提交载荷只有 model+sourceTaskId。 */
  it('m3 regeneration mode picks succeeded same-provider source and submits minimal payload', async () => {
    const now = new Date().toISOString()
    const sourceTask: MediaTaskVO = {
      ...historyTask, id: 4242, status: 'SUCCEEDED', taskType: 'TEXT2VIDEO',
      model: 'minimax-h3', prompt: '源任务提示词', duration: 8, createdAt: now
    }
    vi.mocked(mediaApi.listTasks).mockResolvedValue(response({
      records: [
        sourceTask,
        // 反向：失败任务 / 附属任务 / 跨供应商 / 超 7 天窗口 —— 均不入候选
        { ...historyTask, id: 1, status: 'FAILED', model: 'minimax-h3', createdAt: now },
        { ...historyTask, id: 2, status: 'SUCCEEDED', model: 'minimax-h3-context-ir', createdAt: now },
        { ...historyTask, id: 3, status: 'SUCCEEDED', model: 'seedance-2', createdAt: now },
        { ...historyTask, id: 4, status: 'SUCCEEDED', model: 'minimax-h3', createdAt: '2026-01-01T00:00:00+08:00' }
      ],
      total: 5, page: 1, size: 10, pages: 1
    }))
    const wrapper = await mountView()
    const vm = wrapper.vm as unknown as {
      form: { model: string; sourceTaskId: number | null }
      onModelChange: () => void
      sourceTaskOptions: Array<{ value: number }>
      onSubmit: () => Promise<void>
    }

    vm.form.model = mmRegen.modelId
    vm.onModelChange()
    await flushPromises()

    expect(vm.sourceTaskOptions).toHaveLength(1)
    expect(vm.sourceTaskOptions[0].value).toBe(4242)
    expect(wrapper.text()).toContain('源任务（已完成的 MiniMax 生成）')
    // 再生成无提示词/参数语义：输入区只剩源任务选择
    expect(wrapper.text()).not.toContain('时长（秒）')
    expect(wrapper.text()).not.toContain('水印')

    vm.form.sourceTaskId = 4242
    await vm.onSubmit()
    const payload = vi.mocked(mediaApi.submitVideo).mock.calls[0][0]
    expect(payload.model).toBe(mmRegen.modelId)
    expect(payload.sourceTaskId).toBe(4242)
    expect(payload.prompt).toBeUndefined()
    expect(payload.ratio).toBeUndefined()
    expect(payload.resolution).toBeUndefined()
    expect(payload.duration).toBeUndefined()
  })

  /** M4：context-ir 表单隐藏分辨率/水印/音频；预估走 promptChars（CHAT 公式）。 */
  it('m4 context-ir hides resolution and estimates by promptChars', async () => {
    const wrapper = await mountView()
    const vm = wrapper.vm as unknown as {
      form: { model: string; prompt: string }
      applyCapabilityConstraints: () => void
      loadEstimate: () => Promise<void>
    }

    vm.form.model = mmCtxIr.modelId
    vm.form.prompt = '一只猫在霓虹街头' // 8 字：前端传原始字符数，后端 CHAT 公式换算 ceil(8×0.75)=6
    vm.applyCapabilityConstraints()
    await flushPromises()

    // 只断言表单区（历史列表列头也有「分辨率」字样）
    const formText = wrapper.find('.video-gen__form').text()
    expect(formText).not.toContain('分辨率')
    expect(formText).not.toContain('水印')

    await vm.loadEstimate()
    // 用 lastCall 断言（上一用例遗留的 400ms 防抖定时器可能在清除 mock 后才触发补一枪）
    const calls = vi.mocked(mediaApi.estimatePreview).mock.calls
    const call = calls[calls.length - 1]?.[0]
    expect(call).toMatchObject({
      kind: 'VIDEO',
      model: mmCtxIr.modelId,
      promptChars: 8
    })
    expect(call?.resolution).toBeUndefined()
    expect(call?.videoSeconds).toBeUndefined()
  })

  /** M5：context-ir 结果=增强文本（同一下载端点拉文本展示+下载 .md）。 */
  it('m5 context-ir succeeded task renders text result instead of video player', async () => {
    const { fetchMediaText } = await import('@/api/media')
    vi.mocked(fetchMediaText).mockResolvedValue('增强后的专业提示词')
    const wrapper = await mountView()
    const vm = wrapper.vm as unknown as {
      setActiveTask: (t: MediaTaskVO) => void
      contextIrText: string | null
    }

    vm.setActiveTask({
      ...historyTask, id: 55, status: 'SUCCEEDED', taskType: 'CONTEXT_IR',
      model: mmCtxIr.modelId, videoUrl: '/api/media/tasks/55/download'
    })
    await flushPromises()

    expect(vm.contextIrText).toBe('增强后的专业提示词')
    expect(wrapper.text()).toContain('增强后的专业提示词')
    expect(wrapper.text()).toContain('下载 .md')
    expect(wrapper.text()).not.toContain('下载视频')
    expect(wrapper.text()).not.toContain('入库到资产库')
  })
})
