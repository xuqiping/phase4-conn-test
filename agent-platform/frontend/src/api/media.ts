// ============================================================
// 媒体生成模块 API（视频生成：文生 / 多模态参考生视频）
// 对应后端 /api/media/** + /api/files/upload（参考附件复用单一上传咽喉点）
//   POST /api/media/video           → media:gen（提交）
//   GET  /api/media/models          → media:gen（可选模型目录 + 能力画像）
//   GET  /api/media/tasks/{id}      → media:gen（轮询任务态）
//   GET  /api/media/tasks           → media:gen（历史列表，ownership 过滤）
//   GET  /api/media/tasks/{id}/download → media:gen（视频附件，需 auth header）
//   POST /api/files/upload          → 登录用户（参考图/视频/音频，返 fileId）
// ============================================================

import request from './request'
import type { ApiResponse } from './request'

// === 类型定义 ===

/** 任务态（对齐后端 MediaGenTask.STATUS_*） */
export type MediaStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'DOWNLOAD_FAILED'

/** 任务类型 */
export type MediaTaskType =
  | 'TEXT2VIDEO'
  | 'IMAGE2VIDEO'
  | 'TEXT2IMAGE'
  | 'IMAGE2IMAGE'

/** 分辨率白名单（对齐后端校验；4K 仅 SeedDance 2.0 全版） */
export type MediaResolution = '480p' | '720p' | '1080p' | '4K'

/** 画面比例（官方 ratio 取值；adaptive 图生视频沿用参考图比例） */
export type MediaRatio = '21:9' | '16:9' | '4:3' | '1:1' | '3:4' | '9:16' | 'adaptive'

/**
 * 媒体任务视图（对应后端 MediaTaskVO）。
 * videoUrl 仅 SUCCEEDED 且当前用户有归属时非空 —— 指向下载端点（Content-Disposition 附件），
 * 因下载端点带 @RequirePermission 需 auth header，前端不能直接把 videoUrl 塞 <video src>，
 * 须用带 token 的 axios 拉 blob 再转 objectURL（见 fetchVideoBlob）。
 */
export interface MediaTaskVO {
  id: number
  status: MediaStatus
  /** usage 估算标记：非空（ESTIMATED）= token 按费率表估算非 Ark 真值 */
  statusFlag: string | null
  taskType: MediaTaskType
  model: string | null
  prompt: string | null
  duration: number | null
  ratio: string | null
  resolution: string | null
  tokensCost: number | null
  errorMsg: string | null
  /** 下载端点相对路径（仅 SUCCEEDED 且有归属） */
  videoUrl: string | null
  /** 结果文件 stored_files.file_id（仅 SUCCEEDED 且有归属）；C11 画布抽帧用 */
  resultFileId: string | null
  /** 图片任务：各张逐张下载端点列表（同 videoUrl 归属门控）；视频任务为 null */
  imageUrls: string[] | null
  /** 图片任务：各张 stored_files.file_id 列表（同 imageUrls 归属门控）；视频任务为 null。画布生图节点存首张作 fileId */
  imageFileIds: string[] | null
  /** 图片任务：官方 generated_images（计费张数） */
  generatedImages: number | null
  /** 图片任务：usage.output_tokens（审计） */
  outputTokens: number | null
  /** 图片任务回显：size */
  size: string | null
  /** 图片任务回显：输出格式 */
  outputFormat: string | null
  createdAt: string
  updatedAt: string | null
}

/** 参考附件类型（对齐后端 AttachmentRef.kind 白名单） */
export type AttachmentKind = 'image' | 'video' | 'audio'

/** 参考附件（先经 /api/files/upload 拿 fileId） */
export interface AttachmentRef {
  fileId: string
  kind: AttachmentKind
  /**
   * 参考帧角色（仅 kind=image）：first_frame 首帧 / last_frame 尾帧。
   * 省略 = 普通参考图（role:reference_image）。首尾帧与参考图统一走 attachments 通道，
   * 一次请求可含 1 首帧 + 1 尾帧 + N 参考图（后端校验全局各 ≤1）。
   */
  frameRole?: 'first_frame' | 'last_frame'
}

/**
 * 视频模型目录项（GET /api/media/models）。
 * 前端按能力画像动态渲染表单：附件上传区（x/maxImages 等）、比例/分辨率/时长选项、生成音频开关。
 */
