import request from './request'
import type { ApiResponse } from './request'

export interface LlmProvider {
  id: number
  name: string
  displayName: string | null
  apiEndpoint: string | null
  models: string | null
  config: string | null
  status: string
  sortOrder: number
  createdAt: string
  updatedAt: string
}

export interface LlmProviderCreateRequest {
  name: string
  displayName?: string
  apiEndpoint: string
  apiKey?: string
  models?: string
  config?: string
  sortOrder?: number
}

export interface UserLlmProvider {
  id: number
  providerName: string
  apiEndpoint: string | null
  hasApiKey: boolean
  models: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export interface UserLlmProviderRequest {
  providerName: string
  apiEndpoint?: string
  apiKey?: string
  models?: string
}

export interface TestConnectionResult {
  success: boolean
  message: string
  model: string | null
  durationMs: number | null
}

export interface AvailableModel {
  modelId: string
  displayName: string
  providerName: string
  source: 'global' | 'user'
}

export const llmApi = {
  // Admin: Global providers
  listProviders() {
    return request.get<ApiResponse<LlmProvider[]>>('/llm/providers')
  },

  createProvider(data: LlmProviderCreateRequest) {
    return request.post<ApiResponse<LlmProvider>>('/llm/providers', data)
  },

  updateProvider(id: number, data: LlmProviderCreateRequest) {
    return request.put<ApiResponse<LlmProvider>>(`/llm/providers/${id}`, data)
  },

  deleteProvider(id: number) {
    return request.delete<ApiResponse<void>>(`/llm/providers/${id}`)
  },

  reloadProviders() {
    return request.post<ApiResponse<void>>('/llm/providers/reload')
  },

  testProviderConnection(id: number) {
    return request.post<ApiResponse<TestConnectionResult>>(`/llm/providers/${id}/test`)
  },

  // User: Own providers
  listUserProviders() {
    return request.get<ApiResponse<UserLlmProvider[]>>('/llm/user/providers')
  },

  createUserProvider(data: UserLlmProviderRequest) {
    return request.post<ApiResponse<UserLlmProvider>>('/llm/user/providers', data)
  },

  deleteUserProvider(id: number) {
    return request.delete<ApiResponse<void>>(`/llm/user/providers/${id}`)
  },

  testUserProviderConnection(id: number) {
    return request.post<ApiResponse<TestConnectionResult>>(`/llm/user/providers/${id}/test`)
  },

  // Available models
  listAvailableModels() {
    return request.get<ApiResponse<AvailableModel[]>>('/llm/user/models/available')
  }
}
