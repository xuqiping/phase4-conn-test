import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import StoryboardFields from './StoryboardFields.vue'
import { assetApi } from '@/api/assets'
import type { AxiosResponse } from 'axios'
import type { AssetVO } from '@/types/asset'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/assets', () => ({
  assetApi: { list: vi.fn(), saveStoryboard: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function mkAsset(over: Partial<AssetVO> = {}): AssetVO {
  return {
    id: 5,
    projectId: 7,
    mediaType: '分镜',
    mediaCategory: 'TEXT',
    name: '镜头1',
    status: 'DRAFT',
    content: null,
    currentVersion: 1,
    createdAt: '2026-08-06',
    ...over
  }
}

describe('StoryboardFields (S18)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(assetApi.list).mockResolvedValue(response({ code: 200, message: 'ok', data: { records: [], total: 0, page: 1, size: 200 } }))
    vi.mocked(assetApi.saveStoryboard).mockResolvedValue(response({ code: 200, message: 'ok', data: mkAsset() }))
  })

  it('解析 content → 填 prompt/entityRefs（含镜头号）', async () => {
    vi.mocked(assetApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: { records: [], total: 0, page: 1, size: 200 } })
    )
    const wrapper = mount(StoryboardFields, {
      props: {
        asset: mkAsset({
          content: JSON.stringify({
            shotIndex: 3,
            parentId: 50,
            prompt: '远景城市天际线',
            entityRefs: [{ key: '主角', assetId: 7, name: '主角图', mediaType: '图片' }]
          })
        }),
        canEdit: true
      }
    })
    await flushPromises()
    const vm = wrapper.vm as unknown as { prompt: string; entityRefs: { key: string }[] }
    expect(vm.prompt).toBe('远景城市天际线')
    expect(vm.entityRefs).toHaveLength(1)
    expect(vm.entityRefs[0].key).toBe('主角')
    expect(wrapper.text()).toContain('#3')
  })

  it('saveAll → assetApi.saveStoryboard 提交 prompt/entityRefs/videoInputs', async () => {
    const wrapper = mount(StoryboardFields, {
      props: { asset: mkAsset({ content: JSON.stringify({ prompt: '旧' }) }), canEdit: true }
    })
    await flushPromises()
    const vm = wrapper.vm as unknown as { prompt: string; saveAll: () => Promise<void> }
    vm.prompt = '新提示词'
    await vm.saveAll()
    expect(assetApi.saveStoryboard).toHaveBeenCalledWith(5, expect.objectContaining({ prompt: '新提示词' }))
    expect(messageMock.success).toHaveBeenCalled()
  })

  it('dirty 检测：未改不调 saveStoryboard', async () => {
    const wrapper = mount(StoryboardFields, {
      props: { asset: mkAsset({ content: JSON.stringify({ prompt: '原' }) }), canEdit: true }
    })
    await flushPromises()
    const vm = wrapper.vm as unknown as { saveAll: () => Promise<void> }
    await vm.saveAll()
    expect(assetApi.saveStoryboard).not.toHaveBeenCalled()
  })

  it('canEdit=false → dirty 也不保存', async () => {
    const wrapper = mount(StoryboardFields, {
      props: { asset: mkAsset({ content: JSON.stringify({ prompt: '原' }) }), canEdit: false }
    })
    await flushPromises()
    const vm = wrapper.vm as unknown as { prompt: string; saveAll: () => Promise<void> }
    vm.prompt = '改'
    await vm.saveAll()
    expect(assetApi.saveStoryboard).not.toHaveBeenCalled()
  })

  it('被引资产不在候选目录 → RefList 显「资产已删」红标（L16）', async () => {
    // 项目资产列表不含 id=999
    vi.mocked(assetApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: { records: [], total: 0, page: 1, size: 200 } })
    )
    const wrapper = mount(StoryboardFields, {
      props: {
        asset: mkAsset({
          content: JSON.stringify({
            prompt: 'x',
            entityRefs: [{ key: '已删', assetId: 999 }]
          })
        }),
        canEdit: true
      }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('资产已删')
  })
})
