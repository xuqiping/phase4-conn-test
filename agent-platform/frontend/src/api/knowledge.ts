// ============================================================
// 知识库模块 API
// 对应后端 /api/knowledge/** 端点
//   bases       → knowledge:read（列表/查）/ knowledge:write（建改删）
//   documents   → knowledge:read（列表/查）/ knowledge:write（上传/删）
//   permissions → knowledge:write（授权/撤销/列表）
//   retrieve    → knowledge:read（检索调试，同步 POST）
// ============================================================

import request from './request'
import type { ApiResponse } from './request'
import type { PageResult } from '@/api/admin'
import { getStorage, STORAGE_KEYS } from '@/utils/storage'

// === 类型定义 ===

/** 知识库（对应后端 KnowledgeBaseVO） */
export interface KnowledgeBase {
  id: number
  name: string
  description: string | null
  visibility: string              // PRIVATE / TEAM / PUBLIC
  embeddingModel: string | null
  rerankModel: string | null
  /** 14x#1：per-KB 问答模型，null=跟随全局默认 */
  answerModel: string | null
  /** 14x#1（L4）：换 embedding 且已有文档时的重建索引强提示，正常 null */
  warning?: string | null
  /** 14x#3：库级保密开关——成员仅可经问答召回，原件/切片/调试全收紧（owner/admin 直通） */
  confidential: boolean
  summaryStrategy: string | null  // PER_SECTION / BATCH / HYBRID
  status: string
  createdBy: number | null
  createdAt: string
  canManage: boolean
  /** 14x#2：per-KB 写权限（上传/直传；canRead 单独授权不再隐含写） */
  canWrite: boolean
  canRead: boolean
}

/** 建库/改库请求（对应后端 KnowledgeBaseRequest） */
export interface KnowledgeBaseRequest {
  name: string
  description?: string
  visibility?: string
  embeddingModel?: string
  rerankModel?: string
  /** 14x#1：per-KB 问答模型，空=跟随全局默认（服务端校验 active CHAT 白名单） */
  answerModel?: string
  /** 14x#3：保密开关；undefined=不改既有开关（PUBLIC 库禁开，服务端互斥校验） */
  confidential?: boolean
  summaryStrategy?: string
}

export type RankingMode = 'LLM' | 'RERANK' | 'DISABLED'
export interface RankingConfig {
  configId?: number | null
  mode: RankingMode
  model?: string | null
  configVersion?: string
  candidateLimit: number
  finalLimit: number
  batchSize: number
  timeoutMs: number
  fallbackPolicy: string
  highAccuracyEnabled: boolean
  source?: 'KNOWLEDGE_BASE' | 'ADMIN_DEFAULT'
}

export interface RankingConfigUpdate {
  rankingMode: RankingMode
  model?: string | null
  candidateLimit?: number
  finalLimit?: number
  batchSize?: number
  timeoutMs?: number
  fallbackPolicy?: string
  highAccuracyEnabled?: boolean
}

export interface KnowledgeIndexStatus {
  knowledgeBaseId: number
  state: string
  readAlias: string
  writeAlias: string
  activeSnapshotId?: string | null
  previousSnapshotId?: string | null
  rebuildSnapshotId?: string | null
  total?: number | null
  completed?: number | null
  failed?: number | null
  cancelled?: number | null
}

export interface RagRolloutState {
  knowledgeBaseId: number
  percentage: number
  configVersion: string
  operatorId: number
}