export interface MediaModelVO {
  modelId: string
  displayName: string
  providerName: string
  maxImages: number
  maxVideos: number
  maxAudios: number
  /** 附件总数上限（图+视频+音频合计） */
  maxAttachments: number
  supportedRatios: MediaRatio[]
  supportedResolutions: MediaResolution[]
  minDuration: number
  maxDuration: number
  /** 是否支持「生成音频」开关 */
  supportsGenerateAudio: boolean
  /** 参考视频是否允许 data URI 直传（false → 前端隐藏视频上传区） */
  videoDataUri: boolean
}

// === 图片生成（Seedream lite/pro，GET /api/media/image/models） ===

/**
 * 生图模型能力清单（对应后端 ImageModelCapability）—— 前端数据驱动动态表单的唯一来源。
 * 「选不同模型 → 页面按该模型实际参数决定展示内容，枚举值用下拉框」硬约束由此驱动：
 * 各 supportsXxx 显隐控件，各 List 枚举填下拉候选。
 */
export interface ImageModelCapability {
  /** 参考图上限（lite=14，pro=10；0=不支持参考图） */
  refImageMax: number
  /** 参考图允许格式（lite 多格式，pro 仅 jpeg/png） */
  refImageFormats: string[]
  /** size 预设枚举（下拉候选）：lite=[2K,3K,4K]，pro=[1K,1.5K,2K] */
  sizePresets: string[]
  /** 是否支持自定义「宽x高」size */
  supportsWhSize: boolean
  /** 是否支持组图 sequential（lite 独有） */
  supportsSequential: boolean
  /** 组图最大生成数（lite=15） */
  maxSequentialImages: number
  /** 是否支持联网搜索（lite 独有） */
  supportsWebSearch: boolean
  /** 是否支持流式（lite 独有；MVP 固定 false，仅驱动 UI） */
  supportsStream: boolean
  /** 输出格式枚举（下拉候选）：[jpeg,png] */
  outputFormats: string[]
  /** 提示词优化模式枚举（下拉候选）：lite=[standard]，pro=[standard,fast] */
  optimizeModes: string[]
  /** 是否支持引导尺度 guidance_scale（pro 独有） */
  supportsGuidanceScale: boolean
  /** guidance_scale 下限（pro=1） */
  guidanceMin: number
  /** guidance_scale 上限（pro=10） */
  guidanceMax: number
  /** 水印默认值 */
  watermarkDefault: boolean
}

/** 生图模型目录项（GET /api/media/image/models） */
export interface ImageModelVO {
  modelId: string
  displayName: string
  providerName: string
  capability: ImageModelCapability
}

/** 生图提交请求（对应后端 ImageSubmitRequest）；不支持的字段传了值后端即拒 */
export interface ImageSubmitRequest {
  model: string
  prompt?: string
  /** 参考图 file_id 列表（资产库选取；纯文生图省略） */
  refFileIds?: string[]
  /** size 预设或自定义宽x高 */
  size?: string
  /** 输出格式 jpeg/png */
  outputFormat?: string
  watermark?: boolean
  /** 引导尺度（pro） */
  guidanceScale?: number
  /** 提示词优化模式 standard/fast */
  optimizeMode?: string
  /** 组图 auto/disabled（lite） */
  sequential?: string
  /** 组图最大生成数（lite） */
  maxImages?: number
  /** 联网搜索（lite） */
  webSearch?: boolean
}

/** 提交请求（对应后端 MediaSubmitRequest；duration/ratio/resolution 按模型能力校验） */
export interface MediaSubmitRequest {
  prompt: string
  /** 画面比例（官方 ratio），默认 16:9 */
  ratio?: MediaRatio
  duration?: number
  resolution?: MediaResolution
  /** 水印开关，默认 false */
  watermark?: boolean
  /** 同步生成原生音频（2.0 特色），默认 false */
  generateAudio?: boolean
  taskType?: MediaTaskType
  /** 旧版单首帧参考图 file_id（与 attachments 互斥；画布连线沿用此通道） */
  refFileId?: string
  /** 参考帧位置（仅 IMAGE2VIDEO + refFileId）：'first' 首帧（默认）/ 'last' 尾帧（SeedDance 2.0） */
  frameRole?: 'first' | 'last'
  /** 多模态参考附件（图/视频/音频；上限按模型能力，如 SeedDance 2.0：9图/3视频/3音频/总≤12） */
  attachments?: AttachmentRef[]
  /** 视频模型 id（可选，默认取默认 provider 首个模型） */
  model?: string
}

