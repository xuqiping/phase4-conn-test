import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DocumentManager from './DocumentManager.vue'
import { knowledgeApi } from '@/api/knowledge'
import { useKnowledgeStore } from '@/stores/knowledge'
import { useAuthStore } from '@/stores/auth'
import type { AxiosResponse } from 'axios'
import type { KnowledgeDocument, KnowledgeBase } from '@/api/knowledge'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    listDocuments: vi.fn(),
    listDocumentNodes: vi.fn(),
    documentAssetUrl: (id: number) => `/api/knowledge/documents/${id}/asset`,
    uploadDocument: vi.fn(),
    previewSheets: vi.fn(),
    createDocumentVersion: vi.fn(),
    activateVersion: vi.fn(),
    revokeVersion: vi.fn(),
    deleteDocument: vi.fn(),
    unquarantineDocument: vi.fn(),
    updateDocumentMetadata: vi.fn()
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

function mkDoc(id: number, over: Partial<KnowledgeDocument> = {}): KnowledgeDocument {
  return {
    id,
    kbId: 1,
    title: `doc${id}`,
    status: 'INDEXED',
    docType: 'PDF',
    fileRef: '/api/files/f1',
    fileHash: null,
    parseOptions: null,
    parseError: null,
    quarantineReason: null,
    originalName: null,
    currentVersionId: null,
    createdAt: '2026-08-18T00:00:00Z',
    ...over
  } as KnowledgeDocument
}

async function mountManager(canWrite: boolean, canManage = false, docs: KnowledgeDocument[] = [mkDoc(1), mkDoc(2, { status: 'QUARANTINED' })]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useKnowledgeStore()
  vi.mocked(knowledgeApi.listDocuments).mockResolvedValue(response(docs) as never)
  await store.loadDocuments(1)
  const wrapper = mount(DocumentManager, {
    props: { kbId: 1, canWrite, canManage },
    global: { plugins: [pinia] }
  })
  await flushPromises()
  return wrapper
}

describe('DocumentManager · 14x#2 per-KB 按钮显隐', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
  })

  it('写授权（canWrite, 无 canManage）：上传/直传可见；治理/解除隔离/删除不可见', async () => {
    const wrapper = await mountManager(true, false)
    const text = wrapper.text()
    expect(text).toContain('点击或拖拽文件到此处上传')
    expect(text).toContain('直接输入文本入库')
    expect(text).not.toContain('治理')
    expect(text).not.toContain('解除隔离')
    expect(text).not.toContain('删除')
  })

  it('只读授权（两 false）：上传/直传/治理动作全不可见，仅列表+版本', async () => {
    const wrapper = await mountManager(false, false)
    const text = wrapper.text()
    expect(text).not.toContain('点击或拖拽文件到此处上传')
    expect(text).not.toContain('直接输入文本入库')
    expect(text).not.toContain('治理')
    expect(text).not.toContain('解除隔离')
    expect(text).not.toContain('删除')
    expect(text).toContain('doc1')
    expect(text).toContain('版本')
  })

  it('管理授权（canManage）：治理/解除隔离/删除可见；隔离行优先显示解除隔离', async () => {
    const wrapper = await mountManager(true, true)
    const text = wrapper.text()
    expect(text).toContain('治理')
    expect(text).toContain('解除隔离')
    expect(text).toContain('删除')
  })

  it('版本弹窗：canWrite 无 canManage 不渲染上传新版本区（createVersion 走 canManage 门）', async () => {
    // n-modal teleport 到 body，取 document.body 断言
    const wrapper = await mountManager(true, false)
    const mgr = wrapper.vm as unknown as { openVersions: (d: KnowledgeDocument) => void }
    mgr.openVersions(mkDoc(1))
    await flushPromises()
    expect(document.body.querySelector('.doc-manager__version-upload')).toBeNull()

    document.body.innerHTML = ''
    const managed = await mountManager(true, true)
    const mgr2 = managed.vm as unknown as { openVersions: (d: KnowledgeDocument) => void }
    mgr2.openVersions(mkDoc(1))
    await flushPromises()
    expect(document.body.querySelector('.doc-manager__version-upload')).not.toBeNull()
  })
})

describe('DocumentManager · 14x#3 保密库原件入口隐藏', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
  })

  function mkKb(over: Partial<KnowledgeBase> = {}): KnowledgeBase {
    return {
      id: 1, name: 'sec', description: null, visibility: 'TEAM',
      embeddingModel: 'emb', rerankModel: null, answerModel: null, confidential: true,
      summaryStrategy: 'PER_SECTION', status: 'ACTIVE', createdBy: 99,
      createdAt: '2026-08-18T00:00:00Z', canManage: false, canWrite: false, canRead: true,
      ...over
    } as KnowledgeBase
  }

  async function mountConfidential() {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useKnowledgeStore()
    const auth = useAuthStore()
    store.bases = [mkKb()]
    ;(auth as unknown as { userInfo: { id: number, roles: string[] } }).userInfo = { id: 7, roles: ['user'] }
    vi.mocked(knowledgeApi.listDocuments).mockResolvedValue(response([mkDoc(1, { docType: 'FILE' })]) as never)
    vi.mocked(knowledgeApi.listDocumentNodes).mockResolvedValue(response([]) as never)
    await store.loadDocuments(1)
    const wrapper = mount(DocumentManager, {
      props: { kbId: 1, canWrite: false },
      global: { plugins: [pinia] }
    })
    await flushPromises()
    return { wrapper, auth }
  }

  it('保密库成员：原件入口以保密提示替代（无下载按钮/缩略图）', async () => {
    const { wrapper } = await mountConfidential()
    const mgr = wrapper.vm as unknown as {
      renderAsset: (d: KnowledgeDocument) => { children: unknown }
    }
    const vnode = mgr.renderAsset(mkDoc(1, { docType: 'FILE' }))
    expect(String(vnode.children)).toContain('保密库')
    expect(String(vnode.children)).not.toContain('下载原件')
  })

  it('owner 直通：仍渲染下载原件入口', async () => {
    const { wrapper, auth } = await mountConfidential()
    ;(auth as unknown as { userInfo: { id: number, roles: string[] } }).userInfo = { id: 99, roles: ['user'] }
    const mgr = wrapper.vm as unknown as {
      renderAsset: (d: KnowledgeDocument) => { children: unknown }
    }
    const vnode = mgr.renderAsset(mkDoc(1, { docType: 'FILE' }))
    expect(String(vnode.children)).not.toContain('保密库')
  })
})
