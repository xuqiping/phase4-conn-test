import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useChatStore } from './chat'

vi.mock('@/api/chat', () => ({
  chatApi: {
    listSessions: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getMessages: vi.fn(),
    deleteSession: vi.fn(),
    sendMessage: vi.fn(),
    sendNewMessage: vi.fn(),
    updateSessionTarget: vi.fn(),
    streamMessage: vi.fn(),
    streamNewMessage: vi.fn()
  }
}))

vi.mock('@/utils/storage', () => ({
  STORAGE_KEYS: {
    ACCESS_TOKEN: 'accessToken',
    CHAT_SELECTED_MODEL: 'chatSelectedModel',
    CHAT_SELECTED_TARGET: 'chatSelectedTarget'
  },
  getStorage: vi.fn((key: string) => key === 'accessToken' ? 'token' : null),
  setStorage: vi.fn()
}))

import { chatApi } from '@/api/chat'
import { getStorage, setStorage, STORAGE_KEYS } from '@/utils/storage'

function streamResponse(lines: string[]) {
  const encoder = new TextEncoder()
  let sent = false
  return {
    ok: true,
    body: {
      getReader() {
        return {
          async read() {
            if (sent) return { done: true, value: undefined }
            sent = true
            return { done: false, value: encoder.encode(lines.join('\n')) }
          }
        }
      }
    }
  } as any
}

