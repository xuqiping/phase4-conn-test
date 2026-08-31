import request from './request'
import type { ApiResponse } from './request'

// 类型四分（V60 起，FR-002）：CHAT 对话 / EMBEDDING 向量 / VIDEO 视频 / IMAGE 生图（预留）
export type ProviderCategory = 'CHAT' | 'EMBEDDING' | 'VIDEO' | 'IMAGE' | 'RERANK'

/**
 * 协议取值（视频模型扩展 RG 扩宽）：CHAT/EMBEDDING/RERANK 用对话协议（OPENAI_COMPATIBLE/ANTHROPIC）；
 * VIDEO 用任务型协议 = 后端 MediaGenProvider.getId()（ark/minimax/dashscope，V163 起存量回填 ark，
 * worker 与测试按钮按此路由适配器）；IMAGE 无协议。
 */
export type ProviderProtocol = 'OPENAI_COMPATIBLE' | 'ANTHROPIC' | 'ark' | 'minimax' | 'dashscope'

export interface LlmProvider {
  id: number
  name: string
  displayName: string | null
  protocol: ProviderProtocol
  apiEndpoint: string | null
  models: string | null
  config: string | null
  status: string
  sortOrder: number
  category: ProviderCategory
  dim: number | null
  createdAt: string
  updatedAt: string
}

export interface LlmProviderCreateRequest {
  name: string
  displayName?: string
  protocol?: ProviderProtocol
  apiEndpoint: string
  apiKey?: string
  models?: string
  config?: string
  sortOrder?: number
  category?: ProviderCategory
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

/** 供应商导出/导入条目（10x-2）。导出文件含明文 API Key，仅 admin 可调。 */
export interface LlmProviderExportItem {
  name: string
  displayName?: string | null
  protocol?: ProviderProtocol | null
  apiEndpoint: string
  /** 明文 API Key（导出含明文；导入空值则保留原 key） */
  apiKey?: string | null
  models?: string | null
  config?: string | null
  sortOrder?: number | null
  category?: ProviderCategory | null
  status?: string | null
}

/** 批量导入结果统计（10x-2）。 */
export interface ProviderImportResult {
  created: number
  updated: number
  failed: number
  errors: string[]
}

export interface AvailableModel {
  modelId: string
  displayName: string
  providerName: string
  source: 'global' | 'user'
  defaultModel?: boolean
  /** 修复IX-1：思考档位集合（ANTHROPIC=三档全；OpenAI 系按 provider config 声明）。null/空=不支持，不显示选择器。 */
  thinkingLevels?: string[] | null
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

  // Embedding 专用测试（纯 embedding provider 不支持 chat，走 embed 取维度）
  testProviderEmbedding(id: number) {
    return request.post<ApiResponse<TestConnectionResult>>(`/llm/providers/${id}/test-embed`)
  },

  // RERANK 专用测试（真实 query + documents 调用，不回落到 chat）
  testProviderRerank(id: number) {
    return request.post<ApiResponse<TestConnectionResult>>(`/llm/providers/${id}/test-rerank`)
  },

  // VIDEO 专用测试（任务型协议不支持 chat，走媒体包零成本探测 GET 任务端点/不存在id）
  testProviderVideo(id: number) {
    return request.post<ApiResponse<TestConnectionResult>>(`/media/providers/${id}/test`)
  },

  // 导出全量供应商（10x-2；修复VIII B4/VIII-5：改 POST + 密码二次确认）：
  // 返回 blob 触发浏览器下载，含明文 API Key；密码走 body 绝不进 URL（nginx 日志面）
  exportProviders(password: string) {
    return request.post<Blob>('/llm/providers/export', { password }, { responseType: 'blob' })
  },

  // 批量导入供应商（10x-2）：按 name upsert，返回 created/updated/failed 统计
  importProviders(items: LlmProviderExportItem[]) {
    return request.post<ApiResponse<ProviderImportResult>>('/llm/providers/import', items)
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
  },

  listActiveModels(category: ProviderCategory) {
    return request.get<ApiResponse<string[]>>('/llm/models/active', { params: { category } })
  },

  // C5/D2：视频模型（仅 MEDIA 类 provider，如 Seedance）
  listVideoModels() {
    return request.get<ApiResponse<AvailableModel[]>>('/llm/user/models/video')
  }
}
