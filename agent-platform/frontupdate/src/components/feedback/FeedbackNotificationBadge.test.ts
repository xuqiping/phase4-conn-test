import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import FeedbackNotificationBadge from './FeedbackNotificationBadge.vue'
import { feedbackApi, type FeedbackNotificationVO } from '@/api/feedback'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

const { routerPushMock } = vi.hoisted(() => ({ routerPushMock: vi.fn() }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPushMock })
}))

vi.mock('@/api/feedback', async (importOriginal) => {
  const orig = await importOriginal<typeof import('@/api/feedback')>()
  return {
    ...orig,
    feedbackApi: {
      ...orig.feedbackApi,
      unreadCount: vi.fn(),
      notifications: vi.fn(),
      markNotificationRead: vi.fn(),
      markAllNotificationsRead: vi.fn()
    }
  }
})

function apiOk<T>(data: T) {
  return { data: { code: 200, msg: 'success', data } }
}

const notif = (over: Partial<FeedbackNotificationVO> = {}): FeedbackNotificationVO => ({
  id: 7,
  type: 'QUESTION_ANSWERED',
  refId: 33,
  message: '你的提问「积分怎么算」已被回答',
  readAt: null,
  createdAt: '2026-08-21T10:00:00Z',
  ...over
})

type BadgeVm = {
  count: number
  list: FeedbackNotificationVO[]
  loadList: () => Promise<void>
  open: (n: FeedbackNotificationVO) => Promise<void>
  readAll: () => Promise<void>
}

function mountBadge() {
  const wrapper = mount(FeedbackNotificationBadge)
  return { wrapper, vm: wrapper.vm as unknown as BadgeVm }
}

describe('FeedbackNotificationBadge（19x 反馈铃铛）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    vi.mocked(feedbackApi.unreadCount).mockResolvedValue(apiOk({ count: 1 }) as never)
    vi.mocked(feedbackApi.notifications).mockResolvedValue(apiOk({
      records: [notif()], total: 1, pageNum: 1, pageSize: 20
    }) as never)
    vi.mocked(feedbackApi.markNotificationRead).mockResolvedValue(apiOk(null) as never)
    vi.mocked(feedbackApi.markAllNotificationsRead).mockResolvedValue(apiOk({ count: 1 }) as never)
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('挂载即查未读数，且每 3s 轮询一次', async () => {
    mountBadge()
    await flushPromises()
    expect(feedbackApi.unreadCount).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(3000)
    expect(feedbackApi.unreadCount).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(6000)
    expect(feedbackApi.unreadCount).toHaveBeenCalledTimes(4)
  })

  it('点击通知：标记已读 + 未读数减一 + 跳提问台（QUESTION_ANSWERED→questions）', async () => {
    const { vm } = mountBadge()
    await flushPromises()
    await vm.loadList()
    expect(vm.list).toHaveLength(1)

    await vm.open(vm.list[0])

    expect(feedbackApi.markNotificationRead).toHaveBeenCalledWith(7)
    expect(vm.count).toBe(0)
    expect(routerPushMock).toHaveBeenCalledWith({ path: '/feedback', query: { tab: 'questions' } })
  })

  it('建议审核通知跳建议台（SUGGESTION_REVIEWED→suggestions）', async () => {
    vi.mocked(feedbackApi.notifications).mockResolvedValue(apiOk({
      records: [notif({ id: 8, type: 'SUGGESTION_REVIEWED', message: '你的建议已被采纳' })],
      total: 1, pageNum: 1, pageSize: 20
    }) as never)

    const { vm } = mountBadge()
    await flushPromises()
    await vm.loadList()
    await vm.open(vm.list[0])

    expect(feedbackApi.markNotificationRead).toHaveBeenCalledWith(8)
    expect(routerPushMock).toHaveBeenCalledWith({ path: '/feedback', query: { tab: 'suggestions' } })
  })

  it('已读通知再点：不重复调 read，仅跳转', async () => {
    vi.mocked(feedbackApi.notifications).mockResolvedValue(apiOk({
      records: [notif({ readAt: '2026-08-21T11:00:00Z' })],
      total: 1, pageNum: 1, pageSize: 20
    }) as never)

    const { vm } = mountBadge()
    await flushPromises()
    await vm.loadList()
    await vm.open(vm.list[0])

    expect(feedbackApi.markNotificationRead).not.toHaveBeenCalled()
    expect(routerPushMock).toHaveBeenCalled()
  })

  it('全部已读：调 read-all + 未读数清零', async () => {
    const { vm } = mountBadge()
    await flushPromises()
    await vm.loadList()

    await vm.readAll()

    expect(feedbackApi.markAllNotificationsRead).toHaveBeenCalled()
    expect(messageMock.success).toHaveBeenCalledWith('已全部标记已读')
    expect(vm.count).toBe(0)
  })
})