describe('chat store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('shows stream errors as assistant messages', async () => {
    vi.mocked(chatApi.streamNewMessage).mockResolvedValue(streamResponse([
      'data: {"type":"ERROR","content":"LLM调用失败: 401 Unauthorized"}',
      ''
    ]))

    const store = useChatStore()
    await store.sendStreamingMessage('hello')

    expect(store.sending).toBe(false)
    expect(store.messages).toHaveLength(2)
    expect(store.messages[0].role).toBe('USER')
    expect(store.messages[1].role).toBe('ASSISTANT')
    expect(store.messages[1].content).toContain('LLM调用失败')
  })

  it('initializes selected model from local storage', () => {
    vi.mocked(getStorage).mockImplementation((key: string) =>
      key === STORAGE_KEYS.CHAT_SELECTED_MODEL ? 'deepseek-chat' : 'token'
    )

    const store = useChatStore()

    expect(store.selectedModel).toBe('deepseek-chat')
  })

  it('persists selected model changes', () => {
    const store = useChatStore()

    store.setSelectedModel('kimi-k2')

    expect(store.selectedModel).toBe('kimi-k2')
    expect(setStorage).toHaveBeenCalledWith(STORAGE_KEYS.CHAT_SELECTED_MODEL, 'kimi-k2')
  })

  it('initializes selected target from local storage', () => {
    vi.mocked(getStorage).mockImplementation((key: string) => {
      if (key === STORAGE_KEYS.CHAT_SELECTED_MODEL) return null
      if (key === STORAGE_KEYS.CHAT_SELECTED_TARGET) return 'agent:10'
      return 'token'
    })

    const store = useChatStore()

    expect(store.selectedTarget).toBe('agent:10')
  })

  it('persists selected target changes', () => {
    const store = useChatStore()

    store.setSelectedTarget('workflow:5')

    expect(store.selectedTarget).toBe('workflow:5')
    expect(setStorage).toHaveBeenCalledWith(STORAGE_KEYS.CHAT_SELECTED_TARGET, 'workflow:5')
  })

  it('sends selected agent target when streaming a new session', async () => {
    vi.mocked(chatApi.streamNewMessage).mockResolvedValue(streamResponse([
      'data: {"type":"DONE","sessionId":1}',
      ''
    ]))
    const store = useChatStore()
    store.setSelectedTarget('agent:10')

    await store.sendStreamingMessage('hello')

    expect(chatApi.streamNewMessage).toHaveBeenCalledWith(expect.objectContaining({
      message: 'hello',
      agentId: 10
    }))
  })

  it('updates the current session target and local session list', async () => {    vi.mocked(chatApi.listSessions).mockResolvedValue({ data: { code: 200, message: 'ok', data: [
      {
        id: 1,
        title: 'Run old workflow',
        agentId: null,
        agentName: null,
        workflowId: 5,
        workflowName: 'Old Workflow',
        mode: 'WORKFLOW',
        status: 'ACTIVE',
        createdAt: '2026-06-13T00:00:00Z',
        updatedAt: '2026-06-13T00:00:00Z'
      }
    ] } } as any)
    vi.mocked(chatApi.getMessages).mockResolvedValue({ data: { code: 200, message: 'ok', data: [] } } as any)
    vi.mocked(chatApi.updateSessionTarget).mockResolvedValue({ data: { code: 200, message: 'ok', data: {
      id: 1,
      title: 'Run old workflow',
      agentId: null,
      agentName: null,
      workflowId: 8,
      workflowName: 'New Workflow',
      mode: 'WORKFLOW',
      status: 'ACTIVE',
      createdAt: '2026-06-13T00:00:00Z',
      updatedAt: '2026-06-13T00:01:00Z'
    } } } as any)

    const store = useChatStore()
    await store.fetchSessions()
    await store.selectSession(1)

    await store.updateCurrentSessionTarget('workflow:8')

    expect(chatApi.updateSessionTarget).toHaveBeenCalledWith(1, { workflowId: 8 })
    expect(store.selectedTarget).toBe('workflow:8')
    expect(store.visibleTargetValue).toBe('workflow:8')
    expect(store.currentSession?.workflowName).toBe('New Workflow')
  })

  // 二期 P3（FR-201）：附件引用随请求发送（fileId 集）+ 本地用户回显 metadata.attachments
  it('threads attachment fileIds into the stream request and user echo metadata', async () => {
    vi.mocked(chatApi.streamNewMessage).mockResolvedValue(streamResponse([
      'data: {"type":"DONE","sessionId":1}',
      ''
    ]))
    const store = useChatStore()

    await store.sendStreamingMessage('看这个课件', undefined, undefined, [
      { fileId: 'f-abc.pdf', name: '课件.pdf' }
    ])

    expect(chatApi.streamNewMessage).toHaveBeenCalledWith(expect.objectContaining({
      message: '看这个课件',
      attachmentFileIds: ['f-abc.pdf']
    }))
    const userMsg = store.messages[0]
    expect(userMsg.role).toBe('USER')
    expect(JSON.parse(userMsg.metadata!)).toEqual({
      attachments: [{ fileId: 'f-abc.pdf', name: '课件.pdf' }]
    })
  })

  // 二期 P3（FR-203）：FILE_CARDS 帧（DONE 前到达）→ 并入助手消息 metadata.fileCards
  it('merges FILE_CARDS frames into the assistant message metadata', async () => {
    const card = {
      memoryId: 7,
      fileId: 'f-abc.pdf',
      originalName: '课件.pdf',
      fileKind: 'PDF',
      chunkCount: 12,
      weakMemory: false,
      fileCleaned: false,
      downloadable: true,
      l1: '《课件.pdf》：讲 hooks 原理',
      l2: null
    }
    vi.mocked(chatApi.streamNewMessage).mockResolvedValue(streamResponse([
      'data: {"type":"CHUNK","content":"这份课件讲了 hooks"}',
      `data: {"type":"FILE_CARDS","content":${JSON.stringify(JSON.stringify([card]))},"sessionId":1}`,
      'data: {"type":"DONE","sessionId":1}',
      ''
    ]))
    const store = useChatStore()

    await store.sendStreamingMessage('课件讲了什么')

    expect(store.messages).toHaveLength(2)
    const assistant = store.messages[1]
    expect(assistant.role).toBe('ASSISTANT')
    const meta = JSON.parse(assistant.metadata!)
    expect(meta.fileCards).toEqual([card])
  })
})
