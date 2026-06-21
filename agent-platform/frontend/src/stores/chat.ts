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
  const wsConnected = ref(false)
  const selectedModel = ref<string | null>(
    getStorage<string>(STORAGE_KEYS.CHAT_SELECTED_MODEL) || DEFAULT_CHAT_MODEL
  )
  const selectedTarget = ref<string>(
    getStorage<string>(STORAGE_KEYS.CHAT_SELECTED_TARGET) || DEFAULT_CHAT_TARGET
  )

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

  // REST send (non-streaming fallback)
  async function sendMessage(content: string, agentId?: number, workflowId?: number, ragEnabled?: boolean) {
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
        res = await chatApi.sendMessage(currentSessionId.value, { message: content, model: selectedModel.value ?? undefined, ragEnabled })
      } else {
        const targetPayload = agentId || workflowId
          ? { agentId, workflowId }
          : resolveSelectedTargetPayload()
        res = await chatApi.sendNewMessage({
          message: content,
          ...targetPayload,
          model: selectedModel.value ?? undefined,
          ragEnabled
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
  async function sendStreamingMessage(content: string, ragEnabled?: boolean) {
    sending.value = true
    streamingContent.value = ''
    streamingThinking.value = ''

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
            ragEnabled
          })
        : chatApi.streamNewMessage({
            message: content,
            ...resolveSelectedTargetPayload(),
            model: selectedModel.value ?? undefined,
            ragEnabled
          })

      // 10s timeout for initial response
      const response = await Promise.race([
        fetchPromise,
        new Promise<never>((_, reject) =>
          setTimeout(() => reject(new Error('SSE timeout')), 10000)
        )
      ])

      if (!response.ok || !response.body) {
        sending.value = false
        messages.value.pop()
        return sendMessage(content, undefined, undefined, ragEnabled)
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
              switch (evt.type) {
                case 'CHUNK':
                  streamingContent.value += evt.content || ''
                  break
                case 'THINKING':
                  streamingThinking.value += evt.content || ''
                  break
                case 'DONE':
                  messages.value.push({
                    id: Date.now(),
                    sessionId: currentSessionId.value ?? 0,
                    role: 'ASSISTANT',
                    content: streamingContent.value,
                    metadata: streamingThinking.value
                      ? JSON.stringify({ thinking: streamingThinking.value })
                      : null,
                    createdAt: new Date().toISOString()
                  })
                  streamingContent.value = ''
                  streamingThinking.value = ''
                  sending.value = false
                  await fetchSessions()
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
        case 'MESSAGE_COMPLETE':
          // Finalize streaming message
          if (streamingContent.value) {
            messages.value.push({
              id: Date.now(),
              sessionId: currentSessionId.value ?? 0,
              role: 'ASSISTANT',
              content: streamingContent.value,
              metadata: null,
              createdAt: new Date().toISOString()
            })
          }
          streamingContent.value = ''
          sending.value = false
          fetchSessions()
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

  return {
    sessions,
    currentSessionId,
    currentSession,
    messages,
    loading,
    sending,
    streamingContent,
    streamingThinking,
    wsConnected,
    selectedModel,
    selectedTarget,
    visibleTargetValue,
    setSelectedModel,
    setSelectedTarget,
    updateCurrentSessionTarget,
    resolveSelectedTargetPayload,
    fetchSessions,
    selectSession,
    deleteSession,
    sendMessage,
    sendWSMessage,
    sendStreamingMessage,
    connectWS,
    disconnectWS
  }
})
