import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
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
  setStorage: vi.fn(),
  removeStorage: vi.fn()
}))

// 修复VIII B2：WS 4401 处理链依赖（单飞刷新/跳登录）——mock 掉不发真请求
vi.mock('@/api/request', () => ({
  default: {},
  tryRefreshAccessToken: vi.fn().mockResolvedValue(null),
  redirectToLogin: vi.fn()
}))

import { chatApi } from '@/api/chat'
import { getStorage, setStorage, removeStorage, STORAGE_KEYS } from '@/utils/storage'
import { tryRefreshAccessToken, redirectToLogin } from '@/api/request'

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

  it('does not inject the retired doubao 2.0 model when storage is empty', () => {
    vi.mocked(getStorage).mockReturnValue(null)

    const store = useChatStore()

    expect(store.selectedModel).toBeNull()
  })

  it('persists selected model changes', () => {
    const store = useChatStore()

    store.setSelectedModel('kimi-k2')

    expect(store.selectedModel).toBe('kimi-k2')
    expect(setStorage).toHaveBeenCalledWith(STORAGE_KEYS.CHAT_SELECTED_MODEL, 'kimi-k2')
  })

  it('removes stale persisted model when selection is cleared', () => {
    const store = useChatStore()
    store.setSelectedModel('')

    expect(store.selectedModel).toBeNull()
    expect(removeStorage).toHaveBeenCalledWith(STORAGE_KEYS.CHAT_SELECTED_MODEL)
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
    }), expect.anything())
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
    }), expect.anything())
    const userMsg = store.messages[0]
    expect(userMsg.role).toBe('USER')
    expect(JSON.parse(userMsg.metadata!)).toEqual({
      attachments: [{ fileId: 'f-abc.pdf', name: '课件.pdf' }]
    })
  })

  // 修复IX-1 A5：思考档位随消息透传（undefined=模型未声明，字段省略走后端零参数现状）
  it('threads thinking level into the stream request and omits it when undeclared', async () => {
    vi.mocked(chatApi.streamNewMessage).mockResolvedValue(streamResponse([
      'data: {"type":"DONE","sessionId":1}',
      ''
    ]))
    const store = useChatStore()

    await store.sendStreamingMessage('深想一步', undefined, undefined, undefined, undefined, undefined, 'DEEP')
    expect(chatApi.streamNewMessage).toHaveBeenCalledWith(expect.objectContaining({
      message: '深想一步',
      thinkingLevel: 'DEEP'
    }), expect.anything())
  })

  it('omits thinkingLevel field when not provided', async () => {
    vi.mocked(chatApi.streamNewMessage).mockResolvedValue(streamResponse([
      'data: {"type":"DONE","sessionId":1}',
      ''
    ]))
    const store = useChatStore()

    await store.sendStreamingMessage('默认')
    const payload = vi.mocked(chatApi.streamNewMessage).mock.calls[0][0] as unknown as Record<string, unknown>
    expect(payload.thinkingLevel).toBeUndefined()
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

// ============================================================
// 修复VIII B2（VIII-3）：WS 首消息鉴权——token 出 URL 改走首帧载荷；
// auth_ok 门闩（业务帧忽略/发送回退 REST）；4401 单飞刷新一次重连、再失败跳登录。
// ============================================================

/** 测试替身 WebSocket：记录 URL 与已发帧，由测试手动驱动 open/message/close。 */
class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  static CONNECTING = 0
  static OPEN = 1
  static CLOSING = 2
  static CLOSED = 3
  url: string
  readyState = 0
  sent: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((ev: { data: string }) => void) | null = null
  onclose: ((ev: { code: number }) => void) | null = null
  onerror: (() => void) | null = null

  constructor(url: string) {
    this.url = url
    FakeWebSocket.instances.push(this)
  }

  send(data: string) {
    this.sent.push(data)
  }

  close() {
    this.readyState = 3
  }

  // ---- 测试驱动（模拟服务端行为）----
  open() {
    this.readyState = 1
    this.onopen?.()
  }

  serverMessage(data: unknown) {
    this.onmessage?.({ data: JSON.stringify(data) })
  }

  serverClose(code: number) {
    this.readyState = 3
    this.onclose?.({ code })
  }
}

const mockedRefresh = vi.mocked(tryRefreshAccessToken)
const mockedRedirect = vi.mocked(redirectToLogin)

describe('chat store WS 首消息鉴权（修复VIII B2）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    // 恢复默认实现（clearAllMocks 只清调用记录，这里显式给默认返回值防串扰）
    mockedRefresh.mockResolvedValue(null)
    FakeWebSocket.instances = []
    vi.stubGlobal('WebSocket', FakeWebSocket)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function expectedWsUrl() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${protocol}//${window.location.host}/ws/chat`
  }

  it('连接 URL 不带 token（不出现在 URL/日志面），open 后首帧发 {type:"auth"}', () => {
    const store = useChatStore()
    store.connectWS()

    const sock = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
    expect(sock.url).toBe(expectedWsUrl())
    expect(sock.url).not.toContain('token=')

    sock.open()
    expect(sock.sent).toHaveLength(1)
    expect(JSON.parse(sock.sent[0])).toEqual({ type: 'auth', token: 'token' })
  })

  it('auth_ok 前服务端业务帧一律忽略，sendWSMessage 回退 REST', async () => {
    // 无 currentSessionId → REST 回退走 sendNewMessage（新会话分支）
    vi.mocked(chatApi.sendNewMessage).mockResolvedValue({
      data: { code: 200, msg: 'ok', data: { id: 9, messageId: 9, sessionId: 1, role: 'ASSISTANT', content: '回声', createdAt: '2026-08-29T00:00:00Z' } }
    } as any)
    const store = useChatStore()
    store.connectWS()
    const sock = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
    sock.open()

    // 未认证期到达的业务帧不进流缓冲
    sock.serverMessage({ type: 'CHUNK', content: '不该出现' })
    expect(store.streamingContent).toBe('')

    // 门闩：auth_ok 前发送走 REST 回退（防未认证业务帧被服务端 close(4401)）
    const p = store.sendWSMessage('hello')
    await p
    expect(chatApi.sendNewMessage).toHaveBeenCalledWith(expect.objectContaining({ message: 'hello' }))
    expect(sock.sent.filter((f: string) => !f.includes('"auth"'))).toHaveLength(0)
  })

  it('auth_ok 后业务帧放行且 sendWSMessage 直走 WS', () => {
    const store = useChatStore()
    store.connectWS()
    const sock = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
    sock.open()

    sock.serverMessage({ type: 'auth_ok' })
    sock.serverMessage({ type: 'CHUNK', content: '流内容' })
    expect(store.streamingContent).toBe('流内容')

    store.sendWSMessage('hi')
    const business = sock.sent.filter((f: string) => !f.includes('"auth"'))
    expect(business).toHaveLength(1)
    expect(JSON.parse(business[0]).message).toBe('hi')
    expect(chatApi.sendMessage).not.toHaveBeenCalled()
  })

  it('4401：单飞刷新一次 → 用新 token 重连重走首消息鉴权', async () => {
    mockedRefresh.mockResolvedValue('fresh-token')
    const store = useChatStore()
    store.connectWS()
    const first = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
    first.open()
    first.serverClose(4401)

    expect(mockedRefresh).toHaveBeenCalledTimes(1)
    // 刷新成功 → 新 token 已落库，重连后首帧携带新令牌
    vi.mocked(getStorage).mockImplementation((key: string) =>
      key === STORAGE_KEYS.ACCESS_TOKEN ? 'fresh-token' : null)
    await Promise.resolve()
    await Promise.resolve()

    expect(FakeWebSocket.instances).toHaveLength(2)
    const second = FakeWebSocket.instances[1]
    expect(second.url).toBe(expectedWsUrl())
    expect(second.url).not.toContain('token')
    second.open()
    expect(JSON.parse(second.sent[0])).toEqual({ type: 'auth', token: 'fresh-token' })
    expect(mockedRedirect).not.toHaveBeenCalled()
  })

  it('刷新成功重连后再次 4401 → 直接跳登录（防刷新/重连风暴）', async () => {
    mockedRefresh.mockResolvedValue('fresh-token')
    const store = useChatStore()
    store.connectWS()
    const first = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
    first.open()
    first.serverClose(4401)
    await Promise.resolve()
    await Promise.resolve()

    expect(FakeWebSocket.instances).toHaveLength(2)
    FakeWebSocket.instances[1].open()
    FakeWebSocket.instances[1].serverClose(4401)

    expect(mockedRedirect).toHaveBeenCalledTimes(1)
    expect(mockedRefresh).toHaveBeenCalledTimes(1)
    expect(FakeWebSocket.instances).toHaveLength(2)
  })

  it('4401 刷新失败 → 跳登录且不再重连', async () => {
    mockedRefresh.mockResolvedValue(null)
    const store = useChatStore()
    store.connectWS()
    const sock = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
    sock.open()
    sock.serverClose(4401)
    await Promise.resolve()
    await Promise.resolve()

    expect(mockedRedirect).toHaveBeenCalledTimes(1)
    expect(FakeWebSocket.instances).toHaveLength(1)
  })

  it('重连 auth_ok 复位刷新标记：其后再次 4401 仍走刷新而非跳登录', async () => {
    mockedRefresh.mockResolvedValue('fresh-token')
    const store = useChatStore()
    store.connectWS()
    const first = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
    first.open()
    first.serverClose(4401)
    await Promise.resolve()
    await Promise.resolve()

    const second = FakeWebSocket.instances[1]
    second.open()
    second.serverMessage({ type: 'auth_ok' })
    second.serverClose(4401)

    expect(mockedRefresh).toHaveBeenCalledTimes(2)
    expect(mockedRedirect).not.toHaveBeenCalled()
  })

  it('非 4401 关闭码：不刷新不跳登录（维持「ChatView 重挂载再连」语义）', async () => {
    const store = useChatStore()
    store.connectWS()
    const sock = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]
    sock.open()
    sock.serverClose(1006)

    await Promise.resolve()
    expect(mockedRefresh).not.toHaveBeenCalled()
    expect(mockedRedirect).not.toHaveBeenCalled()
    expect(FakeWebSocket.instances).toHaveLength(1)
    expect(store.wsConnected).toBe(false)
  })
})

