import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import DocumentRelationModal from './DocumentRelationModal.vue'
import DocumentRelationSuggestionModal from './DocumentRelationSuggestionModal.vue'
import { knowledgeApi } from '@/api/knowledge'
import type { AxiosResponse } from 'axios'
import type { KnowledgeDocument, KnowledgeRelation, KnowledgeRelationSuggestion } from '@/api/knowledge'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    listRelations: vi.fn(),
    createRelation: vi.fn(),
    deleteRelation: vi.fn(),
    listRelationSuggestions: vi.fn(),
    adoptRelationSuggestion: vi.fn(),
    ignoreRelationSuggestion: vi.fn(),
    listDocuments: vi.fn()
  }
}))

function response<T>(data: T) {
  return {
    data: { code: 200, message: 'ok', data },
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: {} as never }
  } as never as AxiosResponse<T>
}

const doc = { id: 11, title: '差旅制度' } as KnowledgeDocument

/** n-modal teleport 到 body——wrapper.text() 摸不到，统一从 body 断言（同 DocumentManager 版本弹窗惯例） */
function bodyText() {
  return document.body.textContent || ''
}

function bodyButton(label: string): HTMLButtonElement | undefined {
  return Array.from(document.body.querySelectorAll('button'))
    .find(b => (b.textContent || '').trim() === label)
}

const edges: KnowledgeRelation[] = [
  {
    id: 1, kbId: 1, direction: 'OUT', relationType: 'MUST_CITE',
    otherDocId: 12, otherDocTitle: '差旅术语表', note: '术语必带',
    createdBy: 9, createdAt: '2026-09-01T00:00:00Z'
  },
  {
    id: 2, kbId: 1, direction: 'IN', relationType: 'MAY_BE_CITED',
    otherDocId: 13, otherDocTitle: '报销流程', note: null,
    createdBy: 9, createdAt: '2026-09-02T00:00:00Z'
  }
]

async function mountRelationModal(canManage: boolean) {
  vi.mocked(knowledgeApi.listRelations).mockResolvedValue(response(edges) as never)
  vi.mocked(knowledgeApi.listDocuments).mockResolvedValue(response([]) as never)
  const wrapper = mount(DocumentRelationModal, {
    props: { show: true, kbId: 1, doc, canManage }
  })
  await flushPromises()
  return wrapper
}

describe('DocumentRelationModal · C1 关联边查看/管理', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
  })

  it('成员（无 canManage）：出/入边+类型徽标可见，无添加区、无删除钮（plan L1 只读）', async () => {
    await mountRelationModal(false)
    const text = bodyText()
    // 出边 + 入边 + 类型中文徽标
    expect(text).toContain('差旅术语表')
    expect(text).toContain('报销流程')
    expect(text).toContain('必须引用')
    expect(text).toContain('相关推荐')
    // 只读门：添加区/删除不出现（'建立'钮查按钮而非文案——表头「建立时间」含该词）
    expect(text).not.toContain('添加关联')
    expect(bodyButton('删除')).toBeUndefined()
    expect(bodyButton('建立')).toBeUndefined()
  })

  it('canManage：添加关联区（同库文档选择器+四类型+备注+建立）可见', async () => {
    await mountRelationModal(true)
    const text = bodyText()
    expect(text).toContain('添加关联')
    expect(text).toContain('选择同库文档')
    expect(text).toContain('关系类型')
    expect(bodyButton('建立')).toBeDefined()
  })

  it('canManage：canAdd 门——未选齐时建立钮 disabled', async () => {
    await mountRelationModal(true)
    const btn = bodyButton('建立')
    expect(btn).toBeDefined()
    expect(btn!.disabled).toBe(true)
  })

  it('无边：空态文案出现且 listDocuments 仍被调（选择器预载）', async () => {
    vi.mocked(knowledgeApi.listRelations).mockResolvedValue(response([]) as never)
    vi.mocked(knowledgeApi.listDocuments).mockResolvedValue(response([]) as never)
    mount(DocumentRelationModal, {
      props: { show: true, kbId: 1, doc, canManage: false }
    })
    await flushPromises()
    expect(bodyText()).toContain('暂无关联边')
    expect(knowledgeApi.listDocuments).toHaveBeenCalledWith(1)
  })
})

describe('DocumentRelationSuggestionModal · C1 建议（仅 canManage 入口）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
  })

  const suggestions: KnowledgeRelationSuggestion[] = [
    {
      id: 77, kbId: 1, docIdA: 11, docIdB: 12,
      docTitleA: '差旅制度', docTitleB: '差旅术语表',
      coRecallCount: 5, sampleQueryHash: 'abc', status: 'PENDING',
      lastSeenAt: '2026-09-03T00:00:00Z', createdAt: '2026-09-01T00:00:00Z'
    }
  ]

  it('渲染建议对：两端标题 + 共召回次数 + 采纳/忽略钮', async () => {
    vi.mocked(knowledgeApi.listRelationSuggestions).mockResolvedValue(response(suggestions) as never)
    mount(DocumentRelationSuggestionModal, {
      props: { show: true, kbId: 1 }
    })
    await flushPromises()
    const text = bodyText()
    expect(text).toContain('差旅制度')
    expect(text).toContain('差旅术语表')
    expect(text).toContain('共召回 5 次')
    expect(text).toContain('采纳并建边')
    expect(text).toContain('忽略')
    // 方向选择器默认选中 A→B，选中项 label 直显（下拉另一项展开才渲染，不在此断言）
    expect(text).toContain('命中「差旅制度」时带出「差旅术语表」')
  })

  it('空建议：空态文案', async () => {
    vi.mocked(knowledgeApi.listRelationSuggestions).mockResolvedValue(response([]) as never)
    mount(DocumentRelationSuggestionModal, {
      props: { show: true, kbId: 1 }
    })
    await flushPromises()
    expect(bodyText()).toContain('暂无待处理建议')
  })

  it('采纳：默认方向 A→B + 默认类型 MAY_CITE，调 adoptRelationSuggestion', async () => {
    vi.mocked(knowledgeApi.listRelationSuggestions).mockResolvedValue(response(suggestions) as never)
    vi.mocked(knowledgeApi.adoptRelationSuggestion).mockResolvedValue(response(null) as never)
    mount(DocumentRelationSuggestionModal, {
      props: { show: true, kbId: 1 }
    })
    await flushPromises()
    const adoptBtn = bodyButton('采纳并建边')
    expect(adoptBtn).toBeDefined()
    adoptBtn!.click()
    await flushPromises()
    expect(knowledgeApi.adoptRelationSuggestion).toHaveBeenCalledWith(77, {
      fromDocId: 11,
      relationType: 'MAY_CITE'
    })
  })

  it('忽略：调 ignoreRelationSuggestion 并刷新列表', async () => {
    vi.mocked(knowledgeApi.listRelationSuggestions)
      .mockResolvedValueOnce(response(suggestions) as never)
      .mockResolvedValueOnce(response([]) as never)
    vi.mocked(knowledgeApi.ignoreRelationSuggestion).mockResolvedValue(response(null) as never)
    mount(DocumentRelationSuggestionModal, {
      props: { show: true, kbId: 1 }
    })
    await flushPromises()
    const ignoreBtn = bodyButton('忽略')
    expect(ignoreBtn).toBeDefined()
    ignoreBtn!.click()
    await flushPromises()
    expect(knowledgeApi.ignoreRelationSuggestion).toHaveBeenCalledWith(77)
    expect(bodyText()).toContain('暂无待处理建议')
  })
})
