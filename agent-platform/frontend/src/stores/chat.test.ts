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
    streamMessage: vi.fn(),
    streamNewMessage: vi.fn()
  }
}))

vi.mock('@/utils/storage', () => ({
  STORAGE_KEYS: { ACCESS_TOKEN: 'accessToken' },
  getStorage: vi.fn(() => 'token')
}))

import { chatApi } from '@/api/chat'

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
})
