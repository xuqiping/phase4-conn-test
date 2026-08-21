import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AdminFeedbackView from './AdminFeedbackView.vue'
import { feedbackApi } from '@/api/feedback'
import type { AdminQuestionVO, AdminSuggestionVO } from '@/api/feedback'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const dialogMock = { warning: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock, useDialog: () => dialogMock }
})

vi.mock('@/api/feedback', async (importOriginal) => {
  const orig = await importOriginal<typeof import('@/api/feedback')>()
  return {
    ...orig,
    feedbackApi: {
      ...orig.feedbackApi,
      adminSuggestions: vi.fn(),
      reviewSuggestion: vi.fn(),
      adminQuestions: vi.fn(),
      answerQuestion: vi.fn(),
      closeQuestion: vi.fn()
    }
  }
})

// 附件缩略图走 blob，测试不触网
vi.mock('@/api/file', () => ({
  fetchFilePreview: vi.fn().mockResolvedValue('blob:mock-thumb')
}))

function apiOk<T>(data: T) {
  return { data: { code: 200, msg: 'success', data } }
}
const emptyPage = { records: [], total: 0, pageNum: 1, pageSize: 10 }

const sugRow = (over: Partial<AdminSuggestionVO> = {}): AdminSuggestionVO => ({
  id: 11,
  userId: 1001,
  username: 'alice',
  createdAt: '2026-08-21T10:00:00Z',
  title: '希望支持深色模式',
  content: '正文',
  attachmentFileIds: ['f1'],
  status: 'PENDING',
  reply: null,
  reviewedAt: null,
  ...over
})

const qRow = (over: Partial<AdminQuestionVO> = {}): AdminQuestionVO => ({
  id: 21,
  userId: 1002,
  username: 'bob',
  createdAt: '2026-08-21T10:00:00Z',
  title: '积分怎么算？',
  content: '问题正文',
  status: 'OPEN',
  answer: null,
  answeredAt: null,
  isPublic: false,
  ...over
})

type Vm = {
  loadSuggestions: (p?: number) => Promise<void>
  loadQuestions: (p?: number) => Promise<void>
  openSugDetail: (r: AdminSuggestionVO) => Promise<void>
  confirmReview: (s: 'ADOPTED' | 'REJECTED' | 'CLOSED') => void
  review: (s: 'ADOPTED' | 'REJECTED' | 'CLOSED') => Promise<void>
  openAnswer: (r: AdminQuestionVO) => void
  submitAnswer: () => Promise<void>
  closeQuestion: (r: AdminQuestionVO) => Promise<void>
  sugDetail: AdminSuggestionVO | null
  reviewReply: string
  answerTarget: AdminQuestionVO | null
  answerText: string
  answerPublic: boolean
}

function mountView() {
  const wrapper = mount(AdminFeedbackView)
  return { wrapper, vm: wrapper.vm as unknown as Vm }
}

describe('AdminFeedbackView（19x admin 反馈处理）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(feedbackApi.adminSuggestions).mockResolvedValue(apiOk(emptyPage) as never)
    vi.mocked(feedbackApi.adminQuestions).mockResolvedValue(apiOk(emptyPage) as never)
    vi.mocked(feedbackApi.reviewSuggestion).mockResolvedValue(apiOk(null) as never)
    vi.mocked(feedbackApi.answerQuestion).mockResolvedValue(apiOk(null) as never)
    vi.mocked(feedbackApi.closeQuestion).mockResolvedValue(apiOk(null) as never)
  })

  it('挂载加载建议+提问两列表', async () => {
    mountView()
    await flushPromises()
    expect(feedbackApi.adminSuggestions).toHaveBeenCalledWith({ status: undefined, page: 1, size: 10 })
    expect(feedbackApi.adminQuestions).toHaveBeenCalledWith({ status: undefined, page: 1, size: 10 })
  })

  it('PENDING 建议直接审核：reviewSuggestion 调参正确（toStatus+reply），成功后刷新', async () => {
    const { vm } = mountView()
    await flushPromises()
    await vm.openSugDetail(sugRow())
    vm.reviewReply = '  好建议，排期  '

    vm.confirmReview('ADOPTED') // PENDING 起点不弹确认，直接执行
    await flushPromises()

    expect(dialogMock.warning).not.toHaveBeenCalled()
    expect(feedbackApi.reviewSuggestion).toHaveBeenCalledWith(11, { toStatus: 'ADOPTED', reply: '好建议，排期' })
    expect(messageMock.success).toHaveBeenCalled()
    expect(feedbackApi.adminSuggestions).toHaveBeenCalledTimes(2) // 初次 + 审核后刷新
  })

  it('ADOPTED 改判 REJECTED：先弹二次确认，确认后才调用', async () => {
    const { vm } = mountView()
    await flushPromises()
    await vm.openSugDetail(sugRow({ status: 'ADOPTED', reply: '旧回复' }))

    vm.confirmReview('REJECTED')
    expect(dialogMock.warning).toHaveBeenCalledTimes(1)
    expect(feedbackApi.reviewSuggestion).not.toHaveBeenCalled()

    // 模拟用户点「确认」
    const opts = dialogMock.warning.mock.calls[0][0] as { onPositiveClick: () => void }
    opts.onPositiveClick()
    await flushPromises()
    expect(feedbackApi.reviewSuggestion).toHaveBeenCalledWith(11, { toStatus: 'REJECTED', reply: '旧回复' })
  })

  it('回答提问：answerQuestion 调参（answer+isPublic），首答提示通知提问人', async () => {
    const { vm } = mountView()
    await flushPromises()
    vm.openAnswer(qRow())
    vm.answerText = '按调用量阶梯计费，详见钱包页。'
    vm.answerPublic = true

    await vm.submitAnswer()

    expect(feedbackApi.answerQuestion).toHaveBeenCalledWith(21, {
      answer: '按调用量阶梯计费，详见钱包页。',
      isPublic: true
    })
    expect(messageMock.success).toHaveBeenCalledWith('已回答并通知提问人')
  })

  it('改答案：提示文案为「答案已更新」（不重发通知）', async () => {
    const { vm } = mountView()
    await flushPromises()
    vm.openAnswer(qRow({ status: 'ANSWERED', answer: '旧答案', isPublic: true }))
    // 打开时回填旧答案与公开开关
    expect(vm.answerText).toBe('旧答案')
    expect(vm.answerPublic).toBe(true)

    vm.answerText = '新答案'
    await vm.submitAnswer()

    expect(feedbackApi.answerQuestion).toHaveBeenCalledWith(21, { answer: '新答案', isPublic: true })
    expect(messageMock.success).toHaveBeenCalledWith('答案已更新')
  })

  it('关闭提问：closeQuestion 调用并刷新', async () => {
    const { vm } = mountView()
    await flushPromises()
    await vm.closeQuestion(qRow())
    expect(feedbackApi.closeQuestion).toHaveBeenCalledWith(21)
    expect(feedbackApi.adminQuestions).toHaveBeenCalledTimes(2)
  })

  it('409 抢态：review 失败 → 错误提示 + 刷新列表', async () => {
    vi.mocked(feedbackApi.reviewSuggestion).mockRejectedValue(new Error('该建议已被其他管理员处理') as never)
    const { vm } = mountView()
    await flushPromises()
    await vm.openSugDetail(sugRow())

    await vm.review('ADOPTED')

    expect(messageMock.error).toHaveBeenCalledWith('该建议已被其他管理员处理')
    expect(feedbackApi.adminSuggestions).toHaveBeenCalledTimes(2)
  })
})