/** 文档（对应后端 KnowledgeDocumentVO） */
export interface KnowledgeDocument {
  id: number
  kbId: number
  title: string
  docType: string | null
  status: string                  // PENDING/PARSING/SUMMARIZING/EMBEDDING/INDEXED/FAILED
  currentVersionId: number | null
  fileRef: string | null
  fileHash: string | null
  /** IMAGE/FILE 原件回显（mime 决定缩略图/下载；originalName 展示文件名） */
  mime: string | null
  originalName: string | null
  /** 索引方式 MANUAL/AUTO（parse_options 解出，列表显徽章） */
  indexMode: string | null
  parseError: string | null
  /** 解析选项 JSON（Excel selectedSheets 等），前端展示已选 sheet（V39） */
  parseOptions: string | null
  /** 非致命解析告警（Excel 截断/降级），前端黄色徽章（V39） */
  parseWarning: string | null
  /** 隔离原因：status=QUARANTINED 时返回，红色徽标+tooltip（安全体系 S3） */
  quarantineReason: string | null
  ownerId: number | null
  sourceType: string | null
  sourceUri: string | null
  sourceUpdatedAt: string | null
  authorityLevel: 'OFFICIAL' | 'APPROVED' | 'REFERENCE' | 'UNVERIFIED'
  confidentialityLevel: 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'RESTRICTED'
  tags: string[]
  effectiveAt: string | null
  expiredAt: string | null
  createdAt: string
  updatedAt: string | null
}

/** 图片/文件知识库上传选项（空=后端按后缀推断 docType + AUTO 默认）。 */
export interface UploadOptions {
  docType?: string
  indexMode?: 'MANUAL' | 'AUTO'
  manualIndexText?: string
  visionModel?: string
}

/** Excel sheet 预读结果（阶段1 picker）。tempFileRef 阶段2 upload 复用，零重传。 */
export interface SheetPreview {
  tempFileRef: string
  fileName: string
  sheetNames: string[]
}

/** 知识节点（对应后端 KnowledgeNodeVO，文档目录树/原文查看用，flat 列表按 parentId 建树） */
export interface KnowledgeNode {
  id: number
  parentId: number | null
  documentId: number
  level: string                   // L0 摘要 / L2 原文（L1 在 documents 表）
  nodeType: string                // DIRECTORY / SECTION / TABLE / FAQ
  title: string | null
  content: string | null          // L0 摘要 / L2 原文片段
  tokenCount: number | null
  status: string                  // ACTIVE / STALE / ARCHIVED
}

/** 权限授权记录（对应后端 KnowledgePermissionVO） */
export interface KnowledgePermission {
  id: number
  targetType: string              // KB / DIRECTORY / DOCUMENT
  targetId: number
  subjectType: string             // USER / ROLE / DEPARTMENT / SERVICE_ACCOUNT
  subjectId: number
  subjectName: string | null
  canRead: boolean
  canWrite: boolean
  canManage: boolean
  grantedBy: number | null
  createdAt: string
}

/** 授权请求（对应后端 KnowledgePermissionRequest） */
export interface KnowledgePermissionRequest {
  targetType: string
  targetId: number
  subjectType: string
  subjectId: number
  canRead?: boolean
  canWrite?: boolean
  canManage?: boolean
}

/** 检索调试请求（对应后端 RagRetrieveRequest；adminHint 后端强制覆盖，前端不发） */
export interface RagRetrieveRequest {
  kbId: number
  query: string
  docTypes?: string[]
  maxL0?: number
  mode?: string                   // Phase1 仅 BALANCED
  generateAnswer?: boolean        // false（默认）= 纯检索不调 LLM；true = 生成答案（慢）
}

export interface RagCitation {
  index: number
  documentId: number
  title: string
  nodeId: number
  /** IMAGE/FILE 回显（docType=IMAGE 渲染缩略图，FILE 渲染下载链）。null=普通文本引用。 */
  docType?: string | null
  fileRef?: string | null
  mime?: string | null
  originalName?: string | null
  page?: string | null
  article?: string | null
  sheet?: string | null
  cellRange?: string | null
  bbox?: string | null
  /** 14x#3：引用来自保密库且当前用户非 owner/admin → 隐藏缩略图/下载入口（后端 asset 403 兜底） */
  confidential?: boolean
}

export interface RagRecallHit {
  nodeId: number
  documentId: number
  title: string
  /** L0 摘要原文（node.content），调试面板展示用 */
  content: string
  cosineDistance: number
  cosineSimilarity: number
}

