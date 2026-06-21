import request from './request'
import type { ApiResponse } from './request'

export interface AuthSettings {
  accessTokenExpirationMs: number
}

export interface RagMemorySettings {
  enabled: boolean
}

export const systemApi = {
  getAuthSettings() {
    return request.get<ApiResponse<AuthSettings>>('/system/settings/auth')
  },

  updateAuthSettings(data: AuthSettings) {
    return request.put<ApiResponse<AuthSettings>>('/system/settings/auth', data)
  },

  // RAG/记忆模式全局开关（V26）
  getRagMemorySettings() {
    return request.get<ApiResponse<RagMemorySettings>>('/system/settings/rag-memory')
  },

  updateRagMemorySettings(data: RagMemorySettings) {
    return request.put<ApiResponse<RagMemorySettings>>('/system/settings/rag-memory', data)
  }
}
