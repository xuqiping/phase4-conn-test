import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { NSelect } from 'naive-ui'
import type { AxiosResponse } from 'axios'
import AssetFilePicker from './AssetFilePicker.vue'
import { assetApi, projectApi, publicPoolApi } from '@/api/assets'
import type { PublicProjectSummaryVO } from '@/types/asset'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/assets', () => ({
  projectApi: { list: vi.fn() },
  publicPoolApi: { list: vi.fn() },
  assetApi: { list: vi.fn() },
  assetBridgeApi: { resolve: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function pubProject(id: number, name: string, over: Partial<PublicProjectSummaryVO> = {}): PublicProjectSummaryVO {
  return {
    id, name, publicAccessMode: 'OPEN', publishedBy: 5, publisherUsername: 'pub-a',
    publishedAt: '2026-08-10T00:00:00Z', publishedByAdmin: false, assetCount: 3,
    myRequestStatus: null, usable: true, ...over
  }
}

function mountPicker(mediaType: '图片' | '视频' = '图片') {
  return mount(AssetFilePicker, {
    props: { show: true, mediaType, max: 4 },
    global: { stubs: { teleport: true } }
  })
}

/** 当前来源下项目下拉的 options（NSelect[0]）。 */
function selectOptions(wrapper: ReturnType<typeof mountPicker>) {
  return wrapper.findAllComponents(NSelect)[0].props('options') as { label: string; value: number }[]
}

describe('AssetFilePicker（2x#4 公共池来源）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(projectApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [{ id: 1, name: '我的项目', ownerId: 9, role: 'OWNER', createdAt: '2026-08-01' } as never] })
    )
    vi.mocked(publicPoolApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [
        pubProject(11, '开放图库', { mediaTypes: '["图片","视频"]' }),
        pubProject(12, '仅视频库', { mediaTypes: '["视频"]' }),
        pubProject(13, '待审批库', { usable: false, publicAccessMode: 'APPROVAL_REQUIRED', myRequestStatus: 'PENDING' })
      ] })
    )
    vi.mocked(assetApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: { records: [], total: 0, page: 1, size: 100, pages: 0 } })
    )
  })

  it('打开即拉本地+公共池两来源；默认本地来源选项来自我的项目', async () => {
    const wrapper = mountPicker()
    await flushPromises()

    expect(projectApi.list).toHaveBeenCalledOnce()
    expect(publicPoolApi.list).toHaveBeenCalledOnce()
    expect(selectOptions(wrapper)).toEqual([{ label: '我的项目', value: 1 }])
  })

  it('切公共池：仅展示 usable 且 mediaTypes 匹配的项目（待审批不出现）', async () => {
    const wrapper = mountPicker('图片')
    await flushPromises()
    const vm = wrapper.vm as unknown as { switchSource: (s: 'local' | 'public') => void }
    vm.switchSource('public')
    await flushPromises()

    const opts = selectOptions(wrapper)
    // 仅视频库不含图片类型、待审批库 usable=false → 都不出现
    expect(opts.map(o => o.value)).toEqual([11])
    expect(opts[0].label).toContain('pub-a')
  })

  it('mediaTypes 缺省/解析失败的项目不过滤照常展示', async () => {
    vi.mocked(publicPoolApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [
        pubProject(21, '无类型标注'),
        pubProject(22, '坏json', { mediaTypes: '{bad' })
      ] })
    )
    const wrapper = mountPicker('音频' as never)
    await flushPromises()
    ;(wrapper.vm as unknown as { switchSource: (s: 'local' | 'public') => void }).switchSource('public')
    await flushPromises()

    expect(selectOptions(wrapper).map(o => o.value)).toEqual([21, 22])
  })

  it('选公共池项目后按 type 拉资产（复用既有 assetApi.list，ACL 后端放行）', async () => {
    const wrapper = mountPicker('图片')
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      switchSource: (s: 'local' | 'public') => void
      onProjectChange: () => void
      projectId: number | null
    }
    vm.switchSource('public')
    await flushPromises()
    vm.projectId = 11
    vm.onProjectChange()
    await flushPromises()

    expect(assetApi.list).toHaveBeenCalledWith(11, {
      type: '图片', q: undefined, page: 1, size: 100
    })
  })

  it('切回本地来源清空公共池选择（L1 反向）', async () => {
    const wrapper = mountPicker('图片')
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      switchSource: (s: 'local' | 'public') => void
      selectedIds: number[]
      projectId: number | null
    }
    vm.switchSource('public')
    await flushPromises()
    vm.projectId = 11
    vm.selectedIds = [101]
    vm.switchSource('local')
    await flushPromises()

    expect(vm.projectId).toBeNull()
    expect(vm.selectedIds).toEqual([])
  })

  it('资产列表加载失败有兜底提示（公共项目被移出公众池场景）', async () => {
    vi.mocked(assetApi.list).mockRejectedValueOnce(new Error('403'))
    const wrapper = mountPicker('图片')
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      switchSource: (s: 'local' | 'public') => void
      onProjectChange: () => void
      projectId: number | null
    }
    vm.switchSource('public')
    await flushPromises()
    vm.projectId = 11
    vm.onProjectChange()
    await flushPromises()

    expect(messageMock.error).toHaveBeenCalledWith('资产列表加载失败')
  })
})
