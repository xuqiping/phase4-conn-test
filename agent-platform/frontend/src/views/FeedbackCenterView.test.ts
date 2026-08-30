import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { NUpload } from 'naive-ui'
import FeedbackCenterView from './FeedbackCenterView.vue'
import { feedbackApi, uploadFeedbackFile } from '@/api/feedback'

// ---- naive-ui useMessage mock（组件本体用真实 naive 渲染，仅截 message） ----
const messageMock = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

// ---- vue-router mock（route.query.tab 逐测试可变） ----
const { routeQuery, routerPushMock } = vi.hoisted(() => ({
  routeQuery: { value: {} as Record<string, string> },
  routerPushMock: vi.fn()
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: routeQuery.value }),
  useRouter: () => ({ push: routerPushMock })
}))

// ---- API mock ----
vi.mock('@/api/feedback', async (importOriginal) => {
  const orig = await importOriginal<typeof import('@/api/feedback')>()
  return {
    ...orig,
    uploadFeedbackFile: vi.fn(),
    feedbackApi: {
      ...orig.feedbackApi,
      submitSuggestion: vi.fn(),
      mySuggestions: vi.fn(),
      submitQuestion: vi.fn(),
      myQuestions: vi.fn(),
      faq: vi.fn(),
      helpArticles: vi.fn(),
      helpArticle: vi.fn()
    }
  }
})

function apiOk<T>(data: T) {
  return { data: { code: 200, msg: 'success', data } }
}
const emptyPage = { records: [], total: 0, pageNum: 1, pageSize: 10 }

// 页面含 ModuleScene（useThemeStore），挂载前需活动 pinia
beforeEach(() => setActivePinia(createPinia()))

describe('FeedbackCenterView（19x 反馈与帮助三合一）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeQuery.value = {}
    vi.mocked(feedbackApi.mySuggestions).mockResolvedValue(apiOk(emptyPage) as never)
    vi.mocked(feedbackApi.myQuestions).mockResolvedValue(apiOk(emptyPage) as never)
    vi.mocked(feedbackApi.faq).mockResolvedValue(apiOk(emptyPage) as never)
    vi.mocked(feedbackApi.helpArticles).mockResolvedValue(apiOk([]) as never)
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('三 tab 渲染：提建议/提问题/使用说明', async () => {
    const wrapper = mount(FeedbackCenterView)
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('提建议')
    expect(text).toContain('提问题')
    expect(text).toContain('使用说明')
  })

  it('?tab=questions 预选提问台（铃铛跳转入参）', async () => {
    routeQuery.value = { tab: 'questions' }
    const wrapper = mount(FeedbackCenterView)
    await flushPromises()

    expect(wrapper.find('.n-tabs-tab--active').text()).toBe('提问题')
  })

  it('FAQ 检索防抖 300ms：连输只发一次请求', async () => {
    vi.useFakeTimers()
    routeQuery.value = { tab: 'questions' } // FAQ 在提问台 pane，激活才渲染
    const wrapper = mount(FeedbackCenterView)
    await flushPromises()
    const initialCalls = vi.mocked(feedbackApi.faq).mock.calls.length // onMounted 1 次

    const faqInput = wrapper.find('input[placeholder*="搜索常见问题"]')
    expect(faqInput.exists()).toBe(true)
    await faqInput.setValue('积')
    await faqInput.setValue('积分')
    await faqInput.setValue('积分怎')
    await vi.advanceTimersByTimeAsync(299)
    expect(vi.mocked(feedbackApi.faq).mock.calls.length).toBe(initialCalls) // 未到 300ms 不发

    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(vi.mocked(feedbackApi.faq).mock.calls.length).toBe(initialCalls + 1)
    const faqCalls = vi.mocked(feedbackApi.faq).mock.calls
    expect(faqCalls[faqCalls.length - 1][0]).toMatchObject({ kw: '积分怎' })
  })

  it('附件第 4 个被拦截：uploadFeedbackFile 只调 3 次 + warning 提示', async () => {
    vi.mocked(uploadFeedbackFile).mockImplementation(async (f: File) =>
      apiOk({ fileId: `fid-${f.name}`, url: '/u', name: f.name }) as never)

    const wrapper = mount(FeedbackCenterView)
    await flushPromises()

    const customRequest = wrapper.findComponent(NUpload).props('customRequest') as (o: unknown) => Promise<void>
    const mk = (name: string) => ({
      file: { file: new File(['x'], name, { type: 'image/png' }) },
      onFinish: vi.fn()
    })
    await customRequest(mk('a.png'))
    await customRequest(mk('b.png'))
    await customRequest(mk('c.png'))
    await customRequest(mk('d.png')) // 第 4 个：前端拦截

    expect(uploadFeedbackFile).toHaveBeenCalledTimes(3)
    expect(messageMock.warning).toHaveBeenCalledWith('附件最多 3 个')
    // 已挂 3 个 → 上传按钮文案显示 3/3
    expect(wrapper.text()).toContain('添加截图（3/3）')
  })

  it('XSS：帮助文 markdown 中 <script> 渲染为转义文本（renderMarkdown html:false）', async () => {
    routeQuery.value = { tab: 'help' } // 说明台 pane 激活才渲染正文
    vi.mocked(feedbackApi.helpArticles).mockResolvedValue(apiOk([
      { slug: 'xss-doc', title: '测试文', category: '通用', sortOrder: 1, publishedAt: null }
    ]) as never)
    vi.mocked(feedbackApi.helpArticle).mockResolvedValue(apiOk({
      slug: 'xss-doc', title: '测试文', category: '通用', sortOrder: 1, publishedAt: null,
      contentMd: '正文 <script>alert(1)</script> 结束'
    }) as never)

    const wrapper = mount(FeedbackCenterView)
    await flushPromises()

    const html = wrapper.html()
    expect(html).not.toContain('<script>alert(1)</script>')
    expect(html).toContain('&lt;script&gt;')
  })
})
