import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AdminHelpArticlesView from './AdminHelpArticlesView.vue'
import { feedbackApi } from '@/api/feedback'
import type { AdminArticleVO } from '@/api/feedback'

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
      adminArticles: vi.fn(),
      createArticle: vi.fn(),
      updateArticle: vi.fn(),
      setArticlePublished: vi.fn(),
      deleteArticle: vi.fn()
    }
  }
})

function apiOk<T>(data: T) {
  return { data: { code: 200, msg: 'success', data } }
}
const emptyPage = { records: [], total: 0, pageNum: 1, pageSize: 10 }

const articleRow = (over: Partial<AdminArticleVO> = {}): AdminArticleVO => ({
  id: 5,
  slug: 'how-to-recharge',
  title: '如何充值',
  category: '计费',
  sortOrder: 1,
  contentMd: '# 正文',
  published: false,
  publishedAt: null,
  createdAt: '2026-08-21T10:00:00Z',
  updatedAt: null,
  ...over
})

type Vm = {
  load: (p?: number) => Promise<void>
  openCreate: () => void
  openEdit: (r: AdminArticleVO) => void
  save: () => Promise<void>
  togglePublish: (r: AdminArticleVO, v: boolean) => Promise<void>
  confirmDelete: (r: AdminArticleVO) => void
  form: { slug: string; title: string; category: string; sortOrder: number; contentMd: string }
  editingId: number | null
}

function mountView() {
  const wrapper = mount(AdminHelpArticlesView)
  return { wrapper, vm: wrapper.vm as unknown as Vm }
}

describe('AdminHelpArticlesView（19x#3 admin 帮助文章）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(feedbackApi.adminArticles).mockResolvedValue(apiOk(emptyPage) as never)
    vi.mocked(feedbackApi.createArticle).mockResolvedValue(apiOk({ id: 9 }) as never)
    vi.mocked(feedbackApi.updateArticle).mockResolvedValue(apiOk(null) as never)
    vi.mocked(feedbackApi.setArticlePublished).mockResolvedValue(apiOk(null) as never)
    vi.mocked(feedbackApi.deleteArticle).mockResolvedValue(apiOk(null) as never)
  })

  it('挂载加载列表', async () => {
    mountView()
    await flushPromises()
    expect(feedbackApi.adminArticles).toHaveBeenCalledWith({ page: 1, size: 10 })
  })

  it('新建保存：createArticle payload 正确（空分类→undefined 走后端默认）', async () => {
    const { vm } = mountView()
    await flushPromises()
    vm.openCreate()
    vm.form.slug = 'how-to-recharge'
    vm.form.title = '如何充值'
    vm.form.category = ''
    vm.form.contentMd = '# 正文'

    await vm.save()

    expect(feedbackApi.createArticle).toHaveBeenCalledWith({
      slug: 'how-to-recharge', title: '如何充值', category: undefined, sortOrder: 0, contentMd: '# 正文'
    })
    expect(messageMock.success).toHaveBeenCalledWith('已创建（未发布，用户不可见）')
  })

  it('slug 非法（大写/空格）→ canSave false，save 不调用', async () => {
    const { vm } = mountView()
    await flushPromises()
    vm.openCreate()
    vm.form.slug = 'Bad Slug'
    vm.form.title = '标题'
    vm.form.contentMd = '正文'

    await vm.save()

    expect(feedbackApi.createArticle).not.toHaveBeenCalled()
  })

  it('编辑保存走 updateArticle；slug 回填且 payload 带原 slug（后端忽略不改）', async () => {
    const { vm } = mountView()
    await flushPromises()
    vm.openEdit(articleRow())
    expect(vm.editingId).toBe(5)
    expect(vm.form.slug).toBe('how-to-recharge')

    vm.form.title = '如何充值（新版）'
    await vm.save()

    expect(feedbackApi.updateArticle).toHaveBeenCalledWith(5, {
      slug: 'how-to-recharge', title: '如何充值（新版）', category: '计费', sortOrder: 1, contentMd: '# 正文'
    })
    expect(feedbackApi.createArticle).not.toHaveBeenCalled()
  })

  it('发布开关：togglePublish 调 setArticlePublished 并本地置位', async () => {
    const { vm } = mountView()
    await flushPromises()
    const row = articleRow()
    await vm.togglePublish(row, true)

    expect(feedbackApi.setArticlePublished).toHaveBeenCalledWith(5, true)
    expect(row.published).toBe(true)
    expect(messageMock.success).toHaveBeenCalledWith('已发布，用户可见')
  })

  it('删除：二次确认后才调 deleteArticle（硬删释放 slug）', async () => {
    const { vm } = mountView()
    await flushPromises()
    vm.confirmDelete(articleRow())

    expect(dialogMock.warning).toHaveBeenCalledTimes(1)
    expect(feedbackApi.deleteArticle).not.toHaveBeenCalled()

    const opts = dialogMock.warning.mock.calls[0][0] as { onPositiveClick: () => Promise<void> }
    await opts.onPositiveClick()
    expect(feedbackApi.deleteArticle).toHaveBeenCalledWith(5)
  })
})
