import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AssetProjectView from './AssetProjectView.vue'
import { projectApi, assetApi } from '@/api/assets'
import { useAuthStore } from '@/stores/auth'
import type { AxiosResponse } from 'axios'
import type { AssetProjectVO, AssetVO } from '@/types/asset'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '7' } }),
  useRouter: () => ({ push: vi.fn() })
}))

vi.mock('@/api/assets', () => ({
  projectApi: { get: vi.fn() },
  assetApi: { list: vi.fn(), countMatrix: vi.fn(), create: vi.fn(), upload: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function mkProject(role: 'OWNER' | 'EDITOR' | 'VIEWER'): AssetProjectVO {
  return {
    id: 7,
    name: '短剧第一季',
    description: 'desc',
    ownerId: 1,
    narrativeRoles: ['人物', '道具', '场景'],
    role,
    createdAt: '2026-08-05'
  }
}

function mkAsset(id: number, over: Partial<AssetVO> = {}): AssetVO {
  return {
    id,
    projectId: 7,
    mediaType: 'IMAGE',
    name: `资产${id}`,
    description: '',
    tags: [],
    status: 'DRAFT',
    content: null,
    genMeta: null,
    currentVersion: 1,
    roleKeys: ['人物'],
    createdAt: '2026-08-05',
    ...over
  }
}

function pageResp(records: AssetVO[]) {
  return response({
    code: 200,
    message: 'ok',
    data: { records, total: records.length, page: 1, size: 24 }
  })
}

async function settle() {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

async function mountView(permissions: string[], role: 'OWNER' | 'EDITOR' | 'VIEWER' = 'OWNER') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const authStore = useAuthStore()
  authStore.userInfo = {
    id: 1,
    username: 'tester',
    email: null,
    avatar: null,
    roles: ['tester'],
    permissions
  }
  vi.mocked(projectApi.get).mockResolvedValue(response({ code: 200, message: 'ok', data: mkProject(role) }))
  vi.mocked(assetApi.list).mockResolvedValue(pageResp([mkAsset(1), mkAsset(2)]))
  vi.mocked(assetApi.countMatrix).mockResolvedValue(
    response({ code: 200, message: 'ok', data: { cells: [], typeTotals: [] } })
  )
  const wrapper = mount(AssetProjectView, { global: { plugins: [pinia] } })
  await settle()
  return wrapper
}

describe('AssetProjectView (S11 项目详情页)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('挂载拉 project + assets + matrix', async () => {
    const wrapper = await mountView(['asset:write'])
    expect(projectApi.get).toHaveBeenCalledWith(7)
    expect(assetApi.list).toHaveBeenCalledWith(7, expect.objectContaining({ page: 1, size: 24 }))
    expect(assetApi.countMatrix).toHaveBeenCalledWith(7)
    const vm = wrapper.vm as unknown as { project: AssetProjectVO; assets: AssetVO[] }
    expect(vm.project.id).toBe(7)
    expect(vm.assets.length).toBe(2)
  })

  it('无 asset:write 渲染 403 兜底', async () => {
    const wrapper = await mountView([])
    expect((wrapper.vm as unknown as { canEdit: boolean }).canEdit).toBe(false)
    expect(wrapper.text()).toContain('无 asset:write 权限')
  })

  it('viewer 角色 canWrite=false（隐藏写按钮）', async () => {
    const wrapper = await mountView(['asset:write'], 'VIEWER')
    expect((wrapper.vm as unknown as { canWrite: boolean }).canWrite).toBe(false)
  })

  it('筛选变化 → assetApi.list 带 type/role/q', async () => {
    const wrapper = await mountView(['asset:write'])
    vi.clearAllMocks()
    const vm = wrapper.vm as unknown as { filter: { type?: string; role?: string; q?: string } }
    vm.filter = { type: 'IMAGE', role: '人物', q: '老板娘' }
    await settle()
    expect(assetApi.list).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ type: 'IMAGE', role: '人物', q: '老板娘', page: 1 })
    )
  })

  it('新建文本资产调 assetApi.create', async () => {
    const wrapper = await mountView(['asset:write'])
    const vm = wrapper.vm as unknown as {
      openCreate: () => void
      form: { mediaType: 'PROMPT' | 'SCRIPT'; name: string; description: string; content: string; roleKeys: string[] }
      submitCreate: () => Promise<void>
    }
    vm.openCreate()
    vm.form.mediaType = 'PROMPT'
    vm.form.name = '人物提示词'
    vm.form.content = '一位老板娘'
    vm.form.roleKeys = ['人物']
    await vm.submitCreate()
    expect(assetApi.create).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ mediaType: 'PROMPT', name: '人物提示词', content: '一位老板娘', roleKeys: ['人物'] })
    )
  })

  it('inferMediaType 按 mime 映射 IMAGE/VIDEO/AUDIO/不支持=null', async () => {
    const wrapper = await mountView(['asset:write'])
    const vm = wrapper.vm as unknown as { inferMediaType: (m: string) => string | null }
    expect(vm.inferMediaType('image/png')).toBe('IMAGE')
    expect(vm.inferMediaType('video/mp4')).toBe('VIDEO')
    expect(vm.inferMediaType('audio/mpeg')).toBe('AUDIO')
    expect(vm.inferMediaType('application/pdf')).toBeNull()
  })

  it('上传文件调 assetApi.upload（按 mime 推断 IMAGE）', async () => {
    const wrapper = await mountView(['asset:write'])
    const vm = wrapper.vm as unknown as { onFileChange: (e: Event) => Promise<void> }
    const file = new File(['x'], 'a.png', { type: 'image/png' })
    const input = document.createElement('input')
    Object.defineProperty(input, 'files', { value: [file], configurable: true })
    await vm.onFileChange({ target: input } as unknown as Event)
    expect(assetApi.upload).toHaveBeenCalledWith(7, file, 'IMAGE', expect.objectContaining({ name: 'a.png' }))
  })

  it('drawer changed → 重载 list+matrix（L2/L3 联动）', async () => {
    const wrapper = await mountView(['asset:write'])
    vi.clearAllMocks()
    const vm = wrapper.vm as unknown as { onDetailChanged: () => Promise<void> }
    await vm.onDetailChanged()
    expect(assetApi.list).toHaveBeenCalled()
    expect(assetApi.countMatrix).toHaveBeenCalled()
  })
})
