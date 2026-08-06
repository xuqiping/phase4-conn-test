import { describe, expect, it, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AssetCard from './AssetCard.vue'
import type { AssetVO } from '@/types/asset'

// fetchFilePreview 打桩（C2 缩略图）；真实网络不触达
const fetchMock = vi.fn<(id: string) => Promise<string>>()
vi.mock('@/api/file', () => ({
  fetchFilePreview: (id: string) => fetchMock(id)
}))

// IntersectionObserver polyfill：捕获 callback 手动触发
type IOCB = (entries: { isIntersecting: boolean }[]) => void
let ioCbs: IOCB[] = []
beforeEach(() => {
  ioCbs = []
  fetchMock.mockReset()
  fetchMock.mockImplementation(async id => `blob:${id}`)
  ;(globalThis as unknown as { IntersectionObserver: unknown }).IntersectionObserver = class {
    constructor(cb: IOCB) {
      ioCbs.push(cb)
    }
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})
function intersect(v: boolean) {
  for (const cb of ioCbs) cb([{ isIntersecting: v }])
}

function mkAsset(over: Partial<AssetVO> = {}): AssetVO {
  return {
    id: 1,
    projectId: 7,
    mediaType: '图片',
    mediaCategory: 'IMAGE',
    name: '老板娘定妆',
    description: '主参考图',
    tags: [],
    status: 'DRAFT',
    content: null,
    genMeta: null,
    currentVersion: 2,
    roleKeys: ['人物'],
    createdAt: '2026-08-05',
    ...over
  }
}

describe('AssetCard (S11)', () => {
  it('渲染名称/版本/类型/角色徽标', () => {
    const wrapper = mount(AssetCard, { props: { asset: mkAsset() } })
    expect(wrapper.text()).toContain('老板娘定妆')
    expect(wrapper.text()).toContain('v2')
    expect(wrapper.text()).toContain('图片')
    expect(wrapper.text()).toContain('人物')
    expect(wrapper.text()).toContain('草稿')
  })

  it('角色超 3 个聚合 +N', () => {
    const wrapper = mount(AssetCard, {
      props: { asset: mkAsset({ roleKeys: ['人物', '道具', '场景', '风格', '通用'] }) }
    })
    expect(wrapper.text()).toContain('+2')
  })

  it('点击 emit open（带原资产）', async () => {
    const asset = mkAsset()
    const wrapper = mount(AssetCard, { props: { asset } })
    await wrapper.trigger('click')
    expect(wrapper.emitted('open')).toBeTruthy()
    expect((wrapper.emitted('open')![0][0] as AssetVO).id).toBe(asset.id)
  })

  it('IMAGE 进入视口拉缩略图渲 <img>（C2）', async () => {
    const wrapper = mount(AssetCard, { props: { asset: mkAsset({ fileId: 'thumb-1' }) } })
    await flushPromises()
    // 视口外：图标兜底，无 img
    expect(wrapper.find('img.asset-card__cover-media').exists()).toBe(false)
    expect(fetchMock).not.toHaveBeenCalled()
    intersect(true)
    await flushPromises()
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledWith('thumb-1')
    const img = wrapper.find('img.asset-card__cover-media')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('blob:thumb-1')
  })

  it('无 fileId 回退色块图标（C2）', async () => {
    const wrapper = mount(AssetCard, { props: { asset: mkAsset({ fileId: undefined }) } })
    await flushPromises()
    intersect(true)
    await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.find('img.asset-card__cover-media').exists()).toBe(false)
    expect(wrapper.find('.asset-card__cover-icon').exists()).toBe(true)
  })

  it('VIDEO 进入视口渲 <video>（C2）', async () => {
    const wrapper = mount(AssetCard, {
      props: { asset: mkAsset({ mediaType: '视频', mediaCategory: 'VIDEO', fileId: 'vid-1' }) }
    })
    await flushPromises()
    intersect(true)
    await flushPromises()
    await flushPromises()
    const video = wrapper.find('video.asset-card__cover-media')
    expect(video.exists()).toBe(true)
    expect(video.attributes('src')).toBe('blob:vid-1')
  })
})
