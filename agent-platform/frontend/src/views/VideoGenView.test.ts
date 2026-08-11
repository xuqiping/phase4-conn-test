import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { AxiosResponse } from 'axios'
import VideoGenView from './VideoGenView.vue'
import { mediaApi, type MediaModelVO, type MediaTaskVO } from '@/api/media'
import { useAuthStore } from '@/stores/auth'

const messageMock = { success: vi.fn(), error: vi.fn(), warning: vi.fn() }

vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
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
    mediaApi: {
      ...actual.mediaApi,
      listModels: vi.fn(),
      listTasks: vi.fn(),
      getTask: vi.fn(),
      submitVideo: vi.fn()
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
    vi.mocked(mediaApi.listTasks).mockResolvedValue(response([historyTask]))
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
