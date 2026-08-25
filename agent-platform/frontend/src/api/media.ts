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
  watermark: boolean | null
  generateAudio: boolean | null
  inputAttachments: InputAttachmentVO[]
  /** 7x-4：是否带参考视频（按 inputAttachments 里是否有 kind=="video" 算）；list/detail 都返 */
  hasReference: boolean | null
  /** 仅任务详情返回：平台持久化的提交参数（已剔除 Provider 快照）。 */
  submittedRequest: Record<string, unknown> | null
  /** 仅任务详情返回：实际 Provider 请求的脱敏快照；旧任务为 null。 */
  providerRequestSnapshot: Record<string, unknown> | null
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
  /** 仅用于历史回显，不进入 Provider 请求。 */
  name?: string
}

/** 任务详情中的输入附件摘要；previewUrl 仍需鉴权请求。 */
export interface InputAttachmentVO {
  fileId: string
  kind: AttachmentKind
  frameRole: 'first_frame' | 'last_frame' | null
  name: string | null
  previewUrl: string
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
  /** 当前部署是否已配置 Ark 可访问的短期签名 HTTPS 参考视频通道 */
  referenceVideoEnabled: boolean
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
  /** C3（6x/Q5）：比例模式（7 预设）——与档位 size 同传按档位预算推导宽x高；与显式宽x高互斥 */
  ratio?: string
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
  /** 计划5 Step6：参与项目组 id（组池计费+预检；省略=个人钱包）。 */
  projectGroupId?: number
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
  /** 计划5 Step6：参与项目组 id（组池计费+预检；省略=个人钱包）。 */
  projectGroupId?: number
}

/** 提交响应：{ id, status } */
export interface MediaSubmitResult {
  id: number
  status: MediaStatus
}

/** 历史任务服务端筛选；时间范围采用 [from,to) ISO-8601（拼装见 buildHistoryQuery）。 */
export interface MediaTaskQuery {
  q?: string
  from?: string
  to?: string
  /** @deprecated 兼容旧调用：pageSize 缺省时 limit 即每页条数（1-100，画布等既有消费方） */
  limit?: number
  /** 页码（≥1，缺省 1） */
  page?: number
  /** 每页条数白名单 5/10/20/50（缺省/非法由后端静默回落 10，4x#2） */
  pageSize?: number
  /** 任务大类过滤：IMAGE=仅图片任务、VIDEO=仅视频任务、缺省=全量（SQL 层过滤） */
  kind?: 'IMAGE' | 'VIDEO'
}

/** GET /api/media/tasks 分页包裹结构（后端 PageResult&lt;MediaTaskVO&gt;，4x#2）。 */
export interface MediaTaskPage {
  records: MediaTaskVO[]
  total: number
  page: number
  size: number
  pages: number
}

/**
 * 图片/视频两页共享的历史筛选 → listTasks 查询参数拼装（防两处漂移，4x#2）。
 * rangeType 语义两页不同，不可盲目统一：
 * - 'day'（图片页 daterange，整天选择）：to=尾日 23:59:59.999 含尾日全天（否则同日区间 from==to 被后端 400）；
 * - 'datetime'（视频页 datetimerange，精确到时分秒）：to 原样使用用户所选时刻。
 */
export function buildHistoryQuery(opts: {
  q?: string
  range?: [number, number] | null
  kind: 'IMAGE' | 'VIDEO'
  rangeType: 'day' | 'datetime'
  page?: number
  pageSize?: number
}): MediaTaskQuery {
  const range = opts.range ?? null
  const query: MediaTaskQuery = {
    q: opts.q?.trim() || undefined,
    from: range ? new Date(range[0]).toISOString() : undefined,
    to: range ? new Date(range[1] + (opts.rangeType === 'day' ? 24 * 3600 * 1000 - 1 : 0)).toISOString() : undefined,
    kind: opts.kind
  }
  if (opts.page != null) query.page = opts.page
  if (opts.pageSize != null) query.pageSize = opts.pageSize
  return query
}

/** /api/files/upload 返回（StoredFile 最小子集，前端只取 fileId） */
export interface StoredFileRef {
  fileId: string
}

// === 视频反推与本土化转绘（计划6，对齐后端 media/reverse/dto） ===

/** 反推产物组合（后端 modes 白名单子集）。 */
export type ReverseMode = 'KEYFRAMES' | 'STORYBOARD' | 'SCRIPT'

/** 关键帧（fileId=原始帧供查看，thumbFileId=缩略帧；无缩略时两者相同）。 */
export interface ReverseKeyframe {
  fileId: string
  thumbFileId: string
  timestampSec: number
  shotNo: number
}

