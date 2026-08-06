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

// === C4：节点运行 + 产出物上传（对齐 CanvasNodeDTO / NodeRunResult / StoredFile） ===

/** 画布节点 DTO（对齐后端 CanvasNodeDTO；run 端点请求体）。 */
export interface CanvasNodeDTO {
  id: string
  type: 'text' | 'image' | 'video' | 'audio' | 'script'
  positionX?: number
  positionY?: number
  data: Record<string, unknown>
}

/** 节点运行结果（对齐后端 NodeRunResult；前端把 dataPatch 合并进 node.data）。 */
export interface NodeRunResult {
  nodeId: string
  status: 'idle' | 'running' | 'success' | 'failed'
  dataPatch?: Record<string, unknown>
  outputs?: Array<{ nodeId: string; nodeType: string; fileId: string; outputKind: string }>
  errorMsg?: string | null
}

/** 产出物上传回包（对齐后端 StoredFile record）。 */
export interface CanvasStoredFile {
  fileId: string
  url: string
  name: string
  mimeType: string
  size: number
}

// === C11：视频抽帧（对齐后端 FrameExtractRequest / FrameExtractVO） ===

/** 抽帧模式（FIRST 首帧 / LAST 尾帧 / AT 指定秒）。 */
export type FrameMode = 'FIRST' | 'LAST' | 'AT'

/** 抽帧响应（对齐后端 FrameExtractVO）。 */
export interface FrameExtractVO {
  fileId: string
  url: string
  mime: string
  size: number
  /** 源视频节点 id（前端建图节点后自动连边 video→image）。 */
  sourceNodeId: string
}

// === C12：视频截取（对齐后端 VideoClipRequest / VideoClipVO） ===

/** 截取响应（对齐后端 VideoClipVO）。 */
export interface VideoClipVO {
  fileId: string
  url: string
  mime: string
  size: number
  /** 源视频节点 id（前端建视频节点后自动连边 video→video）。 */
  sourceNodeId: string
}

// === C13：故事板拼接（对齐后端 StoryboardConcatRequest / StoryboardConcatVO） ===

/** 拼接响应（对齐后端 StoryboardConcatVO）。 */
export interface StoryboardConcatVO {
  fileId: string
  url: string
  mime: string
  size: number
  /** 拼接段数。 */
  segmentCount: number
  /** 成片总时长（秒）。 */
  totalDurationSec: number
  /** 参与拼接的源视频 fileId 列表（前端建成片节点后可批量连边，可选）。 */
  sourceNodeIds: string[]
}

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
  },

  /** POST /api/canvas/{id}/nodes/run — 运行单节点（无状态，回 dataPatch 前端合并） */
  runNode(id: number, node: CanvasNodeDTO) {
    return request.post<ApiResponse<NodeRunResult>>(`/canvas/${id}/nodes/run`, node)
  },

  /** POST /api/canvas/{id}/upload — 产出物上传（图片/音频/视频参考图通用，落 SOURCE_CANVAS） */
  upload(id: number, file: File) {
    const form = new FormData()
    form.append('file', file)
    return request.post<ApiResponse<CanvasStoredFile>>(`/canvas/${id}/upload`, form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },

  /**
   * POST /api/canvas/{id}/nodes/{nodeId}/frames — 视频抽帧（C11）。
   * 首/尾/指定秒 → 新图片文件（SOURCE_CANVAS）；前端建图节点 + 自动连边回视频节点。
   * 失败不产空文件（后端抛 → 端点返错）。
   */
  extractFrame(id: number, nodeId: string, payload: { mode: FrameMode; second?: number }) {
    return request.post<ApiResponse<FrameExtractVO>>(
      `/canvas/${id}/nodes/${nodeId}/frames`,
      { mode: payload.mode, second: payload.second ?? null }
    )
  },

  /**
   * POST /api/canvas/{id}/nodes/{nodeId}/clip — 视频截取（C12）。
   * 时间段 [startSec,endSec) → 新视频文件（SOURCE_CANVAS）；前端建视频节点 + 自动连边回源视频节点。
   * 失败不产空文件（后端抛 → 端点返错）。
   */
  clipVideo(id: number, nodeId: string, payload: { startSec: number; endSec: number }) {
    return request.post<ApiResponse<VideoClipVO>>(
      `/canvas/${id}/nodes/${nodeId}/clip`,
      { startSec: payload.startSec, endSec: payload.endSec }
    )
  },

  /**
   * POST /api/canvas/{id}/storyboard/concat — 故事板顺序拼接（C13）。
   * 多个视频 fileId 按序首尾相接 → 新成片视频（SOURCE_CANVAS）；前端建成片节点。
   * 后端去重保序 + 每段 loadPath 归属校验。失败不产空文件。
   */
  concatStoryboard(id: number, fileIds: string[]) {
    return request.post<ApiResponse<StoryboardConcatVO>>(
      `/canvas/${id}/storyboard/concat`,
      { fileIds }
    )
  }
}

/**
 * 带鉴权拉取产出物文件并转 objectURL（图片/音频预览用）。
 *
 * 实现已抽到 {@link fetchFilePreview}（C2，资产卡片/画布/详情抽屉共用）。此处保留别名供既有
 * 调用方（CanvasView / AssetDetailDrawer）零改导入；新代码请直接 import {@link fetchFilePreview}。
 */
import { fetchFilePreview } from './file'
export { fetchFilePreview }
export async function fetchCanvasPreview(fileId: string): Promise<string> {
  return fetchFilePreview(fileId)
}
