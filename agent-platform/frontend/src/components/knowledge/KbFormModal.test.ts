import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import KbFormModal from './KbFormModal.vue'
import { knowledgeApi } from '@/api/knowledge'
import { llmApi } from '@/api/llm'
import type { KnowledgeBase } from '@/api/knowledge'

const messageMock = { success: vi.fn(), error: vi.fn(), warning: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    getRankingConfig: vi.fn(),
    updateBase: vi.fn(),
    createBase: vi.fn(),
    updateRankingConfig: vi.fn()
  }
}))

vi.mock('@/api/llm', () => ({
  llmApi: {
    listActiveModels: vi.fn()
  }
}))

function kb(over: Partial<KnowledgeBase> = {}): KnowledgeBase {
  return {
    id: 1,
    name: 'kb1',
    description: null,
    visibility: 'PRIVATE',
    embeddingModel: 'emb-active',
    rerankModel: null,
    answerModel: null,
    confidential: false,
    summaryStrategy: 'PER_SECTION',
    status: 'ACTIVE',
    createdBy: 7,
    createdAt: '2026-08-18T00:00:00Z',
    canManage: true,
    canWrite: true,
    canRead: true,
    ...over
  } as KnowledgeBase
}

function stubActiveModels(embeddings: string[], chats: string[]) {
  vi.mocked(llmApi.listActiveModels).mockImplementation(((category: string) => Promise.resolve({
    data: { code: 200, message: 'ok', data: category === 'EMBEDDING' ? embeddings : chats },
    status: 200, statusText: 'OK', headers: {}, config: {}
  })) as never)
}

function stubRankingConfig() {
  vi.mocked(knowledgeApi.getRankingConfig).mockResolvedValue({
    data: { code: 200, message: 'ok', data: { mode: 'LLM', model: null, candidateLimit: 30, finalLimit: 10, batchSize: 10, timeoutMs: 4000, fallbackPolicy: 'FAIL_CLOSED', highAccuracyEnabled: false } },
    status: 200, statusText: 'OK', headers: {}, config: {}
  } as never)
  vi.mocked(knowledgeApi.updateRankingConfig).mockResolvedValue({
    data: { code: 200, message: 'ok', data: null },
    status: 200, statusText: 'OK', headers: {}, config: {}
  } as never)
}

async function mountModal(editData: KnowledgeBase | null) {
  const wrapper = mount(KbFormModal, { props: { show: true, editData } })
  await flushPromises()
  return wrapper
}

describe('KbFormModal · 14x#1 模型下拉与 L4 横幅', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
    stubActiveModels(['emb-active', 'emb-b'], ['glm-5.1', 'doubao-pro'])
    stubRankingConfig()
  })

  it('下拉装配：EMBEDDING/CHAT 启用列表 + 空选项「跟随全局默认/留空默认」', async () => {
    const wrapper = await mountModal(kb())
    const vm = wrapper.vm as unknown as {
      embeddingOptions: { label: string; value: string }[]
      answerOptions: { label: string; value: string }[]
    }
    expect(vm.embeddingOptions.map(o => o.value)).toEqual(['', 'emb-active', 'emb-b'])
    expect(vm.embeddingOptions[0].label).toContain('管理员默认')
    expect(vm.answerOptions.map(o => o.value)).toEqual(['', 'glm-5.1', 'doubao-pro'])
    expect(vm.answerOptions[0].label).toBe('跟随全局默认')
  })

  it('存量未启用值（模型已下线）补灰选项，防静默清空', async () => {
    const wrapper = await mountModal(kb({ embeddingModel: 'retired-emb', answerModel: 'retired-chat' }))
    const vm = wrapper.vm as unknown as {
      embeddingOptions: { label: string; value: string }[]
      answerOptions: { label: string; value: string }[]
    }
    expect(vm.embeddingOptions.some(o => o.value === 'retired-emb' && o.label.includes('未在启用列表'))).toBe(true)
    expect(vm.answerOptions.some(o => o.value === 'retired-chat' && o.label.includes('未在启用列表'))).toBe(true)
  })

  it('L4：换 embedding 返回 warning → 横幅出现且弹窗不自动关闭', async () => {
    vi.mocked(knowledgeApi.updateBase).mockResolvedValue({
      data: {
        code: 200, message: 'ok',
        data: { ...kb(), embeddingModel: 'emb-b', warning: '向量模型已变更，存量向量仍为旧模型空间；请到「索引运维」重建索引后再检索' }
      },
      status: 200, statusText: 'OK', headers: {}, config: {}
    } as never)
    const wrapper = await mountModal(kb())
    const vm = wrapper.vm as unknown as { handleSubmit: () => Promise<void> }
    await vm.handleSubmit()
    await flushPromises()

    expect(document.body.querySelector('.kb-form__rebuild-alert')?.textContent).toContain('重建索引')
    expect((wrapper.vm as unknown as { visible: boolean }).visible).toBe(true)
    expect(messageMock.warning).toHaveBeenCalled()
  })

  it('无 warning：正常保存并关闭弹窗', async () => {
    vi.mocked(knowledgeApi.updateBase).mockResolvedValue({
      data: { code: 200, message: 'ok', data: { ...kb(), warning: null } },
      status: 200, statusText: 'OK', headers: {}, config: {}
    } as never)
    const wrapper = await mountModal(kb())
    const vm = wrapper.vm as unknown as { handleSubmit: () => Promise<void> }
    await vm.handleSubmit()
    await flushPromises()

    expect(document.body.querySelector('.kb-form__rebuild-alert')).toBeNull()
    expect((wrapper.vm as unknown as { visible: boolean }).visible).toBe(false)
    expect(messageMock.success).toHaveBeenCalled()
  })
})

describe('KbFormModal · 14x#3 保密库开关', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
    stubActiveModels(['emb-active'], ['glm-5.1'])
    stubRankingConfig()
  })

  it('编辑保密库回显开关，保存 payload 透传 confidential', async () => {
    vi.mocked(knowledgeApi.updateBase).mockResolvedValue({
      data: { code: 200, message: 'ok', data: kb({ confidential: true }) },
      status: 200, statusText: 'OK', headers: {}, config: {}
    } as never)
    const wrapper = await mountModal(kb({ confidential: true }))
    const vm = wrapper.vm as unknown as { form: { confidential: boolean }, handleSubmit: () => Promise<void> }
    expect(vm.form.confidential).toBe(true)

    await vm.handleSubmit()
    await flushPromises()
    expect(knowledgeApi.updateBase).toHaveBeenCalledWith(1, expect.objectContaining({ confidential: true }))
  })

  it('PUBLIC 库开关禁用并提示互斥', async () => {
    const wrapper = await mountModal(kb({ visibility: 'PUBLIC' }))
    const vm = wrapper.vm as unknown as { form: { visibility: string, confidential: boolean } }
    expect(vm.form.visibility).toBe('PUBLIC')
    // 提示文案切换为互斥说明；开关 DOM 禁用态（modal teleport 到 body）
    expect(document.body.querySelector('.kb-form__confidential-hint')?.textContent).toContain('公开库不支持保密')
    expect(document.body.querySelector('.kb-form__confidential .n-switch--disabled')).not.toBeNull()
  })
})
