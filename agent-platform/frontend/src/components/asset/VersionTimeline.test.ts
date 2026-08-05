import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import VersionTimeline from './VersionTimeline.vue'
import { versionApi } from '@/api/assets'
import type { AxiosResponse } from 'axios'
import type { VersionVO } from '@/types/asset'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/assets', () => ({
  versionApi: { list: vi.fn(), get: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function mkVersion(version: number): VersionVO {
  return { id: version * 10, assetId: 5, version, changeNote: `note v${version}`, createdAt: '2026-08-05' }
}

describe('VersionTimeline (S10-10b)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(versionApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [mkVersion(2), mkVersion(1)] })
    )
    vi.mocked(versionApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: { ...mkVersion(1), content: '{"v":1}' } })
    )
  })

  it('加载版本列表（倒序）', async () => {
    const wrapper = mount(VersionTimeline, { props: { assetId: 5, assetCurrentVersion: 2 } })
    await Promise.resolve()
    await Promise.resolve()
    expect(versionApi.list).toHaveBeenCalledWith(5)
    expect((wrapper.vm as unknown as { versions: VersionVO[] }).versions.map((v) => v.version)).toEqual([2, 1])
  })

  it('点选版本 → versionApi.get 拉只读内容', async () => {
    const wrapper = mount(VersionTimeline, { props: { assetId: 5, assetCurrentVersion: 2 } })
    await Promise.resolve()
    await Promise.resolve()
    const vm = wrapper.vm as unknown as { viewVersion: (v: number) => Promise<void>; detailContent: string | null }
    await vm.viewVersion(1)
    expect(versionApi.get).toHaveBeenCalledWith(5, 1)
    expect(vm.detailContent).toContain('"v": 1')
  })
})
