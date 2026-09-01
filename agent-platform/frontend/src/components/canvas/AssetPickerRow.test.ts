import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { NButton } from 'naive-ui'
import Lightbox from './Lightbox.vue'
import AssetPickerRow from './AssetPickerRow.vue'
import type { AssetVO } from '@/types/asset'

// 修复X B2（2x 未解决②）：行交互三分离——点缩略=预览（图/视 Lightbox）、点「选择」=选定、
// 行空白/音频条不动作。懒加载组合式 mock 成可控 url/failed（每用例独立 ref）。
let urlRef = ref<string | null>(null)
let failedRef = ref(false)
vi.mock('@/composables/useLazyFilePreview', () => ({
  useLazyFilePreview: () => ({ url: urlRef, failed: failedRef })
}))

function mkAsset(over: Partial<AssetVO> = {}): AssetVO {
  return {
    id: 1, projectId: 10, mediaType: '图片', name: '资产1', status: 'DRAFT',
    currentVersion: 2, roleKeys: ['人物'], content: null, genMeta: null,
    createdAt: '2026-08-05', ...over
  }
}

function mountRow(over: Partial<AssetVO> = {}) {
  return mount(AssetPickerRow, { props: { asset: mkAsset(over) }, attachTo: document.body })
}

beforeEach(() => {
  urlRef = ref<string | null>(null)
  failedRef = ref(false)
})
afterEach(() => { document.body.innerHTML = '' })

describe('AssetPickerRow · 修复X B2 四态缩略', () => {
  it('图片行：缩略 img + 悬浮基座 + 预览按钮可达（aria-label）', () => {
    urlRef.value = 'blob:img'
    const wrapper = mountRow()
    const thumb = wrapper.find('.picker-row__thumb')
    expect(thumb.element.tagName).toBe('BUTTON')
    expect(thumb.attributes('aria-label')).toBe('预览 资产1')
    expect(wrapper.find('img').attributes('src')).toBe('blob:img')
    wrapper.unmount()
  })

  it('视频行：缩略 video（preload=metadata）+ ▶ 角标', () => {
    urlRef.value = 'blob:vid'
    const wrapper = mountRow({ mediaType: '视频' })
    const video = wrapper.find('.picker-row__thumb video')
    expect(video.attributes('src')).toBe('blob:vid')
    expect(video.attributes('preload')).toBe('metadata')
    expect(wrapper.find('.picker-row__play').text()).toBe('▶')
    expect(wrapper.find('img').exists()).toBe(false)
    wrapper.unmount()
  })

  it('音频行：「音」字标 + 行内 audio 条（preload=none），无 img/video 缩略', () => {
    urlRef.value = 'blob:aud'
    const wrapper = mountRow({ mediaType: '音频', fileId: 'f-aud' })
    const thumb = wrapper.find('.picker-row__thumb')
    expect(thumb.element.tagName).toBe('DIV') // 无灯箱语义 → 静态块
    expect(thumb.find('.picker-row__ph').text()).toBe('音')
    const audio = wrapper.find('audio.picker-row__audio')
    expect(audio.attributes('src')).toBe('blob:aud')
    expect(audio.attributes('preload')).toBe('none')
    expect(wrapper.find('.picker-row__thumb img').exists()).toBe(false)
    wrapper.unmount()
  })

  it('文本行：有 textPreview 显片段（省略号多行截断类），无片段回落类型字标', () => {
    const withText = mountRow({ mediaType: '提示词', textPreview: '一个少年在雨夜奔跑' })
    expect(withText.find('.picker-row__thumb-text').text()).toBe('一个少年在雨夜奔跑')
    withText.unmount()

    const noText = mountRow({ mediaType: '剧本', textPreview: null })
    expect(noText.find('.picker-row__thumb .picker-row__ph').text()).toBe('剧')
    noText.unmount()
  })

  it('预览失败/未就绪：回落类型字标，点缩略不开 Lightbox', async () => {
    failedRef.value = true
    const wrapper = mountRow({ mediaType: '图片' })
    expect(wrapper.find('.picker-row__ph').text()).toBe('图')
    await wrapper.find('.picker-row__thumb').trigger('click')
    expect(wrapper.findComponent(Lightbox).props('open')).toBe(false)
    wrapper.unmount()
  })

  it('文本类不启用文件拉取（enabled=false 交给组合式；此处锁 UI 侧无 img/video/audio 元素）', () => {
    const wrapper = mountRow({ mediaType: '分镜', fileId: 'f-txt' })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('video').exists()).toBe(false)
    expect(wrapper.find('audio').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('AssetPickerRow · 修复X B2 交互三分离', () => {
  it('点缩略（图/视）→ 开 Lightbox 且不触发 pick', async () => {
    urlRef.value = 'blob:img'
    const wrapper = mountRow()
    await wrapper.find('.picker-row__thumb').trigger('click')
    expect(wrapper.findComponent(Lightbox).props('open')).toBe(true)
    expect(wrapper.findComponent(Lightbox).props('kind')).toBe('image')
    expect(wrapper.emitted('pick')).toBeFalsy()

    await wrapper.findComponent(Lightbox).vm.$emit('close')
    expect(wrapper.findComponent(Lightbox).props('open')).toBe(false)
    wrapper.unmount()
  })

  it('视频缩略点击开灯箱 kind=video（缩略为原生 BUTTON，Enter 激活由浏览器默认行为保证）', async () => {
    urlRef.value = 'blob:vid'
    const wrapper = mountRow({ mediaType: '视频' })
    const thumb = wrapper.find('.picker-row__thumb')
    expect(thumb.element.tagName).toBe('BUTTON')
    await thumb.trigger('click')
    expect(wrapper.findComponent(Lightbox).props('open')).toBe(true)
    expect(wrapper.findComponent(Lightbox).props('kind')).toBe('video')
    wrapper.unmount()
  })

  it('点「选择」→ emit pick 携带原资产；loading 态透传', async () => {
    // loading 态以 NButton prop 断言（happy-dom 下 spinner 占位不影响文案口径）
    const loadingWrapper = mount(AssetPickerRow, { props: { asset: mkAsset(), picking: true } })
    expect(loadingWrapper.findComponent(NButton).props('loading')).toBe(true)
    loadingWrapper.unmount()

    const wrapper = mount(AssetPickerRow, { props: { asset: mkAsset() } })
    const btn = wrapper.findAll('button').find((b) => b.text().includes('选择'))
    expect(btn).toBeTruthy()
    await btn!.trigger('click')
    const emitted = wrapper.emitted('pick')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toMatchObject({ id: 1, name: '资产1' })
    wrapper.unmount()
  })

  it('行空白点击 / 点 audio 条：零 pick（防误选口径）', async () => {
    urlRef.value = 'blob:aud'
    const wrapper = mountRow({ mediaType: '音频', fileId: 'f-aud' })
    await wrapper.find('.picker-row').trigger('click')
    expect(wrapper.emitted('pick')).toBeFalsy()
    await wrapper.find('audio').trigger('click')
    expect(wrapper.emitted('pick')).toBeFalsy()
    wrapper.unmount()
  })

  it('ARCHIVED 半透明回归 + meta 含版本/状态/角色', () => {
    const wrapper = mountRow({ status: 'ARCHIVED' })
    expect(wrapper.find('.picker-row--archived').exists()).toBe(true)
    expect(wrapper.find('.picker-row__meta').text()).toContain('v2')
    expect(wrapper.find('.picker-row__meta').text()).toContain('已归档')
    expect(wrapper.find('.picker-row__meta').text()).toContain('人物')
    wrapper.unmount()
  })
})