/** 提交响应：{ id, status } */
export interface MediaSubmitResult {
  id: number
  status: MediaStatus
}

/** /api/files/upload 返回（StoredFile 最小子集，前端只取 fileId） */
export interface StoredFileRef {
  fileId: string
}

// === API 函数 ===

export const mediaApi = {
  /** POST /api/media/video — 提交生成任务，返 {id,status}（media:gen） */
  submitVideo(data: MediaSubmitRequest) {
    return request.post<ApiResponse<MediaSubmitResult>>('/media/video', data)
  },

  /** GET /api/media/tasks/{id} — 轮询任务态+结果（media:gen） */
  getTask(id: number) {
    return request.get<ApiResponse<MediaTaskVO>>(`/media/tasks/${id}`)
  },

  /** GET /api/media/tasks?limit= — 历史列表（ownership 过滤；admin 看全量） */
  listTasks(limit = 50) {
    return request.get<ApiResponse<MediaTaskVO[]>>('/media/tasks', { params: { limit } })
  },

  /** GET /api/media/models — 可选视频模型目录（含能力画像，media:gen） */
  listModels() {
    return request.get<ApiResponse<MediaModelVO[]>>('/media/models')
  },

  // === 图片生成（Seedream 同步生图，按张计费） ===

  /** GET /api/media/image/models — 生图模型目录（含 ImageModelCapability，media:gen） */
  listImageModels() {
    return request.get<ApiResponse<ImageModelVO[]>>('/media/image/models')
  },

  /** POST /api/media/image — 提交生图任务，返 {id,status}（media:gen，按张计费） */
  submitImage(data: ImageSubmitRequest) {
    return request.post<ApiResponse<MediaSubmitResult>>('/media/image', data)
  },

  /** POST /api/files/upload — multipart 上传参考附件（图/视频/音频），返 fileId（登录用户） */
  uploadAttachment(file: File) {
    const fd = new FormData()
    fd.append('file', file)
    return request.post<ApiResponse<StoredFileRef>>('/files/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000 // 参考视频最大 50MB，放宽上传超时
    })
  }
}

/**
 * 带鉴权拉取视频并转 objectURL（用于 <video> 播放 / 下载）。
 *
 * 下载端点 @RequirePermission("media:gen") 需 Authorization header，<video src> / <a download>
 * 无法带 header，故走 axios 拉 blob（拦截器自动注 JWT）再 URL.createObjectURL。
 * 调用方负责在卸载/换任务时 URL.revokeObjectURL 释放。
 */
export async function fetchVideoBlob(downloadPath: string): Promise<string> {
  // VO 的 videoUrl 是 `/api/media/tasks/{id}/download`（带 /api 前缀），axios baseURL 已是 /api，
  // 去掉前缀避免拼成 /api/api/media/...
  const path = downloadPath.replace(/^\/api/, '')
  const res = await request.get<Blob>(path, { responseType: 'blob' })
  return URL.createObjectURL(res.data)
}

/**
 * 带鉴权拉取媒体产物并转 objectURL（视频/图片通用）。
 * 图片逐张下载端点 `/api/media/tasks/{id}/images/{idx}/download` 同样 @RequirePermission 需 auth header，
 * 走 axios 拉 blob（拦截器自动注 JWT）再 objectURL；调用方负责卸载/换图时 revoke。
 */
export async function fetchMediaBlob(downloadPath: string): Promise<string> {
  const path = downloadPath.replace(/^\/api/, '')
  const res = await request.get<Blob>(path, { responseType: 'blob' })
  return URL.createObjectURL(res.data)
}

/** 任务态 → 中文标签 */
export const MEDIA_STATUS_LABEL: Record<MediaStatus, string> = {
  PENDING: '排队中',
  RUNNING: '生成中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  DOWNLOAD_FAILED: '下载失败'
}

/** 任务态 → 主题色（Naive UI tag type） */
export const MEDIA_STATUS_TYPE: Record<MediaStatus, 'default' | 'info' | 'success' | 'error' | 'warning'> = {
  PENDING: 'default',
  RUNNING: 'info',
  SUCCEEDED: 'success',
  FAILED: 'error',
  DOWNLOAD_FAILED: 'warning'
}

/** 终态（停止轮询） */
export function isTerminal(status: MediaStatus): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'DOWNLOAD_FAILED'
}
