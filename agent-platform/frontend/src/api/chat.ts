import request from './request'
import type { ApiResponse } from './request'
import { getStorage, STORAGE_KEYS } from '@/utils/storage'

export interface ChatSession {
  id: number
  title: string | null
  agentId: number | null
  agentName: string | null
  workflowId: number | null
  workflowName: string | null
  mode: string
  status: string
  createdAt: string
  updatedAt: string
}

export interface ChatMessage {
  id: number
  sessionId: number
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  content: string
  metadata: string | null
  createdAt: string
}

export interface ChatResponse {
  sessionId: number
  messageId: number
  content: string
  mode: string
  metadata: string | null
}

export const chatApi = {
  createSession(data: { message: string; agentId?: number; workflowId?: number }) {
    return request.post<ApiResponse<ChatSession>>('/chat/sessions', data)
  },

  listSessions() {
    return request.get<ApiResponse<ChatSession[]>>('/chat/sessions')
  },

  getSession(id: number) {
    return request.get<ApiResponse<ChatSession>>(`/chat/sessions/${id}`)
  },

  deleteSession(id: number) {
    return request.delete<ApiResponse<void>>(`/chat/sessions/${id}`)
  },

  getMessages(sessionId: number) {
    return request.get<ApiResponse<ChatMessage[]>>(`/chat/sessions/${sessionId}/messages`)
  },

  sendMessage(sessionId: number, data: { message: string; model?: string }) {
    return request.post<ApiResponse<ChatResponse>>(`/chat/sessions/${sessionId}/messages`, data)
  },

  sendNewMessage(data: { message: string; agentId?: number; workflowId?: number; model?: string }) {
    return request.post<ApiResponse<ChatResponse>>('/chat/messages', data)
  },

  // Streaming (SSE)
  streamMessage(sessionId: number, data: { message: string; model?: string }) {
    const token = getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN) || ''
    return fetch(`/api/chat/sessions/${sessionId}/messages/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify(data)
    })
  },

  streamNewMessage(data: { message: string; model?: string }) {
    const token = getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN) || ''
    return fetch('/api/chat/messages/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify(data)
    })
  }
}
