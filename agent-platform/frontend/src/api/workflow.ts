// ============================================================
// 工作流模块API
// 对应后端 /api/workflows 端点
// ============================================================

import request from './request'
import type { ApiResponse } from './request'
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
  }
}
