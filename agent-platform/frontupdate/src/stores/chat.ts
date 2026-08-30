import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { chatApi } from '@/api/chat'
import type { ChatSession, ChatMessage, ChatResponse, ChatAttachmentRef } from '@/api/chat'
import type { RecalledFileCard } from '@/api/memory'
import { getStorage, setStorage, removeStorage, STORAGE_KEYS } from '@/utils/storage'

const DEFAULT_CHAT_TARGET = 'none'

/** 9x-1（V160 D4）：DONE.data 为网关 USAGE 精确值（promptTokens/completionTokens/points[/cachedTokens]）。
 *  无 token 字段（纯 DONE / 确认式回复流）→ null，不写 metadata.usage。 */
function usageFromDone(evt: { data?: Record<string, unknown> }): Record<string, unknown> | null {
  const d = evt?.data
  if (d && typeof d === 'object' && ('promptTokens' in d || 'completionTokens' in d)) {
    return d
  }
  return null
}

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
  /** 二期 P3（FR-203）：召回命中的文件卡片（FILE_CARDS 帧捕获；DONE 前到达），DONE 时并入 message.metadata.fileCards 供文件卡片渲染。 */
  const streamingFileCards = ref<RecalledFileCard[] | null>(null)
  /** 工作流 HUMAN_INPUT 待答问题规格（INPUT_REQUIRED 帧捕获；select 型可渲染选项按钮）。text 型直接用普通输入框作答即可。 */
  const pendingInput = ref<Record<string, any> | null>(null)
  /** 5x #7：收录确认结构化载荷（INCLUSION_CONFIRM 帧捕获：messageId/status/hits；DONE 时并入消息 metadata，MessageBubble 渲染点选按钮）。 */
  const pendingInclusionConfirm = ref<Record<string, any> | null>(null)
  const wsConnected = ref(false)
  const selectedModel = ref<string | null>(
    getStorage<string>(STORAGE_KEYS.CHAT_SELECTED_MODEL) || null
  )
  const selectedTarget = ref<string>(
    getStorage<string>(STORAGE_KEYS.CHAT_SELECTED_TARGET) || DEFAULT_CHAT_TARGET
  )
  let ws: WebSocket | null = null

  // 5x 四轮 U6：当前流式请求的 abort 控制器（停止生成）；stopping 标记区分「用户停止」与「真失败」（后者才走 REST 回退）
  let streamAbortController: AbortController | null = null
  const stoppingStream = ref(false)

  // ============================================================
  // 9x#11 聊天队列：生成中再发消息 → 排队，当前轮结束后 FIFO 自动续发。
  // 此前 UI 仅靠 sending 禁用输入（store 无闸门），并发两条会互踩 streamAbortController/streamingContent。
  // ============================================================
  interface QueuedChatMessage {
    content: string
    ragEnabled?: boolean
    webSearchEnabled?: boolean
    attachments?: ChatAttachmentRef[]
    kbIds?: number[]
    projectGroupId?: number
  }
  const messageQueue = ref<QueuedChatMessage[]>([])

  function clearMessageQueue() {
    messageQueue.value = []
  }

  /** 一轮结束（DONE/ERROR/停止/REST 收尾）后自动续发下一条；300ms 缓冲等服务端落库/状态收敛。 */
  function scheduleQueueDrain() {
    window.setTimeout(() => {
      if (sending.value || !messageQueue.value.length) return
      const next = messageQueue.value.shift()!
      void sendStreamingMessage(next.content, next.ragEnabled, next.webSearchEnabled, next.attachments, next.kbIds, next.projectGroupId)
    }, 300)
  }

  /**
   * 停止生成：abort 当前 SSE → reader.read() 抛 AbortError → sendStreamingMessage 的 abort 分支
   * 收尾（本地保留已生成部分 + 不走 REST 回退防双倍计费；服务端 doOnCancel 同步落库部分内容）。
   */
  function stopStreaming() {
    if (!streamAbortController || !sending.value) return
    stoppingStream.value = true
    streamAbortController.abort()
  }

  /** 停止后的本地收尾：已生成部分转为带 stopped 标的消息（服务端也落库一份，刷新后以服务端为准）。 */
  function finalizeStoppedMessage() {
    const partial = streamingContent.value
    const think = streamingThinking.value
    if (partial) {
      appendAssistantMessage(partial, JSON.stringify({
        ...(think ? { thinking: think } : {}),
        stopped: true
      }))
    }
    streamingContent.value = ''
    streamingThinking.value = ''
    streamingCitations.value = null
    streamingFileCards.value = null
    pendingInclusionConfirm.value = null
    sending.value = false
    scheduleQueueDrain()
  }

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
    // 9x#11：切换/新建会话清空待发队列——队列项绑定当时会话上下文，切会话后续发会串会话
    clearMessageQueue()
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

  /** 二期 P3：本地用户回显 metadata（附件引用 chips；无附件 → null）。 */
  function attachmentEchoMetadata(attachments?: ChatAttachmentRef[]): string | null {
    return attachments?.length ? JSON.stringify({ attachments }) : null
  }

  // REST send (non-streaming fallback)
  async function sendMessage(content: string, agentId?: number, workflowId?: number, ragEnabled?: boolean, webSearchEnabled?: boolean, attachments?: ChatAttachmentRef[], kbIds?: number[], projectGroupId?: number) {
    const attachmentFileIds = attachments?.map(a => a.fileId)
    sending.value = true
    try {
      messages.value.push({
        id: Date.now(),
        sessionId: currentSessionId.value ?? 0,
        role: 'USER',
        content,
        metadata: attachmentEchoMetadata(attachments),
        createdAt: new Date().toISOString()
      })

      let res: { data: { data: ChatResponse } }

      if (currentSessionId.value) {
        res = await chatApi.sendMessage(currentSessionId.value, { message: content, model: selectedModel.value ?? undefined, ragEnabled, webSearchEnabled, attachmentFileIds, kbIds, projectGroupId })
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
          attachmentFileIds,
          kbIds,
          projectGroupId
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
      scheduleQueueDrain()
    }
  }

  // SSE streaming
  async function sendStreamingMessage(content: string, ragEnabled?: boolean, webSearchEnabled?: boolean, attachments?: ChatAttachmentRef[], kbIds?: number[], projectGroupId?: number) {
    // 9x#11：生成中 → 入队尾，本轮 DONE/ERROR/停止后自动续发（原无闸门：互踩 streamAbortController/缓冲）
    if (sending.value) {
      messageQueue.value.push({ content, ragEnabled, webSearchEnabled, attachments, kbIds, projectGroupId })
      return
    }
    const attachmentFileIds = attachments?.map(a => a.fileId)
    sending.value = true
    streamingContent.value = ''
    streamingThinking.value = ''
    streamingFileCards.value = null
    // 用户作答（或发新消息）：清掉上一轮 HUMAN_INPUT 待答状态（防 select 选项按钮答完后 stale 残留）
    pendingInput.value = null
    pendingInclusionConfirm.value = null

    messages.value.push({
      id: Date.now(),
      sessionId: currentSessionId.value ?? 0,
      role: 'USER',
      content,
      metadata: attachmentEchoMetadata(attachments),
      createdAt: new Date().toISOString()
    })

    try {
      streamAbortController = new AbortController()
      const fetchPromise = currentSessionId.value
        ? chatApi.streamMessage(currentSessionId.value, {
            message: content,
            model: selectedModel.value ?? undefined,
            ragEnabled,
            webSearchEnabled,
            attachmentFileIds,
            kbIds,
            projectGroupId
          }, streamAbortController.signal)
        : chatApi.streamNewMessage({
            message: content,
            ...resolveSelectedTargetPayload(),
            model: selectedModel.value ?? undefined,
            ragEnabled,
            webSearchEnabled,
            attachmentFileIds,
            kbIds,
            projectGroupId
          }, streamAbortController.signal)

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
        return sendMessage(content, undefined, undefined, ragEnabled, webSearchEnabled, attachments, kbIds, projectGroupId)
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
                case 'FILE_CARDS':
                  // 二期 P3（FR-203）：content 为 RecalledFileCard[] JSON 串（DONE 前到达，单帧）。
                  try {
                    const cards = evt.content ? JSON.parse(evt.content) : null
                    if (Array.isArray(cards) && cards.length) {
                      streamingFileCards.value = cards
                    }
                  } catch {
                    // 解析失败不丢流内容
                  }
                  break
                case 'DONE':
                  messages.value.push({
                    // 5x #7：确认式回复用真实 messageId（服务端 INCLUSION_CONFIRM 帧携带），点选接口按 id 回查
                    id: pendingInclusionConfirm.value?.messageId ?? Date.now(),
                    sessionId: currentSessionId.value ?? 0,
                    role: 'ASSISTANT',
                    content: streamingContent.value,
                    metadata: JSON.stringify({
                      ...(streamingThinking.value ? { thinking: streamingThinking.value } : {}),
                      ...(pendingInput.value ? { pendingInput: pendingInput.value } : {}),
                      ...(pendingInclusionConfirm.value ? { inclusionConfirm: pendingInclusionConfirm.value } : {}),
                      ...(streamingCitations.value ? { citations: streamingCitations.value } : {}),
                      ...(streamingFileCards.value ? { fileCards: streamingFileCards.value } : {}),
                      // 9x-1（V160 D4）：DONE.data 即网关 USAGE 精确值（promptTokens/completionTokens/points/cachedTokens?）
                      ...(usageFromDone(evt) ? { usage: usageFromDone(evt) } : {})
                    }),
                    createdAt: new Date().toISOString()
                  })
                  streamingContent.value = ''
                  streamingThinking.value = ''
                  streamingCitations.value = null
                  streamingFileCards.value = null
                  pendingInclusionConfirm.value = null
                  sending.value = false
                  await fetchSessions()
                  scheduleQueueDrain()
                  break
                case 'INPUT_REQUIRED':
                  pendingInput.value = evt
                  break
                case 'INCLUSION_CONFIRM':
                  // 5x #7：content 为 {messageId,status,hits} JSON（DONE 前到达）；DONE 时随消息落 metadata
                  try {
                    pendingInclusionConfirm.value = evt.content ? JSON.parse(evt.content) : evt
                  } catch {
                    pendingInclusionConfirm.value = evt
                  }
                  break
                case 'ERROR':
                  streamingContent.value = ''
                  streamingThinking.value = ''
                  sending.value = false
                  // 9x#11：后端并发闸拒答（上一轮收尾未完成的竞态）→ 队首重排等续发，不当错误展示
                  if (typeof evt.content === 'string' && evt.content.includes('生成中，请稍候')) {
                    messages.value.pop()
                    messageQueue.value.unshift({ content, ragEnabled, webSearchEnabled, attachments, kbIds, projectGroupId })
                  } else {
                    appendAssistantError(evt.content)
                  }
                  console.error('Stream error:', evt.content)
                  scheduleQueueDrain()
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
        streamingFileCards.value = null
        messages.value.pop()
        streamAbortController?.abort()
        return sendMessage(content, undefined, undefined, ragEnabled, webSearchEnabled, attachments)
      }
    } catch (e) {
      // U6：用户停止（abort 使 read 抛 AbortError）→ 保留部分内容收尾，严禁走 REST 回退（双倍计费）
      if (stoppingStream.value) {
        finalizeStoppedMessage()
        return
      }
      console.warn('SSE failed, falling back to REST:', e)
      sending.value = false
      streamingContent.value = ''
      streamingThinking.value = ''
      streamingFileCards.value = null
      messages.value.pop()
      streamAbortController?.abort()
      return sendMessage(content, undefined, undefined, ragEnabled, webSearchEnabled, attachments)
    } finally {
      streamAbortController = null
      stoppingStream.value = false
    }
  }

  /** 本地把确认消息状态置 ANSWERED/DECLINED（按钮消失防重复点；服务端同样按 metadata 状态幂等拒绝）。 */
  function markInclusionResolved(messageId: number, status: string) {
    const idx = messages.value.findIndex(m => m.id === messageId)
    if (idx < 0) return
    try {
      const meta = messages.value[idx].metadata ? JSON.parse(messages.value[idx].metadata!) : {}
      meta.inclusionConfirm = { ...(meta.inclusionConfirm || {}), status }
      messages.value[idx].metadata = JSON.stringify(meta)
    } catch {
      // metadata 坏 → 忽略（MessageBubble 解析失败也不渲染按钮）
    }
  }

  /**
   * 5x #7 收录确认点选（SSE 流）：ANSWER→携服务端存档原文全量回答流；DECLINE→收尾消息流。
   * 不插 USER 消息（首轮已落库）；点选即本地置状态防重复点。
   */
  async function confirmInclusion(messageId: number, choice: 'ANSWER' | 'DECLINE') {
    if (!currentSessionId.value || sending.value) return
    sending.value = true
    streamingContent.value = ''
    streamingThinking.value = ''
    streamingCitations.value = null
    streamingFileCards.value = null
    pendingInput.value = null
    markInclusionResolved(messageId, choice === 'ANSWER' ? 'ANSWERED' : 'DECLINED')
    try {
      const response = await chatApi.confirmInclusionStream(currentSessionId.value, messageId, choice)
      if (!response.ok || !response.body) {
        sending.value = false
        appendAssistantError('确认失败，请稍后重试。')
        return
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''
        for (const line of lines) {
          if (!line.startsWith('data:')) continue
          const jsonStr = line.substring(5).trim()
          if (!jsonStr) continue
          try {
            const evt = JSON.parse(jsonStr)
            switch (evt.type) {
              case 'CHUNK':
                streamingContent.value += evt.content || ''
                break
              case 'THINKING':
                streamingThinking.value += evt.content || ''
                break
              case 'CITATION':
                try {
                  const arr = evt.content ? JSON.parse(evt.content) : null
                  if (Array.isArray(arr) && arr.length) {
                    streamingCitations.value = [...(streamingCitations.value || []), ...arr]
                  }
                } catch {
                  // 单帧解析失败不丢已有引用
                }
                break
              case 'FILE_CARDS':
                try {
                  const cards = evt.content ? JSON.parse(evt.content) : null
                  if (Array.isArray(cards) && cards.length) {
                    streamingFileCards.value = cards
                  }
                } catch {
                  // 解析失败不丢流内容
                }
                break
              case 'DONE':
                if (streamingContent.value) {
                  messages.value.push({
                    id: Date.now(),
                    sessionId: currentSessionId.value ?? 0,
                    role: 'ASSISTANT',
                    content: streamingContent.value,
                    metadata: JSON.stringify({
                      ...(streamingThinking.value ? { thinking: streamingThinking.value } : {}),
                      ...(streamingCitations.value ? { citations: streamingCitations.value } : {}),
                      ...(streamingFileCards.value ? { fileCards: streamingFileCards.value } : {})
                    }),
                    createdAt: new Date().toISOString()
                  })
                }
                streamingContent.value = ''
                streamingThinking.value = ''
                streamingCitations.value = null
                streamingFileCards.value = null
                sending.value = false
                break
              case 'ERROR':
                streamingContent.value = ''
                streamingThinking.value = ''
                sending.value = false
                appendAssistantError(evt.content)
                break
            }
          } catch {
            // Ignore malformed JSON
          }
        }
      }
      sending.value = false
      scheduleQueueDrain()
    } catch {
      streamingContent.value = ''
      streamingThinking.value = ''
      sending.value = false
      appendAssistantError('确认失败，请稍后重试。')
      scheduleQueueDrain()
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
        case 'FILE_CARDS':
          // 二期 P3（FR-203）：文件记忆卡片帧（MESSAGE_COMPLETE 前到达，单帧）
          try {
            const cards = data.content ? JSON.parse(data.content) : null
            if (Array.isArray(cards) && cards.length) {
              streamingFileCards.value = cards
            }
          } catch {
            // 解析失败不丢流内容
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
                ...(streamingCitations.value ? { citations: streamingCitations.value } : {}),
                ...(streamingFileCards.value ? { fileCards: streamingFileCards.value } : {})
              }),
              createdAt: new Date().toISOString()
            })
          }
          streamingContent.value = ''
          streamingCitations.value = null
          streamingFileCards.value = null
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

  function sendWSMessage(content: string, agentId?: number, workflowId?: number, attachments?: ChatAttachmentRef[]) {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      // Fallback to REST
      return sendMessage(content, agentId, workflowId, undefined, undefined, attachments)
    }

    sending.value = true
    streamingContent.value = ''
    streamingFileCards.value = null
    // 用户作答（或发新消息）：清掉上一轮 HUMAN_INPUT 待答状态
    pendingInput.value = null

    // Add user message immediately
    messages.value.push({
      id: Date.now(),
      sessionId: currentSessionId.value ?? 0,
      role: 'USER',
      content,
      metadata: attachmentEchoMetadata(attachments),
      createdAt: new Date().toISOString()
    })

    ws.send(JSON.stringify({
      message: content,
      sessionId: currentSessionId.value,
      agentId,
      workflowId,
      model: selectedModel.value,
      attachmentFileIds: attachments?.map(a => a.fileId)
    }))
  }

  function disconnectWS() {
    if (ws) {
      ws.close()
      ws = null
    }
  }

  function setSelectedModel(model: string | null) {
    selectedModel.value = model || null
    if (model) setStorage(STORAGE_KEYS.CHAT_SELECTED_MODEL, model)
    else removeStorage(STORAGE_KEYS.CHAT_SELECTED_MODEL)
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
    stopStreaming,
    messageQueue,
    clearMessageQueue,
    confirmInclusion,
    connectWS,
    disconnectWS,
    startConflictPoll,
    stopConflictPoll,
    loadActiveConflicts
  }
})