/** 分镜条目（LLM 产物，字段开放——提示词约束核心字段名）。 */
export interface ReverseStoryboardShot extends Record<string, unknown> {
  shotNo?: number
  startSec?: number
  endSec?: number
  shotSize?: string
  cameraMove?: string
  description?: string
  dialogue?: string
}

/** 反推分析请求（taskId/fileId 二选一）。 */
export interface ReverseAnalyzeRequest {
  taskId?: number | null
  fileId?: string | null
  modes: ReverseMode[]
  sceneThreshold?: number
  maxFrames?: number
  /** 指定反推用对话大模型（可空=管理员默认对话模型，可能非多模态——建议选视觉模型） */
  model?: string
}

/** 反推分析响应：keyframes 恒有；storyboard/script 按请求 modes 带（未请求为 null）。 */
export interface ReverseAnalyzeResult {
  keyframes: ReverseKeyframe[]
  durationSeconds: number
  mode: 'SCENE' | 'UNIFORM'
  sceneHits: number
  storyboard: ReverseStoryboardShot[] | null
  script: Record<string, unknown> | null
  /** 实际使用的模型（请求未指定时为管理员默认，可能非多模态——用户可感知） */
  model: string | null
}

/** 本土化转绘请求/响应（warning=结构校验告警，非空=场景数不一致，结果仍可用）。 */
export interface LocalizeRequest {
  script: string
  targetLocale: string
  notes?: string
  /** 指定转绘用对话大模型（可空=管理员默认对话模型） */
  model?: string
}

export interface LocalizeChange extends Record<string, unknown> {
  from?: string
  to?: string
  scene?: string
}

export interface LocalizeResult {
  localizedScript: string
  changeLog: LocalizeChange[]
  warning: string | null
}

/** 7x（V155）：提交前预估预览查询（kind VIDEO/IMAGE；视频带 videoSeconds/resolution/hasReference，图片带 imageCount）。 */
export interface MediaEstimateQuery {
  kind: 'VIDEO' | 'IMAGE'
  model?: string
  videoSeconds?: number
  resolution?: string
  hasReference?: boolean
  imageCount?: number
  projectGroupId?: number
}

/** C1（17x-2）：组内预估个人口径——balance 是组池（全组共享），成员另有自己的限额卡。 */
export interface MediaEstimatePersonalScope {
  quota: number | null
  used: number
  inProjectAvailable: number | null
  affordableMember: boolean
  /** 卡点归因：MEMBER=个人限额卡 / POOL=组池卡 / NONE=都够（非成员无本结构） */
  bindingConstraint: 'MEMBER' | 'POOL' | 'NONE'
}

/** 7x（V155）+C1：预估预览结果（积分口径；fail-closed：估价失败 estimatedPoints=0 且 affordable=false，提交侧同拒）。 */
export interface MediaEstimateVO {
  estimatedPoints: number
  balance: number
  affordable: boolean
  personalScope?: MediaEstimatePersonalScope | null
}

// === API 函数 ===

export const mediaApi = {
  /** POST /api/media/video — 提交生成任务，返 {id,status}（media:gen） */
  submitVideo(data: MediaSubmitRequest) {
    return request.post<ApiResponse<MediaSubmitResult>>('/media/video', data)
  },

  /** GET /api/media/tasks/{id} — 轮询任务态+结果（media:gen）。
   *  2x 四轮 Step1：后台型（豁免断路/清会话）+ timeout 30s（慢网一次超时不误判，退避由轮询器负责）。 */
  getTask(id: number) {
    return request.get<ApiResponse<MediaTaskVO>>(`/media/tasks/${id}`, {
      _background: true,
      timeout: 30000
    })
  },

  /** GET /api/media/tasks — 历史分页列表（服务端筛选 + ownership；admin 看全量；包裹结构 MediaTaskPage，4x#2）。 */
  listTasks(query: number | MediaTaskQuery = 50) {
    const params = typeof query === 'number' ? { limit: query } : query
    return request.get<ApiResponse<MediaTaskPage>>('/media/tasks', { params })
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

  /** GET /api/media/estimate — 7x（V155）提交前预估预览：估积分+余额+affordable（不落库不预扣，前端防抖询） */
  estimatePreview(params: MediaEstimateQuery) {
    return request.get<ApiResponse<MediaEstimateVO>>('/media/estimate', { params })
  },

  /** POST /api/files/upload — multipart 上传参考附件（图/视频/音频），返 fileId（登录用户） */
  uploadAttachment(file: File) {
    const fd = new FormData()
    fd.append('file', file)
    return request.post<ApiResponse<StoredFileRef>>('/files/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000 // 参考视频最大 50MB，放宽上传超时
    })
  },

  // === 视频反推与本土化转绘（计划6，/api/media/reverse/**） ===

  /**
   * POST /api/media/reverse/analyze — 反推分析（关键帧恒返 + 分镜/剧本按 modes，media:gen）。
   * 同步接口：抽帧 + 单次多模态 LLM（秒级~分钟级）→ timeout 放大 120s + background 标记
   * （防一次网络波动触发断路，规格 §4.1）；signal 接 AbortController 供「取消反推」（plan L2）。
   */
  reverseAnalyze(data: ReverseAnalyzeRequest, signal?: AbortSignal) {
    return request.post<ApiResponse<ReverseAnalyzeResult>>('/media/reverse/analyze', data, {
      timeout: 120000,
      _background: true,
      signal
    })
  },

  /**
   * POST /api/media/reverse/localize — 剧本本土化改写（剧情/分镜结构不变，文化元素替换）。
   * 纯 LLM 文本调用，60s 足够；background 同上（长请求不触发断路）。
   */
  reverseLocalize(data: LocalizeRequest, signal?: AbortSignal) {
    return request.post<ApiResponse<LocalizeResult>>('/media/reverse/localize', data, {
      timeout: 60000,
      _background: true,
      signal
    })
  }
}