/** L1 文档向量召回命中（doc 级语义锚，无 nodeId） */
export interface RagL1RecallHit {
  documentId: number
  title: string
  cosineDistance: number
  cosineSimilarity: number
  /** L1 元数据（向量化文本来源）：摘要/大纲/要点，可能为 null */
  summary: string | null
  outline: string | null
  importantRules: string | null
}

/** 纯 BM25 词法兜底候选（无向量父锚） */
export interface RagBm25Hit {
  nodeId: number
  documentId: number
  title: string
  bm25Rank: number | null
}

export interface RagEvidence {
  nodeId: number
  documentId: number
  title: string
  content: string
  contentHash: string
  docType: string | null
  /** IMAGE/FILE 回显（docType=IMAGE 渲染缩略图，FILE 渲染下载链）。null=普通文本证据。 */
  fileRef: string | null
  mime: string | null
  originalName: string | null
  citationIndex: number
  rerankScore: number
}

export interface RagTokenBudget {
  maxContextTokens: number
  modelMaxContext: number
  answerTokenReserve: number
  effectiveContextCap: number
  promptTokens: number
}

/** 检索调试响应（对应后端 RagRetrieveVO） */
export interface RagRetrieveVO {
  traceId: string
  abstained: boolean
  abstainReason: string | null
  confidenceState?: 'SUPPORTED' | 'PARTIAL' | 'CONFLICT' | 'INSUFFICIENT' | 'OUT_OF_SCOPE' | 'RETRIEVAL_FAILED'
  answer: string
  citations: RagCitation[]
  candidatesL0: RagRecallHit[]
  /** L1 文档向量召回命中（空=短路路径未算 L1） */
  candidatesL1: RagL1RecallHit[]
  /** 词法兜底是否触发：true=有纯 BM25 候选进入 pool */
  bm25Fallback: boolean
  /** 进入 topK 的纯 BM25 候选（bm25Fallback=false 时为空） */
  candidatesBm25: RagBm25Hit[]
  evidenceL2: RagEvidence[]
  tokenBudget: RagTokenBudget
  latencyMs: number
  retrievalTimeline?: Array<{ stage: string; configuredMode?: string; effectiveMode?: string; model?: string | null; candidateCount: number; latencyMs: number; status: string }>
}

/** 检索审计记录（对应后端 RagRetrievalLogVO，大 JSON 字段原样透传） */
export interface RagRetrievalLog {
  id: number
  traceId: string | null
  userId: number | null
  identityType: string | null
  kbIds: string | null
  query: string | null
  mode: string | null
  l2LexicalFallback: boolean | null
  /** SUPPORTED / LOW_CONFIDENCE / NO_DENSE_HITS / NO_VISIBLE_DOCS / CITATION_CHECK_FAIL / ERROR */
  cragVerdict: string | null
  latencyMs: number | null
  createdAt: string
  candidatesL0: string | null
  evidenceL2: string | null
  tokenBudget: string | null
}

/** 检索审计分页查询（对应后端 GET 参数，ISO-8601 时间串） */
export interface RetrievalLogPageQuery {
  page?: number
  size?: number
  userId?: number
  kbId?: number
  mode?: string
  from?: string
  to?: string
}

export interface KnowledgeDocumentMetadataUpdate {
  ownerId?: number | null
  sourceType?: string | null
  sourceUri?: string | null
  sourceUpdatedAt?: string | null
  authorityLevel: KnowledgeDocument['authorityLevel']
  confidentialityLevel: KnowledgeDocument['confidentialityLevel']
  tags: string[]
  effectiveAt?: string | null
  expiredAt?: string | null
}

export interface KnowledgeDocumentVersion {
  id: number
  documentId: number
  versionNo: number
  parentVersionId: number | null
  sourceHash: string | null
  fileRef: string | null
  changeNote: string | null
  status: 'DRAFT' | 'EFFECTIVE' | 'ARCHIVED' | 'REVOKED' | 'SUPERSEDED'
  effectiveAt: string | null
  revokedAt: string | null
  replacedByVersionId: number | null
  createdAt: string
}