// ============================================================
// Phase4 实测修复（Bug #8）：登出清 chat store——此前登出后换号登录，
// ChatView 直接显示上一用户会话/消息（Pinia 状态跨登录存活，纯前端内存残留）。
// ============================================================
import { nextTick } from 'vue'
import { useAuthStore } from './auth'

describe('chat store · Bug #8 登出清态', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('auth.userInfo 置空 → 会话/消息/流态全清（登出后换号不串台）', async () => {
    const auth = useAuthStore()
    auth.userInfo = { id: 1, username: 'admin', email: null, avatar: null, roles: [], permissions: [] } as any

    const store = useChatStore()
    store.sessions = [{ id: 9, title: '旧用户会话', updatedAt: '' } as any]
    store.messages = [{ id: 1, sessionId: 9, role: 'USER', content: '上一用户消息', metadata: null, createdAt: '' } as any]
    store.currentSessionId = 9
    store.streamingContent = '半截流式回答'
    store.sending = true

    auth.userInfo = null   // 登出
    await nextTick()       // pre-flush watch 生效

    expect(store.sessions).toHaveLength(0)
    expect(store.messages).toHaveLength(0)
    expect(store.currentSessionId).toBeNull()
    expect(store.streamingContent).toBe('')
    expect(store.sending).toBe(false)
  })

  it('登录/换号（null → 有值）不清态——清态只绑登出沿', async () => {
    const auth = useAuthStore()
    const store = useChatStore()
    store.messages = [{ id: 2, role: 'USER', content: 'x', metadata: null, createdAt: '' } as any]

    auth.userInfo = { id: 2, username: 'pm_tester', email: null, avatar: null, roles: [], permissions: [] } as any
    await nextTick()

    expect(store.messages).toHaveLength(1)
  })
})
