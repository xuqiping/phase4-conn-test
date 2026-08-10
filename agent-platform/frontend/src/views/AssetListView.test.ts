import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AssetListView from './AssetListView.vue'
import PublicPublishDialog from '@/components/asset/PublicPublishDialog.vue'
import PublicAccessDialog from '@/components/asset/PublicAccessDialog.vue'
import { projectApi, publicPoolApi } from '@/api/assets'
import { useAuthStore } from '@/stores/auth'
import type { AxiosResponse } from 'axios'
import type { AssetProjectVO, PublicProjectSummaryVO } from '@/types/asset'

const dialogMock = { warning: vi.fn() }
const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const routerPushMock = vi.fn()
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return {
    ...actual,
    useMessage: () => messageMock,
    useDialog: () => dialogMock
  }
})

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPushMock })
}))

vi.mock('@/api/assets', () => ({
  projectApi: {
    list: vi.fn(),
    create: vi.fn(),
    remove: vi.fn()
  },
  publicPoolApi: {
    list: vi.fn(),
    requestAccess: vi.fn(),
    unpublish: vi.fn()
  }
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

function mkProject(id: number, role: 'OWNER' | 'EDITOR' | 'VIEWER', over: Partial<AssetProjectVO> = {}): AssetProjectVO {
  return {
    id,
    name: `项目${id}`,
    description: 'desc',
    ownerId: 1,
    narrativeRoles: ['人物', '道具'],
    mediaTypes: [{ key: '提示词', category: 'TEXT' }],
    role,
    createdAt: '2026-08-05',
    ...over
  }
}

function mkPublic(id: number, over: Partial<PublicProjectSummaryVO> = {}): PublicProjectSummaryVO {
  return {
    id,
    name: `公共项目${id}`,
    description: `摘要${id}`,
    publicAccessMode: 'OPEN',
    publishedBy: 8,
    publisherUsername: 'publisher',
    publishedAt: '2026-08-06',
    publishedByAdmin: false,
    assetCount: 12,
    myRequestStatus: null,
    usable: true,
    ...over
  }
}

function mountView(permissions: string[], roles: string[] = ['tester']) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const authStore = useAuthStore()
  authStore.userInfo = {
    id: 1,
    username: 'tester',
    email: null,
    avatar: null,
    roles,
    permissions
  }
  return mount(AssetListView, { global: { plugins: [pinia] } })
}

describe('AssetListView (S9 项目列表页 + 公共池)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(projectApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [mkProject(1, 'OWNER'), mkProject(2, 'EDITOR'), mkProject(3, 'VIEWER')] })
    )
    vi.mocked(publicPoolApi.list).mockResolvedValue(
      response({ code: 200, message: 'ok', data: [mkPublic(11), mkPublic(12, { publicAccessMode: 'APPROVAL_REQUIRED', publishedByAdmin: true })] })
    )
    vi.mocked(projectApi.create).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkProject(9, 'OWNER') })
    )
    vi.mocked(projectApi.remove).mockResolvedValue(response({ code: 200, message: 'ok', data: undefined as never }))
    vi.mocked(publicPoolApi.requestAccess).mockResolvedValue(response({ code: 200, message: 'ok', data: {} as never }))
    vi.mocked(publicPoolApi.unpublish).mockResolvedValue(response({ code: 200, message: 'ok', data: undefined as never }))
  })

  it('三页签按本地角色与公共摘要分流，并显示官方/访问模式文字徽章', async () => {
    const summary = mkPublic(13, { publishedByAdmin: true, publicAccessMode: 'APPROVAL_REQUIRED' })
    Object.defineProperty(summary, 'narrativeRoles', { get: () => { throw new Error('公共卡不得读取 narrativeRoles') } })
    Object.defineProperty(summary, 'mediaTypes', { get: () => { throw new Error('公共卡不得读取 mediaTypes') } })
    vi.mocked(publicPoolApi.list).mockResolvedValueOnce(response({ code: 200, message: 'ok', data: [summary] }))

    const wrapper = mountView(['asset:write'])
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      mineProjects: AssetProjectVO[]
      sharedProjects: AssetProjectVO[]
      publicProjects: PublicProjectSummaryVO[]
      activeTab: string
    }
    expect(vm.mineProjects.map((p) => p.id)).toEqual([1])
    expect(vm.sharedProjects.map((p) => p.id)).toEqual([2, 3])
    expect(vm.publicProjects.map((p) => p.id)).toEqual([13])
    expect(wrapper.text()).toContain('公共池（1）')
    vm.activeTab = 'public'
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('官方发布')
    expect(wrapper.text()).toContain('需审批')
    expect(wrapper.text()).toContain('12 个资产')
    expect(wrapper.text()).not.toContain('2 个叙事角色')
  })

  it('公共池加载失败不破坏我的/共享数据，并显示独立错误', async () => {
    vi.mocked(publicPoolApi.list).mockRejectedValueOnce(new Error('offline'))
    const wrapper = mountView(['asset:write'])
    await flushPromises()

    const vm = wrapper.vm as unknown as { mineProjects: AssetProjectVO[]; sharedProjects: AssetProjectVO[]; publicError: string }
    expect(vm.mineProjects.map((p) => p.id)).toEqual([1])
    expect(vm.sharedProjects.map((p) => p.id)).toEqual([2, 3])
    expect(vm.publicError).toContain('公共池')
    ;(wrapper.vm as unknown as { activeTab: string }).activeTab = 'public'
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('公共池加载失败')
  })

  it('本地列表失败显示持久重试错误且不误报空态，公共池仍可用', async () => {
    vi.mocked(projectApi.list).mockRejectedValueOnce(new Error('local offline'))
    const wrapper = mountView(['asset:write'])
    await flushPromises()

    const vm = wrapper.vm as unknown as { localError: string; publicProjects: PublicProjectSummaryVO[]; activeTab: string }
    expect(vm.localError).toContain('项目列表加载失败')
    expect(vm.publicProjects).toHaveLength(2)
    expect(wrapper.text()).toContain('项目列表加载失败')
    expect(wrapper.text()).not.toContain('暂无项目')
    vm.activeTab = 'public'
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('公共池（2）')
  })

  it('本地列表旧成功/旧失败均不覆盖新会话且不产生旧错误 toast', async () => {
    const oldSuccess = deferred<AxiosResponse<{ code: number; message: string; data: AssetProjectVO[] }>>()
    vi.mocked(projectApi.list)
      .mockReturnValueOnce(oldSuccess.promise)
      .mockResolvedValueOnce(response({ code: 200, message: 'ok', data: [mkProject(9, 'OWNER')] }))
    const wrapper = mountView(['asset:write'])
    await Promise.resolve()
    const vm = wrapper.vm as unknown as { projects: AssetProjectVO[]; localError: string; loadLocalData: () => Promise<void> }
    await vm.loadLocalData()
    oldSuccess.resolve(response({ code: 200, message: 'ok', data: [mkProject(1, 'OWNER')] }))
    await flushPromises()
    expect(vm.projects.map((p) => p.id)).toEqual([9])
    expect(vm.localError).toBe('')

    const oldFailure = deferred<AxiosResponse<{ code: number; message: string; data: AssetProjectVO[] }>>()
    vi.mocked(projectApi.list)
      .mockReturnValueOnce(oldFailure.promise)
      .mockResolvedValueOnce(response({ code: 200, message: 'ok', data: [mkProject(10, 'OWNER')] }))
    const staleLoad = vm.loadLocalData()
    await Promise.resolve()
    await vm.loadLocalData()
    oldFailure.reject(new Error('stale offline'))
    await staleLoad
    expect(vm.projects.map((p) => p.id)).toEqual([10])
    expect(vm.localError).toBe('')
    expect(messageMock.error).not.toHaveBeenCalledWith('加载项目列表失败')
  })

  it('OPEN/usable 公共卡可进入，未获批卡点击不路由', async () => {
    vi.mocked(publicPoolApi.list).mockResolvedValueOnce(response({ code: 200, message: 'ok', data: [
      mkPublic(11),
      mkPublic(12, { publicAccessMode: 'APPROVAL_REQUIRED', usable: false })
    ] }))
    const wrapper = mountView(['asset:write'])
    await flushPromises()
    const vm = wrapper.vm as unknown as { activeTab: string }
    vm.activeTab = 'public'
    await wrapper.vm.$nextTick()

    const cards = wrapper.findAll('.public-project-card')
    await cards[0].trigger('click')
    await cards[1].trigger('click')
    expect(routerPushMock).toHaveBeenCalledOnce()
    expect(routerPushMock).toHaveBeenCalledWith('/assets/11')
  })

  it('申请/等待/重新申请动作矩阵，真实按钮点击申请后刷新公共列表', async () => {
    vi.mocked(publicPoolApi.list).mockResolvedValue(response({ code: 200, message: 'ok', data: [
      mkPublic(21, { publicAccessMode: 'APPROVAL_REQUIRED', usable: false }),
      mkPublic(22, { publicAccessMode: 'APPROVAL_REQUIRED', usable: false, myRequestStatus: 'PENDING' }),
      mkPublic(23, { publicAccessMode: 'APPROVAL_REQUIRED', usable: false, myRequestStatus: 'REJECTED' }),
      mkPublic(24, { publicAccessMode: 'APPROVAL_REQUIRED', usable: false, myRequestStatus: 'REVOKED' })
    ] }))
    const wrapper = mountView(['asset:write'])
    await flushPromises()
    ;(wrapper.vm as unknown as { activeTab: string }).activeTab = 'public'
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('申请使用')
    expect(wrapper.text()).toContain('等待审批')
    expect(wrapper.text().match(/重新申请/g)).toHaveLength(2)
    const waitButton = wrapper.findAll('button').find((b) => b.text().includes('等待审批'))
    expect(waitButton?.attributes('disabled')).toBeDefined()
    expect(waitButton?.attributes('title')).toContain('审批')

    const requestButton = wrapper.findAll('button').find((b) => b.text().includes('申请使用'))
    expect(requestButton).toBeDefined()
    await requestButton!.trigger('click')
    await flushPromises()
    expect(publicPoolApi.requestAccess).toHaveBeenCalledWith(21)
    expect(publicPoolApi.list).toHaveBeenCalledTimes(2)
    expect(routerPushMock).not.toHaveBeenCalled()
  })

  it('申请按钮同步防双击且 stopPropagation 不打开公共卡', async () => {
    const slowRequest = deferred<AxiosResponse<never>>()
    vi.mocked(publicPoolApi.list).mockResolvedValue(response({ code: 200, message: 'ok', data: [
      mkPublic(31, { publicAccessMode: 'APPROVAL_REQUIRED', usable: false })
    ] }))
    vi.mocked(publicPoolApi.requestAccess).mockReturnValueOnce(slowRequest.promise)
    const wrapper = mountView(['asset:write'])
    await flushPromises()
    ;(wrapper.vm as unknown as { activeTab: string }).activeTab = 'public'
    await wrapper.vm.$nextTick()
    const button = wrapper.findAll('button').find((b) => b.text().includes('申请使用'))!
    await button.trigger('click')
    await button.trigger('click')
    expect(publicPoolApi.requestAccess).toHaveBeenCalledOnce()
    expect(routerPushMock).not.toHaveBeenCalled()
    slowRequest.resolve(response({ code: 200, message: 'ok', data: undefined as never }) as never)
    await flushPromises()
  })

  it('OWNER 显示发布/移出/审批入口，弹窗事件与移出确认刷新两份列表', async () => {
    vi.mocked(projectApi.list).mockResolvedValue(response({ code: 200, message: 'ok', data: [
      mkProject(1, 'OWNER'),
      mkProject(4, 'OWNER', { publicPool: true, publicAccessMode: 'APPROVAL_REQUIRED' })
    ] }))
    const wrapper = mountView(['asset:write'], ['admin'])
    await flushPromises()

    expect(wrapper.text()).toContain('发布到公共池')
    expect(wrapper.text()).toContain('移出公共池')
    expect(wrapper.text()).toContain('审批管理')

    const publishButton = wrapper.findAll('button').find((b) => b.text().includes('发布到公共池'))
    await publishButton!.trigger('click')
    expect(routerPushMock).not.toHaveBeenCalled()
    const publishDialog = wrapper.findComponent(PublicPublishDialog)
    expect(publishDialog.props('show')).toBe(true)
    expect((publishDialog.props('project') as AssetProjectVO | null)?.id).toBe(1)
    expect(publishDialog.props('isAdmin')).toBe(true)
    await publishDialog.vm.$emit('published')
    await flushPromises()
    expect(projectApi.list).toHaveBeenCalledTimes(2)
    expect(publicPoolApi.list).toHaveBeenCalledTimes(2)

    const accessButton = wrapper.findAll('button').find((b) => b.text().includes('审批管理'))
    await accessButton!.trigger('click')
    const accessDialog = wrapper.findComponent(PublicAccessDialog)
    expect(accessDialog.props('show')).toBe(true)
    expect(accessDialog.props('projectId')).toBe(4)
    await accessDialog.vm.$emit('changed')
    await flushPromises()
    expect(publicPoolApi.list).toHaveBeenCalledTimes(3)

    const unpublishButton = wrapper.findAll('button').find((b) => b.text().includes('移出公共池'))
    await unpublishButton!.trigger('click')
    expect(dialogMock.warning).toHaveBeenCalled()
    const lastWarningCall = dialogMock.warning.mock.calls[dialogMock.warning.mock.calls.length - 1]
    const opts = lastWarningCall[0] as { onPositiveClick: () => Promise<void> }
    await opts.onPositiveClick()
    expect(publicPoolApi.unpublish).toHaveBeenCalledWith(4)
    expect(projectApi.list).toHaveBeenCalledTimes(3)
    expect(publicPoolApi.list).toHaveBeenCalledTimes(4)
  })

  it('移出确认回调重复执行只调用一次 API 且按钮不路由', async () => {
    const slowUnpublish = deferred<AxiosResponse<never>>()
    vi.mocked(projectApi.list).mockResolvedValue(response({ code: 200, message: 'ok', data: [
      mkProject(4, 'OWNER', { publicPool: true, publicAccessMode: 'OPEN' })
    ] }))
    vi.mocked(publicPoolApi.unpublish).mockReturnValueOnce(slowUnpublish.promise)
    const wrapper = mountView(['asset:write'])
    await flushPromises()
    const button = wrapper.findAll('button').find((b) => b.text().includes('移出公共池'))!
    await button.trigger('click')
    expect(routerPushMock).not.toHaveBeenCalled()
    const call = dialogMock.warning.mock.calls[0][0] as { onPositiveClick: () => Promise<void> }
    const first = call.onPositiveClick()
    const second = call.onPositiveClick()
    expect(publicPoolApi.unpublish).toHaveBeenCalledOnce()
    slowUnpublish.resolve(response({ code: 200, message: 'ok', data: undefined as never }) as never)
    await Promise.all([first, second])
  })

  it('无 asset:write 渲染 403 兜底', async () => {
    const wrapper = mountView([])
    await flushPromises()
    expect((wrapper.vm as unknown as { canEdit: boolean }).canEdit).toBe(false)
    expect(wrapper.text()).toContain('无 asset:write 权限')
    expect(projectApi.list).not.toHaveBeenCalled()
    expect(publicPoolApi.list).not.toHaveBeenCalled()
  })

  it('新建项目调 projectApi.create（trim + 空 desc → undefined）', async () => {
    const wrapper = mountView(['asset:write'])
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      openCreate: () => void
      form: { name: string; description: string }
      submitCreate: () => Promise<void>
    }
    vm.openCreate()
    vm.form.name = '  我的短剧  '
    vm.form.description = '   '
    await vm.submitCreate()

    expect(projectApi.create).toHaveBeenCalledWith({ name: '我的短剧', description: undefined })
  })

  it('删除项目二次确认 onPositiveClick 调 projectApi.remove', async () => {
    const wrapper = mountView(['asset:write'])
    await flushPromises()
    const vm = wrapper.vm as unknown as { confirmDelete: (p: AssetProjectVO) => void }
    vm.confirmDelete(mkProject(1, 'OWNER'))

    expect(dialogMock.warning).toHaveBeenCalled()
    const opts = dialogMock.warning.mock.calls[0][0] as { onPositiveClick: () => Promise<void> }
    await opts.onPositiveClick()
    expect(projectApi.remove).toHaveBeenCalledWith(1)
  })
})