export interface RagTraceDetail {
  traceId: string
  retrievals: Array<Record<string, unknown>>
  rankings: Array<Record<string, unknown>>
  modelCalls: Array<Record<string, unknown>>
  usages: Array<Record<string, unknown>>
  audits: Array<Record<string, unknown>>
}

export interface EvaluationDataset {
  id: number
  tenantId: number
  kbId: number
  name: string
  description?: string | null
  createdBy: number
}

export interface EvaluationCase {
  id: number
  datasetId: number
  queryType: string
  question: string
  expectedChunkIds: string[]
  forbiddenChunkIds: string[]
  answerable: boolean
  metadata: Record<string, unknown>
}

export interface EvaluationImportResult {
  imported: number
  errors: Array<{ line: number; message: string }>
}

export interface EvaluationRun {
  id: number
  tenantId: number
  datasetId: number
  pipelineVersion: string
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  startedBy: number
  startedAt: string
  finishedAt?: string | null
  summaryMetrics: Record<string, number>
  errorSummary?: string | null
}

export interface ShadowComparison {
  id: number
  kbId: number
  championTraceId?: string | null
  challengerTraceId?: string | null
  championVersion: string
  challengerVersion: string
  status: string
  rankedChunkIds: string[]
  cost: number
  errorSummary?: string | null
  createdAt: string
}

// === API 函数 ===

/** FormData 追加图片/文件上传选项（非空字段才追加，空=后端按后缀推断 + AUTO 默认）。 */
function appendUploadOptions(fd: FormData, opts?: UploadOptions) {
  if (!opts) return
  if (opts.docType) fd.append('docType', opts.docType)
  if (opts.indexMode) fd.append('indexMode', opts.indexMode)
  if (opts.manualIndexText && opts.manualIndexText.trim()) fd.append('manualIndexText', opts.manualIndexText.trim())
  if (opts.visionModel) fd.append('visionModel', opts.visionModel)
}

