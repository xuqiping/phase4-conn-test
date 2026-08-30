import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { AxiosResponse } from 'axios'
import { NCheckboxGroup, NRadioGroup } from 'naive-ui'
import VideoReversePanel from './VideoReversePanel.vue'
import {
  mediaApi, type MediaTaskVO, type ReverseAnalyzeResult
} from '@/api/media'

const messageMock = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }

vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/file', () => ({ fetchFilePreview: vi.fn().mockResolvedValue('blob:frame') }))

vi.mock('@/api/media', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/media')>()
  return {
    ...actual,
    mediaApi: {
      ...actual.mediaApi,
      listTasks: vi.fn(),
      uploadAttachment: vi.fn(),
      reverseAnalyze: vi.fn(),
      reverseLocalize: vi.fn()
    }
  }
})

function response<T>(data: T): AxiosResponse<{ code: number; message: string; data: T }> {
  return { data: { code: 200, message: 'ok', data }, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

const doneTask: MediaTaskVO = {
  id: 44, status: 'SUCCEEDED', statusFlag: null, taskType: 'TEXT2VIDEO', model: 'seedance',
  prompt: '一只橘猫晒太阳', duration: 5, ratio: '16:9', resolution: '720p', watermark: false,
  generateAudio: false, inputAttachments: [], hasReference: false, submittedRequest: null,
  providerRequestSnapshot: null, tokensCost: null, errorMsg: null,
  videoUrl: '/api/media/tasks/44/download', resultFileId: 'res-44', imageUrls: null,
  imageFileIds: null, generatedImages: null, outputTokens: null, size: null, outputFormat: null,
  createdAt: '2026-08-17T10:00:00+08:00', updatedAt: null
}

const analyzeResult: ReverseAnalyzeResult = {
  keyframes: [
    { fileId: 'kf-1', thumbFileId: 'kf-1', timestampSec: 1.5, shotNo: 1 },
    { fileId: 'kf-2', thumbFileId: 'kf-2', timestampSec: 5.2, shotNo: 2 }
  ],
  durationSeconds: 12.5,
  mode: 'SCENE',
  sceneHits: 6,
  storyboard: [{ shotNo: 1, startSec: 0, endSec: 6.2, shotSize: '远景', cameraMove: '固定', description: '橘猫出场', dialogue: '' }],
  script: { scenes: [{ sceneHeading: '外景-窗台-白天', action: '橘猫晒太阳', dialogue: [] }], synopsis: '橘猫日常' },
  model: 'glm-4v'
}

describe('VideoReversePanel（计划6 Step4 视频反推 Tab 面板）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(mediaApi.listTasks).mockResolvedValue(
      response({ records: [doneTask], total: 1, page: 1, size: 50, pages: 1 }))
  })

  function mountPanel() {
    return mount(VideoReversePanel)
  }

  it('默认渲染：来源两模式 + 产物勾选默认仅关键帧 + 无来源禁用开始反推', () => {
    const wrapper = mountPanel()
    expect(wrapper.text()).toContain('上传本地视频')
    expect(wrapper.text()).toContain('从历史任务选')
    expect(wrapper.text()).toContain('反推产物')
    const btn = wrapper.findAll('button').find(b => b.text().includes('开始反推'))!
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('历史任务源 + 反推三产物：analyze 走 taskId → 时间轴/分镜表/剧本渲染', async () => {
    vi.mocked(mediaApi.reverseAnalyze).mockResolvedValue(response(analyzeResult))
    const wrapper = mountPanel()
    const vm = wrapper.vm as unknown as { selectedTaskId: number | null }
    // 源切换到历史任务（触发懒加载）并选中
    wrapper.findComponent(NRadioGroup).vm.$emit('update:value', 'task')
    await flushPromises()
    vm.selectedTaskId = 44
    await flushPromises()

    // 勾上分镜/剧本（复用 checkbox group v-model）
    wrapper.findComponent(NCheckboxGroup).vm.$emit('update:value', ['KEYFRAMES', 'STORYBOARD', 'SCRIPT'])
    await flushPromises()
    const btn = wrapper.findAll('button').find(b => b.text().includes('开始反推'))!
    await btn.trigger('click')
    await flushPromises()

    expect(mediaApi.reverseAnalyze).toHaveBeenCalledWith(
      expect.objectContaining({ taskId: 44, modes: ['KEYFRAMES', 'STORYBOARD', 'SCRIPT'] }),
      expect.any(AbortSignal)
    )
    const text = wrapper.text()
    expect(text).toContain('反推结果')
    expect(text).toContain('2 帧')
    expect(text).toContain('场景检测')
    expect(text).toContain('分镜表（1 镜）')
    expect(text).toContain('橘猫日常')
    expect(wrapper.findAll('img.video-reverse__frame-img').length).toBe(2)
  })

  it('仅关键帧不调 LLM 产物字段：storyboard/script 缺失时不渲染对应区', async () => {
    vi.mocked(mediaApi.reverseAnalyze).mockResolvedValue(response({
      ...analyzeResult, storyboard: null, script: null
    }))
    const wrapper = mountPanel()
    const vm = wrapper.vm as unknown as { selectedTaskId: number | null }
    wrapper.findComponent(NRadioGroup).vm.$emit('update:value', 'task')
    await flushPromises()
    vm.selectedTaskId = 44
    await flushPromises()
    const btn = wrapper.findAll('button').find(b => b.text().includes('开始反推'))!
    await btn.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('反推结果')
    expect(wrapper.text()).not.toContain('分镜表（')
    expect(wrapper.findComponent({ name: 'NAlert' }).exists()).toBe(false)
  })

  it('转绘：localize 结果渲染改写剧本+替换清单，warning 显著标注', async () => {
    vi.mocked(mediaApi.reverseAnalyze).mockResolvedValue(response(analyzeResult))
    vi.mocked(mediaApi.reverseLocalize).mockResolvedValue(response({
      localizedScript: '{"scenes":[{"sceneHeading":"INT-Windowsill-Day"}],"synopsis":"cat in sun"}',
      changeLog: [{ from: '窗台', to: 'Windowsill', scene: '场景1' }],
      warning: '场景数不一致：原 1 现 1x'
    }))
    const wrapper = mountPanel()
    const vm = wrapper.vm as unknown as { selectedTaskId: number | null; targetLocale: string }
    wrapper.findComponent(NRadioGroup).vm.$emit('update:value', 'task')
    await flushPromises()
    vm.selectedTaskId = 44
    wrapper.findComponent(NCheckboxGroup).vm.$emit('update:value', ['SCRIPT'])
    await flushPromises()
    const btn = wrapper.findAll('button').find(b => b.text().includes('开始反推'))!
    await btn.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('本土化转绘')
    vm.targetLocale = '美国'
    await flushPromises()
    const locBtn = wrapper.findAll('button').find(b => b.text().includes('开始转绘'))!
    await locBtn.trigger('click')
    await flushPromises()

    expect(mediaApi.reverseLocalize).toHaveBeenCalledWith(
      expect.objectContaining({ targetLocale: '美国' }))
    expect(wrapper.text()).toContain('场景数不一致')
    expect(wrapper.text()).toContain('替换清单（changeLog，1 处）')
    expect(wrapper.text()).toContain('Windowsill')
  })

  it('「用剧本生成」→ emit use-script 带剧本文本与源 fileId（本土化版）', async () => {
    vi.mocked(mediaApi.reverseAnalyze).mockResolvedValue(response(analyzeResult))
    vi.mocked(mediaApi.reverseLocalize).mockResolvedValue(response({
      localizedScript: '{"synopsis":"cat in sun"}',
      changeLog: [],
      warning: null
    }))
    const wrapper = mountPanel()
    const vm = wrapper.vm as unknown as { selectedTaskId: number | null; targetLocale: string }
    wrapper.findComponent(NRadioGroup).vm.$emit('update:value', 'task')
    await flushPromises()
    vm.selectedTaskId = 44
    wrapper.findComponent(NCheckboxGroup).vm.$emit('update:value', ['SCRIPT'])
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text().includes('开始反推'))!.trigger('click')
    await flushPromises()
    vm.targetLocale = '美国'
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text().includes('开始转绘'))!.trigger('click')
    await flushPromises()

    // 转绘后默认取本土化版
    const useBtn = wrapper.findAll('button').find(b => b.text().includes('用剧本生成'))!
    await useBtn.trigger('click')
    const events = wrapper.emitted('use-script')
    expect(events).toHaveLength(1)
    expect(events![0][0]).toMatchObject({
      promptText: expect.stringContaining('cat in sun'),
      sourceFileId: 'res-44'
    })
  })
})
