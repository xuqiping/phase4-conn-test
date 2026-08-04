// ============================================================
// 无限画布模块 API（LibTV 式创作页）
// 对应后端 /api/canvas/**
//   POST   /api/canvas            → canvas:write（新建画布，返 CanvasVO）
//   GET    /api/canvas            → canvas:write（我的画布列表，摘要不含 snapshot）
//   GET    /api/canvas/{id}       → canvas:write（详情含 snapshot）
//   PUT    /api/canvas/{id}       → canvas:write（全量保存 name+snapshot）
//   PATCH  /api/canvas/{id}/rename→ canvas:write（仅重命名）
//   DELETE /api/canvas/{id}       → canvas:write（软删，不级联清产出物）
// ============================================================

import request from './request'
import type { ApiResponse } from './request'

// === 类型定义（对齐后端 CanvasVO / CanvasSaveRequest） ===

/** 画布视图。列表接口 snapshot=null，详情接口才带。 */
export interface CanvasVO {
  id: number
  name: string
  /** 画布结构 JSON 字符串（{nodes,edges,viewport}）；列表为 null。 */
  snapshot: string | null
  /** 节点数（后端从 snapshot.nodes 派生）。 */
  nodeCount: number | null
  createdAt: string
  updatedAt: string | null
}

/** 新建请求。name 可空（后端默认「未命名画布」）。 */
export interface CanvasCreateRequest {
  name?: string
}

/** 全量保存请求。 */
export interface CanvasSaveRequest {
  name: string
  /** 画布结构 JSON 字符串，空画布可省略（后端兜底 "{}"）。最长 5MB。 */
  snapshot?: string
}

// === API 函数 ===

export const canvasApi = {
  /** POST /api/canvas — 新建画布 */
  create(data?: CanvasCreateRequest) {
    return request.post<ApiResponse<CanvasVO>>('/canvas', data ?? {})
  },

  /** GET /api/canvas — 我的画布列表（ownership 过滤；admin 看全量） */
  list() {
    return request.get<ApiResponse<CanvasVO[]>>('/canvas')
  },

  /** GET /api/canvas/{id} — 画布详情（含 snapshot） */
  get(id: number) {
    return request.get<ApiResponse<CanvasVO>>(`/canvas/${id}`)
  },

  /** PUT /api/canvas/{id} — 全量保存（name + snapshot） */
  save(id: number, data: CanvasSaveRequest) {
    return request.put<ApiResponse<CanvasVO>>(`/canvas/${id}`, data)
  },

  /** PATCH /api/canvas/{id}/rename — 仅重命名 */
  rename(id: number, name: string) {
    return request.patch<ApiResponse<CanvasVO>>(`/canvas/${id}/rename`, { name })
  },

  /** DELETE /api/canvas/{id} — 软删 */
  remove(id: number) {
    return request.delete<ApiResponse<void>>(`/canvas/${id}`)
  }
}
