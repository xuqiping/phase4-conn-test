import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { chatApi } from '@/api/chat'
import type { ChatSession, ChatMessage, ChatResponse } from '@/api/chat'
import { getStorage, setStorage, STORAGE_KEYS } from '@/utils/storage'

const DEFAULT_CHAT_MODEL = 'doubao-seed-2.0-code'
const DEFAULT_CHAT_TARGET = 'none'

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<ChatSession[]>([])
  const currentSessionId = ref<number | null>(null)
  const messages = ref<ChatMessage[]>([])
  const loading = ref(false)
  const sending = ref(false)
  const streamingContent = ref('')
  const streamingThinking = ref('')
  /** P3：RAG 命中引用（CITATION 帧捕获；DONE 前到达），DONE 时并入 message.metadata.citations 供 MessageBubble 渲染 [n]。 */
  const streamingCitations = ref<any[] | null>(null)
  /** 工作流 HUMAN_INPUT 待答问题规格（INPUT_REQUIRED 帧捕获；select 型可渲染选项按钮）。text 型直接用普通输入框作答即可。 */
  const pendingInput = ref<Record<string, any> | null>(null)
  const wsConnected = ref(false)
  const selectedModel = ref<string | null>(
    getStorage<string>(STORAGE_KEYS.CHAT_SELECTED_MODEL) || DEFAULT_CHAT_MODEL
  )
  const selectedTarget = ref<string>(
    getStorage<string>(STORAGE_KEYS.CHAT_SELECTED_TARGET) || DEFAULT_CHAT_TARGET
  )
  // 项目记忆 scope（V33）：写目标 + 读开关（扁平对称）。store 持态，sendMessage 直接读。
  const memProjectId = ref<number | null>(null)        // 写目标（null=总记忆会话）
  const memIncludeGlobal = ref<boolean>(true)          // 读开关：总记忆 on/off
  const memReadProjectIds = ref<number[]>([])          // 读开关：开启读取的项目集合

  /** 请求体用的 scope 字段（memIncludeGlobal 始终带 = scope 更新标记，后端据此持久化三列）。 */
  const memoryScopePayload = computed(() => ({
    projectId: memProjectId.value,
    memIncludeGlobal: memIncludeGlobal.value,
    memReadProjectIds: memReadProjectIds.value
  }))

  let ws: WebSocket | null = null

  function appendAssistantMessage(content: string, metadata: string | null = null) {
    messages.value.push({
      id: Date.now(),
      sessionId: currentSessionId.value ?? 0,
      role: 'ASSISTANT',
      content,
      metadata,
      createdAt: new Date().toISOString()
    })
  }

  function appendAssistantError(content?: string) {
    appendAssistantMessage(content || '消息发送失败，请检查模型配置或稍后重试。', JSON.stringify({ error: true }))
  }

  const currentSession = computed(() =>
    sessions.value.find(s => s.id === currentSessionId.value) ?? null
  )

  const visibleTargetValue = computed(() => {
    const session = currentSession.value
    if (session?.agentId) return `agent:${session.agentId}`
    if (session?.workflowId) return `workflow:${session.workflowId}`
    if (currentSessionId.value) return DEFAULT_CHAT_TARGET
    return selectedTarget.value
  })

  function resolveTargetPayload(targetValue = selectedTarget.value) {
    const [type, rawId] = targetValue.split(':')
    const id = Number(rawId)
    if (!Number.isFinite(id)) return {}
    if (type === 'agent') return { agentId: id }
    if (type === 'workflow') return { workflowId: id }
    return {}
  }

  function resolveSelectedTargetPayload() {
    return resolveTargetPayload()
  }

  async function fetchSessions() {
    loading.value = true
    try {
      const res = await chatApi.listSessions()
      sessions.value = res.data.data
    } finally {
      loading.value = false
    }
  }

  async function selectSession(sessionId: number | null) {
    currentSessionId.value = sessionId
    messages.value = []
    streamingContent.value = ''
    if (sessionId) {
      const res = await chatApi.getMessages(sessionId)
      messages.value = res.data.data
    }
  }

  async function deleteSession(sessionId: number) {
    await chatApi.deleteSession(sessionId)
    sessions.value = sessions.value.filter(s => s.id !== sessionId)
    if (currentSessionId.value === sessionId) {
      currentSessionId.value = null
      messages.value = []
    }
  }

  // 批量删除会话（ownership 过滤，后端返实删条数）。删后本地过滤；当前会话在内则清空。
  async function batchDeleteSessions(ids: number[]) {
    const res = await chatApi.batchDeleteSessions(ids)
    const deleted = res.data.data
    const idSet = new Set(ids)
    sessions.value = sessions.value.filter(s => !idSet.has(s.id))
    if (currentSessionId.value && idSet.has(currentSessionId.value)) {
      currentSessionId.value = null
      messages.value = []
    }
    return deleted
  }

  // REST send (non-streaming fallback)
  async function sendMessage(content: string, agentId?: number, workflowId?: number, ragEnabled?: boolean, webSearchEnabled?: boolean) {
    sending.value = true
    try {
      messages.value.push({
        id: Date.now(),
        sessionId: currentSessionId.value ?? 0,
        role: 'USER',
        content,
        metadata: null,
        createdAt: new Date().toISOString()
      })

      let res: { data: { data: ChatResponse } }

      if (currentSessionId.value) {
        res = await chatApi.sendMessage(currentSessionId.value, { message: content, model: selectedModel.value ?? undefined, ragEnabled, webSearchEnabled, ...memoryScopePayload.value })
      } else {
        const targetPayload = agentId || workflowId
          ? { agentId, workflowId }
          : resolveSelectedTargetPayload()
        res = await chatApi.sendNewMessage({
          message: content,
          ...targetPayload,
          model: selectedModel.value ?? undefined,
          ragEnabled,
          webSearchEnabled,
          ...memoryScopePayload.value
        })
      }

      const chatRes = res.data.data

      if (!currentSessionId.value) {
        currentSessionId.value = chatRes.sessionId
        await fetchSessions()
      }

      messages.value.push({
        id: chatRes.messageId,
        sessionId: chatRes.sessionId,
        role: 'ASSISTANT',
        content: chatRes.content,
        metadata: chatRes.metadata,
        createdAt: new Date().toISOString()
      })

      return chatRes
    } finally {
      sending.value = false
    }
  }

  // SSE streaming
  async function sendStreamingMessage(content: string, ragEnabled?: boolean, webSearchEnabled?: boolean) {
    sending.value = true
    streamingContent.value = ''
    streamingThinking.value = ''
    // 用户作答（或发新消息）：清掉上一轮 HUMAN_INPUT 待答状态（防 select 选项按钮答完后 stale 残留）
    pendingInput.value = null

    messages.value.push({
      id: Date.now(),
      sessionId: currentSessionId.value ?? 0,
      role: 'USER',
      content,
      metadata: null,
      createdAt: new Date().toISOString()
    })

    try {
      const fetchPromise = currentSessionId.value
        ? chatApi.streamMessage(currentSessionId.value, {
            message: content,
            model: selectedModel.value ?? undefined,
            ragEnabled,
            webSearchEnabled,
            ...memoryScopePayload.value
          })
        : chatApi.streamNewMessage({
            message: content,
            ...resolveSelectedTargetPayload(),
            model: selectedModel.value ?? undefined,
            ragEnabled,
            webSearchEnabled,
            ...memoryScopePayload.value
          })

      // 60s timeout for initial response（修 #1：原 10s 对 AGENT/工作流等非真流式首字节太短，频繁误超时→REST 回退双跑更慢）
      const response = await Promise.race([
        fetchPromise,
        new Promise<never>((_, reject) =>
          setTimeout(() => reject(new Error('SSE timeout')), 60000)
        )
      ])

      if (!response.ok || !response.body) {
        sending.value = false
        messages.value.pop()
        return sendMessage(content, undefined, undefined, ragEnabled, webSearchEnabled)
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let gotData = false

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('data:')) {
            const jsonStr = line.substring(5).trim()
            if (!jsonStr) continue
            try {
              const evt = JSON.parse(jsonStr)
              gotData = true
              // 修 #3：流式建新会话后回读 sessionId，避免每条消息新建会话（后端每个事件都带 sessionId）
              if (evt.sessionId) {
                currentSessionId.value = evt.sessionId
              }
              switch (evt.type) {
                case 'CHUNK':
                  streamingContent.value += evt.content || ''
                  break
                case 'THINKING':
                  streamingThinking.value += evt.content || ''
                  break
                case 'CITATION':
                  // P3：content 为 citations JSON 串（DONE 前到达，KB 与 web 各发一次）。
                  // 累积合并：多帧 CITATION（KB 引用 + 联网 web 引用）拼成一个数组，避免后者覆盖前者。
                  try {
                    const arr = evt.content ? JSON.parse(evt.content) : null
                    if (Array.isArray(arr) && arr.length) {
                      streamingCitations.value = [...(streamingCitations.value || []), ...arr]
                    }
                  } catch {
                    // 单帧解析失败不丢已有引用
                  }
                  break
                case 'DONE':
                  messages.value.push({
                    id: Date.now(),
                    sessionId: currentSessionId.value ?? 0,
                    role: 'ASSISTANT',
                    content: streamingContent.value,
                    metadata: JSON.stringify({
                      ...(streamingThinking.value ? { thinking: streamingThinking.value } : {}),
                      ...(pendingInput.value ? { pendingInput: pendingInput.value } : {}),
                      ...(streamingCitations.value ? { citations: streamingCitations.value } : {})
                    }),
                    createdAt: new Date().toISOString()
                  })
                  streamingContent.value = ''
                  streamingThinking.value = ''
                  streamingCitations.value = null
                  sending.value = false
                  await fetchSessions()
                  break
                case 'INPUT_REQUIRED':
                  pendingInput.value = evt
                  break
                case 'ERROR':
                  streamingContent.value = ''
                  streamingThinking.value = ''
                  sending.value = false
                  appendAssistantError(evt.content)
                  console.error('Stream error:', evt.content)
                  break
              }
            } catch {
              // Ignore malformed JSON
            }
          }
        }
      }
      // If stream ended with no data, fall back to REST
      if (!gotData) {
        sending.value = false
        streamingContent.value = ''
        streamingThinking.value = ''
        messages.value.pop()
        return sendMessage(content)
      }
    } catch (e) {
      console.warn('SSE failed, falling back to REST:', e)
      sending.value = false
      streamingContent.value = ''
      streamingThinking.value = ''
      messages.value.pop()
      return sendMessage(content)
    }
  }

  // WebSocket streaming
  function connectWS() {
    const token = getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN)
    if (!token) return
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const wsUrl = `${protocol}//${window.location.host}/ws/chat?token=${token}`

    ws = new WebSocket(wsUrl)

    ws.onopen = () => {
      wsConnected.value = true
    }

    ws.onclose = () => {
      wsConnected.value = false
      ws = null
    }

    ws.onerror = () => {
      wsConnected.value = false
    }

    ws.onmessage = (event) => {
      const data = JSON.parse(event.data)

      switch (data.type) {
        case 'CHUNK':
          streamingContent.value += data.content || ''
          break
        case 'CITATION':
          // P3：content 为 citations JSON 串（MESSAGE_COMPLETE 前到达，KB 与 web 各发一次），累积合并
          try {
            const arr = data.content ? JSON.parse(data.content) : null
            if (Array.isArray(arr) && arr.length) {
              streamingCitations.value = [...(streamingCitations.value || []), ...arr]
            }
          } catch {
            // 单帧解析失败不丢已有引用
          }
          break
        case 'MESSAGE_COMPLETE':
          // Finalize streaming message
          if (streamingContent.value) {
            messages.value.push({
              id: Date.now(),
              sessionId: currentSessionId.value ?? 0,
              role: 'ASSISTANT',
              content: streamingContent.value,
              metadata: JSON.stringify({
                ...(pendingInput.value ? { pendingInput: pendingInput.value } : {}),
                ...(streamingCitations.value ? { citations: streamingCitations.value } : {})
              }),
              createdAt: new Date().toISOString()
            })
          }
          streamingContent.value = ''
          streamingCitations.value = null
          sending.value = false
          fetchSessions()
          break
        case 'INPUT_REQUIRED':
          // 工作流命中 HUMAN_INPUT：捕获待答规格（问题已随 CHUNK 流出显示）。text 型直接正常回复即可被后端拦截恢复。
          pendingInput.value = data
          break
        case 'ERROR':
          streamingContent.value = ''
          sending.value = false
          appendAssistantError(data.message || data.content)
          break
      }
    }
  }

  function sendWSMessage(content: string, agentId?: number, workflowId?: number) {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      // Fallback to REST
      return sendMessage(content, agentId, workflowId)
    }

    sending.value = true
    streamingContent.value = ''
    // 用户作答（或发新消息）：清掉上一轮 HUMAN_INPUT 待答状态
    pendingInput.value = null

    // Add user message immediately
    messages.value.push({
      id: Date.now(),
      sessionId: currentSessionId.value ?? 0,
      role: 'USER',
      content,
      metadata: null,
      createdAt: new Date().toISOString()
    })

    ws.send(JSON.stringify({
      message: content,
      sessionId: currentSessionId.value,
      agentId,
      workflowId,
      model: selectedModel.value
    }))
  }

  function disconnectWS() {
    if (ws) {
      ws.close()
      ws = null
    }
  }

  function setSelectedModel(model: string | null) {
    selectedModel.value = model
    if (model) {
      setStorage(STORAGE_KEYS.CHAT_SELECTED_MODEL, model)
    }
  }

  function setSelectedTarget(target: string | null) {
    selectedTarget.value = target || DEFAULT_CHAT_TARGET
    setStorage(STORAGE_KEYS.CHAT_SELECTED_TARGET, selectedTarget.value)
  }

  async function updateCurrentSessionTarget(target: string | null) {
    const nextTarget = target || DEFAULT_CHAT_TARGET
    if (!currentSessionId.value) {
      setSelectedTarget(nextTarget)
      return
    }

    const payload = resolveTargetPayload(nextTarget)
    const res = await chatApi.updateSessionTarget(currentSessionId.value, payload)
    const updatedSession = res.data.data
    const index = sessions.value.findIndex(session => session.id === updatedSession.id)
    if (index >= 0) {
      sessions.value[index] = updatedSession
    } else {
      sessions.value.unshift(updatedSession)
    }
    setSelectedTarget(nextTarget)
  }

  // ---- 计划12 H'-4：legacy 记忆冲突/状态轮询已废 ----
  // 旧 /chat/memories/status|incident 端点随 MemoryController 删除（404）。新栈冲突走总结 worker →
  // MemoryConsolidationController 面板解决（MemoryNotificationBadge 3s 轮询 count 自带 UI），不在聊天栏轮询。
  // 保留这三个 ref + 三个函数签名（ChatView 模板/watcher 仍引用），恒为 0/null/空，无网络请求、无 404 刷屏。
  const activeConflictCount = ref(0)
  const memoryProcessing = ref(0)
  const memoryIncident = ref<string | null>(null)

  async function loadActiveConflicts() { /* no-op：旧端点已删 */ }
  function startConflictPoll() { /* no-op：旧端点已删 */ }
  function stopConflictPoll() { /* no-op：旧端点已删 */ }

  return {
    sessions,
    currentSessionId,
    currentSession,
    messages,
    loading,
    sending,
    streamingContent,
    streamingThinking,
    pendingInput,
    wsConnected,
    selectedModel,
    selectedTarget,
    visibleTargetValue,
    activeConflictCount,
    memoryProcessing,
    memoryIncident,
    setSelectedModel,
    setSelectedTarget,
    updateCurrentSessionTarget,
    resolveSelectedTargetPayload,
    fetchSessions,
    selectSession,
    deleteSession,
    batchDeleteSessions,
    sendMessage,
    sendWSMessage,
    sendStreamingMessage,
    connectWS,
    disconnectWS,
    startConflictPoll,
    stopConflictPoll,
    loadActiveConflicts,
    // 项目记忆 scope（V33）
    memProjectId,
    memIncludeGlobal,
    memReadProjectIds
  }
})
