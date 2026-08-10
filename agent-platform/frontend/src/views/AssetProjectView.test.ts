import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
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
  projectApi: { get: vi.fn(), list: vi.fn(), update: vi.fn() },
  assetApi: { list: vi.fn(), countMatrix: vi.fn(), create: vi.fn(), upload: vi.fn(), copy: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function mkProject(role: 'OWNER' | 'EDITOR' | 'VIEWER', over: Partial<AssetProjectVO> = {}): AssetProjectVO {
  return {
    id: 7,
    name: '短剧第一季',
    description: 'desc',
    ownerId: 1,
    narrativeRoles: ['人物', '道具', '场景'],
    mediaTypes: [
      { key: '提示词', category: 'TEXT' },
      { key: '剧本', category: 'TEXT' },
      { key: '图片', category: 'IMAGE' },
      { key: '视频', category: 'VIDEO' },
      { key: '音频', category: 'AUDIO' }
    ],
    role,
    createdAt: '2026-08-05',
    ...over
  }
}

function mkAsset(id: number, over: Partial<AssetVO> = {}): AssetVO {
  return {
    id,
    projectId: 7,
    mediaType: '图片',
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

async function mountView(
  permissions: string[],
  role: 'OWNER' | 'EDITOR' | 'VIEWER' = 'OWNER',
  projectOver: Partial<AssetProjectVO> = {}
) {
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
  vi.mocked(projectApi.get).mockResolvedValue(response({ code: 200, message: 'ok', data: mkProject(role, projectOver) }))
  vi.mocked(projectApi.list).mockResolvedValue(response({ code: 200, message: 'ok', data: [
    mkProject('OWNER', { id: 7, name: '源项目' }),
    mkProject('OWNER', { id: 8, name: '我的项目' }),
    mkProject('EDITOR', { id: 9, name: '可编辑项目' }),
    mkProject('VIEWER', { id: 10, name: '只读项目' })
  ] }))
  vi.mocked(assetApi.list).mockResolvedValue(pageResp([mkAsset(1), mkAsset(2)]))
  vi.mocked(assetApi.countMatrix).mockResolvedValue(
    response({ code: 200, message: 'ok', data: { cells: [], typeTotals: [] } })
  )
  vi.mocked(assetApi.copy).mockResolvedValue(response({ code: 200, message: 'ok', data: mkAsset(101, { projectId: 8 }) }))
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
    expect(wrapper.text()).not.toContain('编辑分类')
    expect(wrapper.text()).not.toContain('上传文件')
    expect(wrapper.text()).not.toContain('新建提示词/剧本')
  })

  it('公共 VIEWER 保持只读，显示公共/官方徽章与每卡复制按钮', async () => {
    const wrapper = await mountView(['asset:write'], 'VIEWER', { publicPool: true, publishedByAdmin: true })
    const vm = wrapper.vm as unknown as { canWrite: boolean; isPublicViewer: boolean }
    expect(vm.canWrite).toBe(false)
    expect(vm.isPublicViewer).toBe(true)
    expect(wrapper.text()).toContain('公共项目')
    expect(wrapper.text()).toContain('官方发布')
    expect(wrapper.findAll('.asset-project__copy-button')).toHaveLength(2)
    expect(wrapper.text()).not.toContain('编辑分类')
  })

  it('非公共 VIEWER 不显示复制按钮', async () => {
    const wrapper = await mountView(['asset:write'], 'VIEWER', { publicPool: false })
    expect((wrapper.vm as unknown as { isPublicViewer: boolean }).isPublicViewer).toBe(false)
    expect(wrapper.find('.asset-project__copy-button').exists()).toBe(false)
  })

  it('真实复制按钮打开弹窗并过滤 OWNER/EDITOR、排除源项目与 VIEWER', async () => {
    const wrapper = await mountView(['asset:write'], 'VIEWER', { publicPool: true })
    await wrapper.find('.asset-project__copy-button').trigger('click')
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      showCopy: boolean
      copyAsset: AssetVO
      writableTargets: AssetProjectVO[]
    }
    expect(vm.showCopy).toBe(true)
    expect(vm.copyAsset.id).toBe(1)
    expect(projectApi.list).toHaveBeenCalledOnce()
    expect(vm.writableTargets.map((p) => [p.id, p.role])).toEqual([[8, 'OWNER'], [9, 'EDITOR']])
  })

  it('复制 API 参数正确；成功关闭并清状态，不刷新源资产', async () => {
    const wrapper = await mountView(['asset:write'], 'VIEWER', { publicPool: true })
    await wrapper.find('.asset-project__copy-button').trigger('click')
    await flushPromises()
    vi.mocked(assetApi.list).mockClear()
    const vm = wrapper.vm as unknown as {
      selectedTargetProjectId: number | null
      submitCopy: () => Promise<void>
      showCopy: boolean
      copyAsset: AssetVO | null
      copyError: string
    }
    vm.selectedTargetProjectId = 8
    await vm.submitCopy()

    expect(assetApi.copy).toHaveBeenCalledWith(1, { targetProjectId: 8 })
    expect(messageMock.success).toHaveBeenCalledWith(expect.stringContaining('我的项目'))
    expect(vm.showCopy).toBe(false)
    expect(vm.copyAsset).toBeNull()
    expect(vm.selectedTargetProjectId).toBeNull()
    expect(vm.copyError).toBe('')
    expect(assetApi.list).not.toHaveBeenCalled()
  })

  it('复制失败保持弹窗与选择并显示错误', async () => {
    vi.mocked(assetApi.copy).mockRejectedValueOnce(new Error('copy failed'))
    const wrapper = await mountView(['asset:write'], 'VIEWER', { publicPool: true })
    await wrapper.find('.asset-project__copy-button').trigger('click')
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      selectedTargetProjectId: number | null
      submitCopy: () => Promise<void>
      showCopy: boolean
      copyAsset: AssetVO | null
      copyError: string
    }
    vm.selectedTargetProjectId = 9
    await vm.submitCopy()

    expect(vm.showCopy).toBe(true)
    expect(vm.copyAsset?.id).toBe(1)
    expect(vm.selectedTargetProjectId).toBe(9)
    expect(vm.copyError).toContain('复制失败')
    expect(messageMock.error).toHaveBeenCalled()
  })

  it('无可写目标显示清晰空态', async () => {
    vi.mocked(projectApi.list).mockResolvedValueOnce(response({ code: 200, message: 'ok', data: [
      mkProject('VIEWER', { id: 10, name: '只读项目' }),
      mkProject('OWNER', { id: 7, name: '源项目' })
    ] }))
    const wrapper = await mountView(['asset:write'], 'VIEWER', { publicPool: true })
    await wrapper.find('.asset-project__copy-button').trigger('click')
    await flushPromises()
    const vm = wrapper.vm as unknown as { writableTargets: AssetProjectVO[]; copyTargetsLoading: boolean }
    expect(vm.writableTargets).toEqual([])
    expect(vm.copyTargetsLoading).toBe(false)
    expect(document.body.textContent).toContain('暂无可写的目标项目')
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
      form: { mediaType: '提示词' | '剧本'; name: string; description: string; content: string; roleKeys: string[] }
      submitCreate: () => Promise<void>
    }
    vm.openCreate()
    vm.form.mediaType = '提示词'
    vm.form.name = '人物提示词'
    vm.form.content = '一位老板娘'
    vm.form.roleKeys = ['人物']
    await vm.submitCreate()
    // 正文按类型包规范 JSON（提示词→{body}，剧本→{synopsis}），后端 content 为 JSONB
    expect(assetApi.create).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ mediaType: '提示词', name: '人物提示词', content: '{"body":"一位老板娘"}', roleKeys: ['人物'] })
    )
  })

  it('新建剧本资产 content 包成 {synopsis}', async () => {
    const wrapper = await mountView(['asset:write'])
    const vm = wrapper.vm as unknown as {
      openCreate: () => void
      form: { mediaType: '提示词' | '剧本'; name: string; description: string; content: string; roleKeys: string[] }
      submitCreate: () => Promise<void>
    }
    vm.openCreate()
    vm.form.mediaType = '剧本'
    vm.form.name = '分场剧本'
    vm.form.content = '第一场：庭院'
    await vm.submitCreate()
    expect(assetApi.create).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ mediaType: '剧本', name: '分场剧本', content: '{"synopsis":"第一场：庭院"}' })
    )
  })

  it('inferMediaType 按 mime 映射 图片/视频/音频/不支持=null', async () => {
    const wrapper = await mountView(['asset:write'])
    const vm = wrapper.vm as unknown as { inferMediaType: (m: string) => string | null }
    expect(vm.inferMediaType('image/png')).toBe('图片')
    expect(vm.inferMediaType('video/mp4')).toBe('视频')
    expect(vm.inferMediaType('audio/mpeg')).toBe('音频')
    expect(vm.inferMediaType('application/pdf')).toBeNull()
  })

  it('上传文件调 assetApi.upload（按 mime 推断 图片）', async () => {
    const wrapper = await mountView(['asset:write'])
    const vm = wrapper.vm as unknown as { onFileChange: (e: Event) => Promise<void> }
    const file = new File(['x'], 'a.png', { type: 'image/png' })
    const input = document.createElement('input')
    Object.defineProperty(input, 'files', { value: [file], configurable: true })
    await vm.onFileChange({ target: input } as unknown as Event)
    expect(assetApi.upload).toHaveBeenCalledWith(7, file, '图片', expect.objectContaining({ name: 'a.png' }))
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
