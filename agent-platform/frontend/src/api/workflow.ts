// ============================================================
// 工作流模块API
// 对应后端 /api/workflows 端点
// ============================================================

import request from './request'
import type { ApiResponse } from './request'
import { getStorage, STORAGE_KEYS } from '@/utils/storage'
import type {
  ExecutionEvent,
} from '@/api/execution'
import type {
  Workflow,
  WorkflowListItem,
  CreateWorkflowRequest,
  UpdateWorkflowRequest
} from '@/types/workflow'

export const workflowApi = {
  /**
   * 获取工作流列表
   * GET /api/workflows
   */
  list() {
    return request.get<ApiResponse<WorkflowListItem[]>>('/workflows')
  },

  /**
   * 创建工作流
   * POST /api/workflows
   */
  create(data: CreateWorkflowRequest) {
    return request.post<ApiResponse<Workflow>>('/workflows', data)
  },

  /**
   * 获取工作流详情（含nodes+edges）
   * GET /api/workflows/{id}
   */
  getDetail(id: number) {
    return request.get<ApiResponse<Workflow>>(`/workflows/${id}`)
  },

  /**
   * 更新工作流
   * PUT /api/workflows/{id}
   */
  update(id: number, data: UpdateWorkflowRequest) {
    return request.put<ApiResponse<Workflow>>(`/workflows/${id}`, data)
  },

  /**
   * 删除工作流
   * DELETE /api/workflows/{id}
   */
  remove(id: number) {
    return request.delete<ApiResponse<void>>(`/workflows/${id}`)
  },

  /**
   * 复制工作流
   * POST /api/workflows/{id}/duplicate
   */
  duplicate(id: number) {
    return request.post<ApiResponse<Workflow>>(`/workflows/${id}/duplicate`)
  },

  run(id: number, input: Record<string, unknown> = {}) {
    return request.post<ApiResponse<ExecutionEvent[]>>(`/workflows/${id}/run`, input, { timeout: 120000 })
  },

  async *runStream(
    id: number,
    input: Record<string, unknown> = {},
    signal?: AbortSignal
  ): AsyncGenerator<ExecutionEvent> {
    const token = getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN) || ''
    const response = await fetch(`/api/workflows/${id}/run/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
        'Authorization': `Bearer ${token}`
      },
      signal,
      body: JSON.stringify(input)
    })
    if (!response.ok || !response.body) {
      throw new Error(`工作流实时运行请求失败: ${response.status}`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    try {
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const parts = buffer.split(/\r?\n\r?\n/)
        buffer = parts.pop() || ''
        for (const part of parts) {
          const event = parseSseEvent(part)
          if (event) {
            yield event
          }
        }
      }
      buffer += decoder.decode()
      const event = parseSseEvent(buffer)
      if (event) {
        yield event
      }
    } finally {
      reader.releaseLock()
    }
  },

  /**
   * 导出工作流JSON
   * GET /api/workflows/{id}/export
   */
  exportJson(id: number) {
    return request.get<ApiResponse<Workflow>>(`/workflows/${id}/export`)
  },

  /**
   * 导入工作流JSON
   * POST /api/workflows/import
   */
  importJson(data: Workflow) {
    return request.post<ApiResponse<Workflow>>('/workflows/import', data)
  },

  /**
   * 设置工作流记忆模式开关
   * PUT /api/workflows/{id}/rag-enabled，body key 为 "enabled"（true/false）
   */
  setRagEnabled(id: number, enabled: boolean) {
    return request.put<ApiResponse<void>>(`/workflows/${id}/rag-enabled`, { enabled })
  }
}

function parseSseEvent(raw: string): ExecutionEvent | null {
  const dataLines = raw
    .split(/\r?\n/)
    .filter(line => line.startsWith('data:'))
    .map(line => line.slice(5).trimStart())
  if (dataLines.length === 0) return null
  const payload = dataLines.join('\n')
  if (!payload || payload === '[DONE]') return null
  return JSON.parse(payload) as ExecutionEvent
}