export const knowledgeApi = {
  // ---- 知识库 ----
  /** GET /api/knowledge/bases — 当前用户可见的 KB 列表（knowledge:read） */
  listBases() {
    return request.get<ApiResponse<KnowledgeBase[]>>('/knowledge/bases')
  },
  getBase(id: number) {
    return request.get<ApiResponse<KnowledgeBase>>(`/knowledge/bases/${id}`)
  },
  createBase(data: KnowledgeBaseRequest) {
    return request.post<ApiResponse<KnowledgeBase>>('/knowledge/bases', data)
  },
  updateBase(id: number, data: KnowledgeBaseRequest) {
    return request.put<ApiResponse<KnowledgeBase>>(`/knowledge/bases/${id}`, data)
  },
  getDefaultRankingConfig() {
    return request.get<ApiResponse<RankingConfig>>('/knowledge/admin/ranking-config')
  },
  updateDefaultRankingConfig(data: RankingConfigUpdate) {
    return request.put<ApiResponse<RankingConfig>>('/knowledge/admin/ranking-config', data)
  },
  getIndexStatus(kbId: number) {
    return request.get<ApiResponse<KnowledgeIndexStatus>>(`/knowledge/admin/indexes/${kbId}`)
  },
  rebuildIndex(kbId: number, snapshotId: string, dryRun = true) {
    return request.post<ApiResponse<KnowledgeIndexStatus>>(`/knowledge/admin/indexes/${kbId}/rebuild`, { snapshotId, dryRun, confirmed: false })
  },
  cancelIndexRebuild(kbId: number, snapshotId: string) {
    return request.post<ApiResponse<KnowledgeIndexStatus>>(`/knowledge/admin/indexes/${kbId}/rebuild/cancel`, {
      snapshotId, dryRun: false, confirmed: true
    })
  },
  switchIndex(kbId: number, snapshotId: string) {
    return request.post<ApiResponse<KnowledgeIndexStatus>>(`/knowledge/admin/indexes/${kbId}/switch`, { snapshotId, confirmed: true, dryRun: false })
  },
  rollbackIndex(kbId: number) {
    return request.post<ApiResponse<KnowledgeIndexStatus>>(`/knowledge/admin/indexes/${kbId}/rollback`, { snapshotId: 'rollback', confirmed: true, dryRun: false })
  },
  getRolloutStatus(kbId: number) {
    return request.get<ApiResponse<RagRolloutState>>(`/knowledge/admin/rollouts/${kbId}`)
  },
  configureRollout(kbId: number, percentage: number, configVersion: string) {
    return request.post<ApiResponse<RagRolloutState>>(`/knowledge/admin/rollouts/${kbId}`, {
      percentage, configVersion, confirmed: true
    })
  },
  rollbackRollout(kbId: number) {
    return request.post<ApiResponse<RagRolloutState>>(`/knowledge/admin/rollouts/${kbId}/rollback`, {
      percentage: 0, configVersion: 'rollback', confirmed: true
    })
  },
  submitRagFeedback(data: { knowledgeBaseId: number; evaluationResultId?: number; category: string; comment?: string }) {
    return request.post<ApiResponse<{ id: number; status: string }>>('/knowledge/feedback', data)
  },
  createEvaluationDataset(data: { kbId: number; name: string; description?: string }) {
    return request.post<ApiResponse<EvaluationDataset>>('/knowledge/admin/evaluation/datasets', data)
  },
  importEvaluationJsonl(datasetId: number, jsonl: string) {
    return request.post<ApiResponse<EvaluationImportResult>>(
      `/knowledge/admin/evaluation/datasets/${datasetId}/cases/import`, jsonl,
      { headers: { 'Content-Type': 'application/x-ndjson' } }
    )
  },
  listEvaluationCases(datasetId: number) {
    return request.get<ApiResponse<EvaluationCase[]>>(
      `/knowledge/admin/evaluation/datasets/${datasetId}/cases`
    )
  },
  exportEvaluationJsonl(datasetId: number) {
    return request.get<string>(`/knowledge/admin/evaluation/datasets/${datasetId}/cases/export`, {
      responseType: 'text'
    })
  },
  startEvaluationRun(datasetId: number, pipelineVersion: string) {
    return request.post<ApiResponse<EvaluationRun>>(
      `/knowledge/admin/evaluation/datasets/${datasetId}/runs`, { pipelineVersion }
    )
  },
  getEvaluationRun(runId: number) {
    return request.get<ApiResponse<EvaluationRun>>(`/knowledge/admin/evaluation/runs/${runId}`)
  },
  listShadowComparisons(kbId: number, status?: string, limit = 50) {
    return request.get<ApiResponse<ShadowComparison[]>>('/knowledge/admin/shadow-comparisons', {
      params: { kbId, status, limit }
    })
  },
  getRankingConfig(kbId: number) {
    return request.get<ApiResponse<RankingConfig>>(`/knowledge/bases/${kbId}/ranking-config`)
  },
  updateRankingConfig(kbId: number, data: RankingConfigUpdate) {
    return request.put<ApiResponse<RankingConfig>>(`/knowledge/bases/${kbId}/ranking-config`, data)
  },
  deleteBase(id: number) {
    return request.delete<ApiResponse<void>>(`/knowledge/bases/${id}`)
  },

  // ---- 文档 ----
  /** GET /api/knowledge/documents?kbId= — 文档列表（knowledge:read） */
  listDocuments(kbId: number) {
    return request.get<ApiResponse<KnowledgeDocument[]>>('/knowledge/documents', { params: { kbId } })
  },
  getDocument(id: number) {
    return request.get<ApiResponse<KnowledgeDocument>>(`/knowledge/documents/${id}`)
  },
  updateDocumentMetadata(id: number, data: KnowledgeDocumentMetadataUpdate) {
    return request.put<ApiResponse<KnowledgeDocument>>(`/knowledge/documents/${id}/metadata`, data)
  },
  listDocumentVersions(id: number) {
    return request.get<ApiResponse<KnowledgeDocumentVersion[]>>(`/knowledge/documents/${id}/versions`)
  },
  createDocumentVersion(id: number, file: File, expectedCurrentVersionId: number, changeNote?: string) {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('expectedCurrentVersionId', String(expectedCurrentVersionId))
    if (changeNote?.trim()) fd.append('changeNote', changeNote.trim())
    return request.post<ApiResponse<KnowledgeDocumentVersion>>(`/knowledge/documents/${id}/versions`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  activateDocumentVersion(id: number, versionId: number, expectedCurrentVersionId: number | null) {
    return request.put<ApiResponse<void>>(`/knowledge/documents/${id}/versions/${versionId}/activate`, {
      expectedCurrentVersionId
    })
  },
  revokeDocumentVersion(id: number, versionId: number) {
    return request.put<ApiResponse<void>>(`/knowledge/documents/${id}/versions/${versionId}/revoke`)
  },
  /** POST /api/knowledge/documents/upload — multipart 上传（knowledge:write）。
   *  opts 为图片/文件知识库扩展（docType/indexMode/manualIndexText/visionModel）；空=后端推断。 */
  uploadDocument(kbId: number, file: File, opts?: UploadOptions) {
    const fd = new FormData()
    fd.append('kbId', String(kbId))
    fd.append('file', file)
    appendUploadOptions(fd, opts)
    return request.post<ApiResponse<KnowledgeDocument>>('/knowledge/documents/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      // 2x 修复：大文件慢上行会撞全局 15s 超时——放宽到 5min（同 assets.ts 上传）
      timeout: 300000
    })
  },
  /** POST /api/knowledge/documents/sheets/preview — 阶段1 预读 Excel sheet 名（picker）。 */
  previewSheets(kbId: number, file: File) {
    const fd = new FormData()
    fd.append('kbId', String(kbId))
    fd.append('file', file)
    return request.post<ApiResponse<SheetPreview>>('/knowledge/documents/sheets/preview', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  /** POST /api/knowledge/documents/upload — 阶段2 Excel picker 上传（复用 tempFileRef + 选定 sheet）。 */
  uploadDocumentSheets(kbId: number, tempFileRef: string, selectedSheets: string[], opts?: UploadOptions) {
    const fd = new FormData()
    fd.append('kbId', String(kbId))
    fd.append('tempFileRef', tempFileRef)
    selectedSheets.forEach(s => fd.append('selectedSheets', s))
    appendUploadOptions(fd, opts)
    return request.post<ApiResponse<KnowledgeDocument>>('/knowledge/documents/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 300000
    })
  },
  /** GET /api/knowledge/documents/{docId}/asset — 取图片/文件原件（KB 成员可读）。 */
  documentAssetUrl(docId: number) {
    return `/api/knowledge/documents/${docId}/asset`
  },
  deleteDocument(id: number) {
    return request.delete<ApiResponse<void>>(`/knowledge/documents/${id}`)
  },
  /** POST /api/knowledge/documents/{docId}/unquarantine — 解除注入隔离并重新解析（knowledge:manage，安全体系 S3） */
  unquarantineDocument(id: number) {
    return request.post<ApiResponse<void>>(`/knowledge/documents/${id}/unquarantine`)
  },
  /** GET /api/knowledge/documents/{docId}/nodes — 文档目录树/原文节点（knowledge:read） */
  listDocumentNodes(docId: number) {
    return request.get<ApiResponse<KnowledgeNode[]>>(`/knowledge/documents/${docId}/nodes`)
  },

  // ---- 权限 ----
  /** GET /api/knowledge/permissions?targetType=&targetId= — 某对象的授权列表（knowledge:write） */
  listPermissions(targetType: string, targetId: number) {
    return request.get<ApiResponse<KnowledgePermission[]>>('/knowledge/permissions', {
      params: { targetType, targetId }
    })
  },
  grantPermission(data: KnowledgePermissionRequest) {
    return request.post<ApiResponse<KnowledgePermission>>('/knowledge/permissions', data)
  },
  revokePermission(id: number) {
    return request.delete<ApiResponse<void>>(`/knowledge/permissions/${id}`)
  },

  // ---- 检索调试 ----
  /** POST /api/knowledge/retrieve — 检索调试，返回完整候选/证据/引用/预算（knowledge:read）
   *  同步生成完整答案（含 LLM 调用），单次可达 15s+，需高于全局 15s timeout，否则 axios 中断 → ERR_ABORTED → 页面空白 */
  retrieve(data: RagRetrieveRequest) {
    return request.post<ApiResponse<RagRetrieveVO>>('/knowledge/retrieve', data, { timeout: 60000 })
  },

  // ---- 检索审计（knowledge:manage，管理员）----
  /** GET /api/knowledge/retrieval-logs — 审计分页（按 userId/kbId/mode/时间范围过滤） */
  pageRetrievalLogs(q: RetrievalLogPageQuery) {
    return request.get<ApiResponse<PageResult<RagRetrievalLog>>>('/knowledge/retrieval-logs', { params: q })
  },
  getRagTraceDetail(traceId: string) {
    return request.get<ApiResponse<RagTraceDetail>>(`/knowledge/retrieval-logs/traces/${traceId}`)
  },
  resolveRagTrace(q: { modelRequestId?: string; usageLogId?: number; auditLogId?: number }) {
    return request.get<ApiResponse<string>>('/knowledge/retrieval-logs/traces/resolve', { params: q })
  },
  /** DELETE /api/knowledge/retrieval-logs/{id} — 删单条 */
  deleteRetrievalLog(id: number) {
    return request.delete<ApiResponse<void>>(`/knowledge/retrieval-logs/${id}`)
  },
  /** DELETE /api/knowledge/retrieval-logs?before=ISO-8601 — 按时间批量清理 */
  deleteRetrievalLogsBefore(before: string) {
    return request.delete<ApiResponse<number>>('/knowledge/retrieval-logs', { params: { before } })
  }
}

/** /ask SSE 流事件（对应后端 StreamEvent：CHUNK/THINKING/CITATION/DONE/ERROR）。 */
export interface AskStreamEvent {
  type: 'CHUNK' | 'THINKING' | 'CITATION' | 'RAG_STATE' | 'DONE' | 'ERROR' | string
  content?: string
}

/**
 * POST /api/knowledge/ask — RAG 流式问答（SSE，knowledge:read）。
 * 异步生成器逐事件 yield（CHUNK 追加答案，CITATION=引用 JSON 数组，DONE 收尾）。
 * 计划5 Step6：projectGroupId 非空 → ask 全链（query 向量化/重排/answer 生成）组池计费。
 */
export async function* askStream(
  query: string,
  kbIds: number[],
  signal?: AbortSignal,
  projectGroupId?: number
): AsyncGenerator<AskStreamEvent> {
  const token = getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN) || ''
  const response = await fetch('/api/knowledge/ask', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
      'Authorization': `Bearer ${token}`
    },
    signal,
    body: JSON.stringify(projectGroupId ? { query, kbIds, projectGroupId } : { query, kbIds })
  })
  if (!response.ok || !response.body) {
    throw new Error(`RAG 问答请求失败: ${response.status}`)
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
        const evt = parseAskSseEvent(part)
        if (evt) yield evt
      }
    }
    buffer += decoder.decode()
    const evt = parseAskSseEvent(buffer)
    if (evt) yield evt
  } finally {
    reader.releaseLock()
  }
}

function parseAskSseEvent(raw: string): AskStreamEvent | null {
  const dataLines = raw
    .split(/\r?\n/)
    .filter(line => line.startsWith('data:'))
    .map(line => line.slice(5).trimStart())
  if (dataLines.length === 0) return null
  const payload = dataLines.join('\n')
  if (!payload) return null
  try { return JSON.parse(payload) as AskStreamEvent } catch { return null }
}
