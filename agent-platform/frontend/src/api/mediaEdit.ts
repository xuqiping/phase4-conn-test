// ============================================================
// 视频剪辑模块 API（多轨时间线：视频轨 + 多音轨 + 字幕轨，后端 FFmpeg 渲染 + 剪映草稿导出）
// 对应后端 /api/media/edit/** + /api/files/upload（素材上传复用单一咽喉点）
//   GET  /api/media/edit/assets          → media:edit（素材库：已生成视频）
//   POST /api/media/edit/submit          → media:edit（提交渲染，body=EditSpec V2）
//   POST /api/media/edit/export-draft    → media:edit（导出剪映草稿 zip，body=EditSpec V2）
//   GET  /api/media/edit/tasks/{id}      → media:edit（轮询任务态）
//   GET  /api/media/edit/tasks           → media:edit（历史列表，ownership 过滤）
//   GET  /api/media/edit/tasks/{id}/download → media:edit（成片附件，需 auth header）
//   POST /api/files/upload               → 登录用户（上传视频/音频素材，返 fileId）
// ============================================================

import request from './request'
import type { ApiResponse } from './request'

// === 类型定义（对齐后端 EditSpec V2 / VO） ===

export type EditStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'DOWNLOAD_FAILED'

export type EditResolution = '480p' | '720p' | '1080p'

export type TrackType = 'VIDEO' | 'AUDIO' | 'TEXT'

/** 视频/音频片段（V2）。trim* 是素材内裁剪，target* 是成片时间轴定位（秒）。 */
export interface SegmentSpec {
  fileId: string
  sourceType?: 'GEN' | 'UPLOAD'
  /** source 侧裁剪起点（秒，空=0） */
  trimStart?: number | null
  /** source 侧裁剪终点（秒，空=素材全长） */
  trimEnd?: number | null
  /** 成片时间轴起点（秒） */
  targetStart: number
  /** 成片时间轴终点（秒） */
  targetEnd: number
  /** 段级音量 0~1（AUDIO 段 + 含音频的 VIDEO 段；空=轨级 volume） */
  volume?: number | null
}

/** 字幕段（V2 TEXT 轨元素）。 */
export interface TextSegmentSpec {
  content: string
  targetStart: number
  targetEnd: number
  position?: 'CENTER' | 'BOTTOM'
  fontSize?: number | null
}

/** 轨道（V2）。VIDEO/AUDIO 用 segments，TEXT 用 texts。 */
export interface TrackSpec {
  /** 前端临时 id（不入后端契约，仅 v-for key 用；提交时可剔除） */
  id: string
  type: TrackType
  name?: string
  /** AUDIO 轨默认音量 0~1 */
  volume?: number | null
  segments?: SegmentSpec[]
  texts?: TextSegmentSpec[]
}

export interface OutputSpec {
  resolution?: EditResolution
  fps?: number
}

/** 剪辑意图（V2 提交体，对应后端 EditSpec）。 */
export interface EditSpec {
  schemaVersion: 2
  tracks: TrackSpec[]
  output?: OutputSpec
}

export interface MediaEditTaskVO {
  id: number
  status: EditStatus
  errorMsg: string | null
  clipsCount: number
  hasBgm: boolean
  subtitlesCount: number
  videoUrl: string | null
  createdAt: string
  updatedAt: string | null
}

export interface MediaAssetVO {
  fileId: string
  name: string
  durationSeconds: number | null
  sourceType: string
  createdAt: string
}

export interface StoredFileRef {
  fileId: string
}

export interface SubmitResult {
  id: number
  status: EditStatus
}

// === API 函数 ===

export const mediaEditApi = {
  /** GET /api/media/edit/assets — 素材库（已生成视频，ownership 过滤；admin 全量） */
  listAssets() {
    return request.get<ApiResponse<MediaAssetVO[]>>('/media/edit/assets')
  },

  /** POST /api/media/edit/submit — 提交剪辑渲染任务，返 {id,status}（media:edit） */
  submit(data: EditSpec) {
    return request.post<ApiResponse<SubmitResult>>('/media/edit/submit', data)
  },

  /**
   * POST /api/media/edit/export-draft — 导出剪映草稿 zip，返 Blob（media:edit）。
   * 调用方负责用 Blob 触发浏览器下载（createObjectURL + a.click）。
   * @param absolutePath true=素材 path 写服务器绝对路径（不打包）；false=打包素材进 zip（默认，可移植）
   */
  async exportDraft(data: EditSpec, absolutePath = false): Promise<{ blob: Blob; filename: string }> {
    const res = await request.post('/media/edit/export-draft', data, {
      responseType: 'blob',
      params: { absolutePath }
    })
    // 从 Content-Disposition 解析文件名（后端 filename*=UTF-8''xxx.zip）
    const cd = (res.headers['content-disposition'] || '') as string
    const m = cd.match(/filename\*=UTF-8''([^;]+)/i)
    const filename = m ? decodeURIComponent(m[1]) : 'futurex-draft.zip'
    return { blob: res.data as Blob, filename }
  },

  /** GET /api/media/edit/tasks/{id} — 轮询任务态+结果（media:edit） */
  getTask(id: number) {
    return request.get<ApiResponse<MediaEditTaskVO>>(`/media/edit/tasks/${id}`)
  },

  /** GET /api/media/edit/tasks?limit= — 历史列表（ownership 过滤；admin 看全量） */
  listTasks(limit = 50) {
    return request.get<ApiResponse<MediaEditTaskVO[]>>('/media/edit/tasks', { params: { limit } })
  },

  /** POST /api/files/upload — multipart 上传视频/音频素材，返 fileId（登录用户） */
  uploadAsset(file: File) {
    const fd = new FormData()
    fd.append('file', file)
    return request.post<ApiResponse<StoredFileRef>>('/files/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 60000
    })
  }
}

/**
 * 带鉴权拉取成片并转 objectURL（<video> 播放 / 下载）。
 * 下载端点 @RequirePermission("media:edit") 需 Authorization header，<video src> 无法带 → axios 拉 blob 再 createObjectURL。
 * 调用方负责在卸载/换任务时 URL.revokeObjectURL 释放。
 */
export async function fetchResultBlob(downloadPath: string): Promise<string> {
  const path = downloadPath.replace(/^\/api/, '')
  const res = await request.get<Blob>(path, { responseType: 'blob' })
  return URL.createObjectURL(res.data)
}

/** 任务态 → 中文标签 */
export const EDIT_STATUS_LABEL: Record<EditStatus, string> = {
  PENDING: '排队中',
  RUNNING: '渲染中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  DOWNLOAD_FAILED: '下载失败'
}

/** 任务态 → 主题色（Naive UI tag type） */
export const EDIT_STATUS_TYPE: Record<EditStatus, 'default' | 'info' | 'success' | 'error' | 'warning'> = {
  PENDING: 'default',
  RUNNING: 'info',
  SUCCEEDED: 'success',
  FAILED: 'error',
  DOWNLOAD_FAILED: 'warning'
}

/** 终态（停止轮询） */
export function isTerminalEdit(status: EditStatus): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'DOWNLOAD_FAILED'
}