/**
 * 4x-1：会话内媒体产物 blob 缓存（LRU）。缓存的请求路径 ↔ Blob，
 * 每次调用仍各自 createObjectURL——调用方 revoke 自己的 URL，互不影响。
 * 后端 `/tasks/{id}/download` 已带 ETag + no-cache（304 再验证），跨会话由 HTTP 缓存兜底；
 * 本缓存解决同一会话内切任务/翻页反复拉同一视频的重复下载。
 * 任务结果文件不可变（重新生成=新任务新 id 新路径），会话内命中不存在过期问题。
 */
const mediaBlobCache = new Map<string, Blob>()
const MEDIA_BLOB_CACHE_MAX_ENTRIES = 6
const MEDIA_BLOB_CACHE_MAX_BYTES = 256 * 1024 * 1024
let mediaBlobCacheBytes = 0

function cacheMediaBlob(path: string, blob: Blob) {
  if (blob.size > MEDIA_BLOB_CACHE_MAX_BYTES) return // 单条超大不缓存
  // LRU 淘汰：Map 迭代序=插入序，超条数/超字节从最旧开始逐出
  while (mediaBlobCache.size >= MEDIA_BLOB_CACHE_MAX_ENTRIES
      || (mediaBlobCacheBytes + blob.size > MEDIA_BLOB_CACHE_MAX_BYTES && mediaBlobCache.size > 0)) {
    const oldest = mediaBlobCache.keys().next().value
    if (oldest === undefined) break
    mediaBlobCacheBytes -= mediaBlobCache.get(oldest)!.size
    mediaBlobCache.delete(oldest)
  }
  mediaBlobCache.set(path, blob)
  mediaBlobCacheBytes += blob.size
}

/** 命中缓存则 LRU 触碰（删了重插到末尾）并直接返回新 objectURL。 */
function cachedObjectUrl(path: string): string | null {
  const hit = mediaBlobCache.get(path)
  if (!hit) return null
  mediaBlobCache.delete(path)
  mediaBlobCache.set(path, hit)
  return URL.createObjectURL(hit)
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
  const hit = cachedObjectUrl(path)
  if (hit) return hit
  // 2x 四轮 Step1：预览预取属后台型（断网不弹「服务不可达」不踢会话，恢复后重拉）
  const res = await request.get<Blob>(path, { responseType: 'blob', _background: true })
  cacheMediaBlob(path, res.data)
  return URL.createObjectURL(res.data)
}

/**
 * 带鉴权拉取媒体产物并转 objectURL（视频/图片通用）。
 * 图片逐张下载端点 `/api/media/tasks/{id}/images/{idx}/download` 同样 @RequirePermission 需 auth header，
 * 走 axios 拉 blob（拦截器自动注 JWT）再 objectURL；调用方负责卸载/换图时 revoke。
 */
export async function fetchMediaBlob(downloadPath: string): Promise<string> {
  const path = downloadPath.replace(/^\/api/, '')
  // 4x-1：同 fetchVideoBlob 走会话内 LRU 缓存（图片体积小，命中收益同样明显）
  const hit = cachedObjectUrl(path)
  if (hit) return hit
  // 2x 四轮 Step1：预览预取属后台型（同 fetchVideoBlob）
  const res = await request.get<Blob>(path, { responseType: 'blob', _background: true })
  cacheMediaBlob(path, res.data)
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
