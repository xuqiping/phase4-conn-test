import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { NButton, NSelect } from 'naive-ui'
import AssetProjectView from './AssetProjectView.vue'
import { projectApi, assetApi } from '@/api/assets'
import { useAuthStore } from '@/stores/auth'
import type { AxiosResponse } from 'axios'
import type { AssetProjectVO, AssetVO } from '@/types/asset'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const routeHolder = vi.hoisted(() => ({ route: null as null | { params: { id: string } } }))
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  routeHolder.route = reactive({ params: { id: '7' } })
  return {
    useRoute: () => routeHolder.route,
    useRouter: () => ({ push: vi.fn() })
  }
})

vi.mock('@/api/assets', () => ({
  projectApi: { get: vi.fn(), list: vi.fn(), update: vi.fn(), updateSettings: vi.fn() },
  assetApi: { list: vi.fn(), countMatrix: vi.fn(), create: vi.fn(), upload: vi.fn(), copy: vi.fn() },
  memberApi: { searchCandidates: vi.fn() },
  scoreApi: { mine: vi.fn(), submit: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
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
  projectOver: Partial<AssetProjectVO> = {},
  configureMocks = true
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
  if (configureMocks) {
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
  }
  const wrapper = mount(AssetProjectView, { global: { plugins: [pinia] } })
  await settle()
  return wrapper
}

describe('AssetProjectView (S11 项目详情页)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeHolder.route!.params.id = '7'
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

  it('真实 NSelect + 复制按钮提交参数正确；成功关闭并清状态，不刷新源资产', async () => {
    const wrapper = await mountView(['asset:write'], 'VIEWER', { publicPool: true })
    await wrapper.find('.asset-project__copy-button').trigger('click')
    await flushPromises()
    vi.mocked(assetApi.list).mockClear()
    const vm = wrapper.vm as unknown as {
      selectedTargetProjectId: number | null
      showCopy: boolean
      copyAsset: AssetVO | null
      copyError: string
    }
    const selects = wrapper.findAllComponents(NSelect)
    await selects[selects.length - 1].vm.$emit('update:value', 8)
    const copyButton = wrapper.findAllComponents(NButton).find((b) => b.text().trim() === '复制')
    expect(copyButton).toBeDefined()
    await copyButton!.trigger('click')
    await flushPromises()

    expect(assetApi.copy).toHaveBeenCalledWith(1, { targetProjectId: 8 })
    expect(messageMock.success).toHaveBeenCalledWith(expect.stringContaining('我的项目'))
    expect(vm.showCopy).toBe(false)
    expect(vm.copyAsset).toBeNull()
    expect(vm.selectedTargetProjectId).toBeNull()
    expect(vm.copyError).toBe('')
    expect(assetApi.list).not.toHaveBeenCalled()
  })

  it('复制提交中正常关闭入口均被拦截并保留上下文', async () => {
    const slowCopy = deferred<AxiosResponse<never>>()
    vi.mocked(assetApi.copy).mockReturnValueOnce(slowCopy.promise)
    const wrapper = await mountView(['asset:write'], 'VIEWER', { publicPool: true })
    await wrapper.find('.asset-project__copy-button').trigger('click')
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      selectedTargetProjectId: number | null
      submitCopy: () => Promise<void>
      onCopyVisibilityChange: (value: boolean) => void
      closeCopy: () => void
      showCopy: boolean
      copyAsset: AssetVO | null
    }
    vm.selectedTargetProjectId = 8
    const pending = vm.submitCopy()
    vm.onCopyVisibilityChange(false)
    vm.closeCopy()
    expect(vm.showCopy).toBe(true)
    expect(vm.copyAsset?.id).toBe(1)
    expect(vm.selectedTargetProjectId).toBe(8)
    slowCopy.resolve(response({ code: 200, message: 'ok', data: undefined as never }) as never)
    await pending
  })

  it('强制关闭重开同资产同目标仍按 mutation key 防重，旧完成不污染新弹窗', async () => {
    const slowCopy = deferred<AxiosResponse<never>>()
    vi.mocked(assetApi.copy).mockReturnValueOnce(slowCopy.promise)
    const wrapper = await mountView(['asset:write'], 'VIEWER', { publicPool: true })
    const vm = wrapper.vm as unknown as {
      assets: AssetVO[]
      showCopy: boolean
      selectedTargetProjectId: number | null
      openCopy: (asset: AssetVO) => Promise<void>
      submitCopy: () => Promise<void>
      copyAsset: AssetVO | null
    }
    await vm.openCopy(vm.assets[0])
    vm.selectedTargetProjectId = 8
    const oldSubmit = vm.submitCopy()
    vm.showCopy = false
    await vm.openCopy(vm.assets[0])
    vm.selectedTargetProjectId = 8
    await vm.submitCopy()
    expect(assetApi.copy).toHaveBeenCalledOnce()
    slowCopy.resolve(response({ code: 200, message: 'ok', data: undefined as never }) as never)
    await oldSubmit
    expect(vm.showCopy).toBe(true)
    expect(vm.copyAsset?.id).toBe(1)
    expect(messageMock.success).not.toHaveBeenCalled()
  })

  it('route 切换立即清空旧 project/assets/matrix/total 并进入新 loading', async () => {
    const projectB = deferred<AxiosResponse<unknown>>()
    const assetsB = deferred<AxiosResponse<unknown>>()
    const matrixB = deferred<AxiosResponse<unknown>>()
    vi.mocked(projectApi.get).mockImplementation((id) => id === 7
      ? Promise.resolve(response({ code: 200, message: 'ok', data: mkProject('OWNER') }))
      : projectB.promise as never)
    vi.mocked(assetApi.list).mockImplementation((id) => id === 7
      ? Promise.resolve(pageResp([mkAsset(1)]))
      : assetsB.promise as never)
    vi.mocked(assetApi.countMatrix).mockImplementation((id) => id === 7
      ? Promise.resolve(response({ code: 200, message: 'ok', data: { cells: [{ mediaType: '图片', roleKey: null, count: 1 }], typeTotals: [] } }))
      : matrixB.promise as never)
    const wrapper = await mountView(['asset:write'], 'OWNER', {}, false)
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      project: AssetProjectVO | null
      assets: AssetVO[]
      matrix: { cells: unknown[] }
      total: number
      loading: boolean
    }
    expect(vm.project?.id).toBe(7)
    routeHolder.route!.params.id = '8'
    await wrapper.vm.$nextTick()
    expect(vm.project).toBeNull()
    expect(vm.assets).toEqual([])
    expect(vm.matrix.cells).toEqual([])
    expect(vm.total).toBe(0)
    expect(vm.loading).toBe(true)

    projectB.resolve(response({ code: 200, message: 'ok', data: mkProject('VIEWER', { id: 8 }) }) as never)
    assetsB.resolve(pageResp([]) as never)
    matrixB.resolve(response({ code: 200, message: 'ok', data: { cells: [], typeTotals: [] } }) as never)
    await flushPromises()
  })

  it('A 未完成时切 B，A 晚成功/失败/finally 不覆盖 B 且不 toast', async () => {
    const projectA = deferred<AxiosResponse<unknown>>()
    const assetsA = deferred<AxiosResponse<unknown>>()
    const matrixA = deferred<AxiosResponse<unknown>>()
    vi.mocked(projectApi.get).mockImplementation((id) => id === 7
      ? projectA.promise as never
      : Promise.resolve(response({ code: 200, message: 'ok', data: mkProject('VIEWER', { id: 8, name: 'B' }) })))
    vi.mocked(assetApi.list).mockImplementation((id) => id === 7
      ? assetsA.promise as never
      : Promise.resolve(pageResp([mkAsset(8, { projectId: 8 })])))
    vi.mocked(assetApi.countMatrix).mockImplementation((id) => id === 7
      ? matrixA.promise as never
      : Promise.resolve(response({ code: 200, message: 'ok', data: { cells: [], typeTotals: [] } })))
    const wrapper = await mountView(['asset:write'], 'OWNER', {}, false)
    routeHolder.route!.params.id = '8'
    await wrapper.vm.$nextTick()
    await flushPromises()
    const vm = wrapper.vm as unknown as { project: AssetProjectVO | null; assets: AssetVO[]; total: number; loading: boolean }
    expect(vm.project?.id).toBe(8)
    expect(vm.assets.map((a) => a.id)).toEqual([8])
    expect(vm.loading).toBe(false)

    projectA.resolve(response({ code: 200, message: 'ok', data: mkProject('OWNER', { id: 7, name: 'A' }) }) as never)
    assetsA.reject(new Error('stale assets failed'))
    matrixA.resolve(response({ code: 200, message: 'ok', data: { cells: [{ mediaType: '图片', roleKey: null, count: 99 }], typeTotals: [] } }) as never)
    await flushPromises()
    expect(vm.project?.id).toBe(8)
    expect(vm.assets.map((a) => a.id)).toEqual([8])
    expect(vm.total).toBe(1)
    expect(vm.loading).toBe(false)
    expect(messageMock.error).not.toHaveBeenCalled()
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

  // ---------- C7 项目设置 + 评分/PERSONAL 门控（2x第三轮） ----------

  it('C7-1 OWNER 显「项目设置」按钮；EDITOR 不显', async () => {
    const ownerWrapper = await mountView(['asset:write'], 'OWNER')
    expect(ownerWrapper.text()).toContain('项目设置')
    const editorWrapper = await mountView(['asset:write'], 'EDITOR')
    expect(editorWrapper.text()).not.toContain('项目设置')
  })

  it('C7-2 canScore 矩阵：OWNER 恒 true；EDITOR 随开关；VIEWER false', async () => {
    const ownerOff = await mountView(['asset:write'], 'OWNER', { memberScoringEnabled: false })
    expect((ownerWrapperVm(ownerOff)).canScore).toBe(true)

    const editorOff = await mountView(['asset:write'], 'EDITOR', { memberScoringEnabled: false })
    expect((ownerWrapperVm(editorOff)).canScore).toBe(false)

    const editorOn = await mountView(['asset:write'], 'EDITOR', { memberScoringEnabled: true })
    expect((ownerWrapperVm(editorOn)).canScore).toBe(true)

    const viewer = await mountView(['asset:write'], 'VIEWER', { memberScoringEnabled: true })
    expect((ownerWrapperVm(viewer)).canScore).toBe(false)
  })

  it('C7-3 personalMode 矩阵：EDITOR+PERSONAL=true；OWNER+PERSONAL=false；SHARED 恒 false', async () => {
    const editorPersonal = await mountView(['asset:write'], 'EDITOR', { contentMode: 'PERSONAL' })
    expect((ownerWrapperVm(editorPersonal)).personalMode).toBe(true)

    const ownerPersonal = await mountView(['asset:write'], 'OWNER', { contentMode: 'PERSONAL' })
    expect((ownerWrapperVm(ownerPersonal)).personalMode).toBe(false)

    const editorShared = await mountView(['asset:write'], 'EDITOR', { contentMode: 'SHARED' })
    expect((ownerWrapperVm(editorShared)).personalMode).toBe(false)
  })

  it('C7-4 设置保存 → 重拉 project + 列表（L6 即时生效）', async () => {
    const wrapper = await mountView(['asset:write'], 'OWNER')
    vi.clearAllMocks()
    vi.mocked(projectApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkProject('OWNER', { contentMode: 'PERSONAL', memberScoringEnabled: true }) })
    )
    vi.mocked(assetApi.list).mockResolvedValue(pageResp([mkAsset(1)]))
    vi.mocked(assetApi.countMatrix).mockResolvedValue(
      response({ code: 200, message: 'ok', data: { cells: [], typeTotals: [] } })
    )
    const vm = wrapper.vm as unknown as { onSettingsSaved: () => Promise<void>; personalMode: boolean }
    await vm.onSettingsSaved()
    expect(projectApi.get).toHaveBeenCalledWith(7)
    expect(assetApi.list).toHaveBeenCalled()
    // 重拉后 PERSONAL 门控即时重算（L6）
    expect(vm.personalMode).toBe(false) // OWNER 旁路
  })

  it('C7-5 筛选含上传者/分数 → assetApi.list 透传全部新参数', async () => {
    const wrapper = await mountView(['asset:write'])
    vi.clearAllMocks()
    vi.mocked(assetApi.list).mockResolvedValue(pageResp([]))
    const vm = wrapper.vm as unknown as { filter: Record<string, unknown> }
    vm.filter = { creatorUsername: 'zhang3', scoreMin: 60, scoreMax: 90, scoreSource: 'member' }
    await settle()
    expect(assetApi.list).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ creatorUsername: 'zhang3', scoreMin: 60, scoreMax: 90, scoreSource: 'member' })
    )
  })
})

/** 取视图暴露的门控 computed。 */
function ownerWrapperVm(wrapper: { vm: unknown }) {
  return wrapper.vm as unknown as { canScore: boolean; personalMode: boolean }
}
