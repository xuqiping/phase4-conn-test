import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ConsistencyPack from './ConsistencyPack.vue'
import { versionApi } from '@/api/assets'
import type { AxiosResponse } from 'axios'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/assets', () => ({
  versionApi: { saveConsistencyPack: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

describe('ConsistencyPack (S10-10b)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(versionApi.saveConsistencyPack).mockResolvedValue(
      response({ code: 200, message: 'ok', data: undefined as never })
    )
  })

  it('initial 注入表单', async () => {
    const wrapper = mount(ConsistencyPack, {
      props: {
        assetId: 5,
        canEdit: true,
        initial: {
          mainRefImageFileId: 'fid-main',
          galleryFileIds: ['g1', 'g2'],
          standardDescription: 'desc',
          paramBaseline: '{"seed":1}'
        }
      }
    })
    await Promise.resolve()
    const form = (wrapper.vm as unknown as { form: { mainRefImageFileId: string; galleryFileIds: string[]; standardDescription: string; paramBaseline: string } }).form
    expect(form.mainRefImageFileId).toBe('fid-main')
    expect(form.galleryFileIds).toEqual(['g1', 'g2'])
    expect(form.standardDescription).toBe('desc')
  })

  it('保存调 saveConsistencyPack（空串原样传=清空）+ emit saved', async () => {
    const wrapper = mount(ConsistencyPack, {
      props: { assetId: 5, canEdit: true, initial: { mainRefImageFileId: 'fid', galleryFileIds: ['g1'] } }
    })
    await Promise.resolve()
    const vm = wrapper.vm as unknown as {
      form: { mainRefImageFileId: string; standardDescription: string; paramBaseline: string }
      save: () => Promise<void>
    }
    // standardDescription / paramBaseline 留空 → 空串原样传（后端空串=清空，区别于 undefined=不改）
    await vm.save()
    expect(versionApi.saveConsistencyPack).toHaveBeenCalledWith(5, {
      mainRefImageFileId: 'fid',
      galleryFileIds: ['g1'],
      standardDescription: '',
      paramBaseline: ''
    })
    expect(wrapper.emitted('saved')).toBeTruthy()
    expect(wrapper.emitted('saved')?.[0]).toEqual([5])
  })
})
