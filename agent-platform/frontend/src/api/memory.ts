/**
 * 计划12 · 个人记忆新栈 API 客户端（`/api/chat/memory/*`）。
 *
 * 与 legacy `chatApi.*memories*`（`/api/chat/memories`，旧 `user_memories` 表，H 收尾 404）物理隔离。
 * 全部新端点走本文件；前端组件逐步从 chatApi 迁到本 `memoryApi`。
 *
 * 后端 controller 对应：
 * - MemoryNotificationController  通知（轮询 badge + 折叠板）
 * - MemoryGenConfigController     gen 开关矩阵
 * - MemorySummaryController       总结列表
 * - MemoryTurnController          流水账（list / raw / 删 / 批删）
 * - MemoryTagController           标签库
 * - MemoryRecallController        召回（preview / scope GET·PUT）
 * - MemoryConsolidationController 总结触发 / 自动勾选 / 冲突裁决
 * - MemoryRosterController        花名册（/projects/{pid}）
 * - MemoryProjectRuleController   二期 P1 收录规则（/projects/{pid}/rule，FR-001）
 * - MemoryEntryController         二期 P1 条目审核（/projects/{pid}/entries + /entries/{id}，FR-005）
 * - MemoryProjectLinkController   二期 P2 项目授权（/projects/{pid}/links + /links/{id}，FR-101/103）
 *
 * 二期 P1（FR-006，V67）：turns 纯个人域——一期「生命周期折叠板」（departed/deleted 拉取）随
 * turns 四列下线，MemoryLifecycleController 已删；流水账 VO 去 projectIds/projectNames/bornPersonal；
 * 召回结果去 departedAuthorNotes（项目 turns 召回消亡，条目合流取而代之）。
 */
import request from './request'
import type { ApiResponse } from './request'

// ============================ 类型 ============================

/** 波及通知（跨用户：他人撤回 turn 波及我的 summary / 项目删除影响 / 二期 P2 授权申请与结果）。 */
export interface MemoryNotificationVO {
  id: number
  type: 'SUMMARY_AFFECTED_BY_RECALL' | 'PROJECT_DELETED_AFFECTED' | 'LINK_REQUEST' | 'LINK_RESULT'
  refId: number | null
  message: string | null
  createdAt: string
}

/** gen 开关矩阵行（我所在的一个项目 + owner/member 双开关 + effective）。 */
export interface MemoryGenMatrixItemVO {
  projectId: number
  projectName: string
  role: 'OWNER' | 'ADMIN' | 'MEMBER'
  ownerEnabled: boolean
  memberEnabled: boolean
  effective: boolean
}

/** 总结展示（只读自己，tag 信息回填，status 状态徽标）。 */
export interface MemorySummaryVO {
  id: number
  projectId: number | null
  tagId: number | null
  subject: string | null
  topic: string | null
  tagLabel: string | null
  l1Summary: string | null
  l2Detail: string | null
  sourceSummaryId: number | null
  sourceTurnIds: number[]
  /** 二期 P4（FR-301）：PROJECT=项目共享总结（项目资产全员可读）；USER/缺省=个人总结。 */
  scopeOwner?: 'USER' | 'PROJECT'
  /** 二期 P3c：方向 INPUT=输入 / OUTPUT=输出 / BOTH=综合（缺省）。 */
  direction?: 'INPUT' | 'OUTPUT' | 'BOTH'
  /** 二期 P4：条目级总结的 provenance（项目条目 id 集；turn 级总结为空）。 */
  sourceEntryIds?: number[]
  status: 'CLEAN' | 'PENDING_CONFLICT' | 'STALE'
  summarizedAt: string | null
  createdAt: string | null
  updatedAt: string | null
}

/** 流水账展示（本人全量，tag label 回填；二期 P1 纯个人域，无项目挂载/出身标记）。 */
export interface MemoryTurnVO {
  id: number
  sessionId: number | null
  direction: 'INPUT' | 'OUTPUT'
  tagIds: number[]
  tagLabels: (string | null)[]
  l1Summary: string | null
  l2Detail: string | null
  rawContent: string | null
  genDone: boolean
  createdAt: string
  /** 二期 P2：该流水账被收录到的项目（个人域 turn → memory_project_entries 反查，去重）。 */
  indexedProjects?: { projectId: number; name: string }[]
}

/** raw 流水账（gen_done=false，在线查看无导出；二期 P1 纯个人域）。 */
export interface MemoryRawView {
  id: number
  sessionId: number | null
  direction: 'INPUT' | 'OUTPUT'
  rawContent: string | null
  createdAt: string
}

/** 标签库行（对外只露 label/subject/topic/usageCount/needsReview，不露 aliases/anchor）。 */
export interface MemoryTagVO {
  id: number
  subject: string
  topic: string
  label: string
  usageCount: number
  /** V77：大类词表外内容标记待裁决。 */
  needsReview: boolean
}

/** 召回 scope 视图（底栏 + 召回预览用）。 */
export interface MemoryRecallScopeView {
  personalOn: boolean
  projectIds: number[]
  direction: 'INPUT' | 'OUTPUT' | 'BOTH' | null
  relativeDays: number | null
  start: string | null
  end: string | null
  includeDeparted: boolean
  availableProjects: { projectId: number; name: string }[]
}

export interface MemoryRecallScopeRequest {
  personalOn?: boolean
  projectIds?: number[]
  direction?: 'INPUT' | 'OUTPUT' | 'BOTH' | null
  relativeDays?: number | null
  start?: string | null
  end?: string | null
  includeDeparted?: boolean
}

export interface MemoryRecallResult {
  assembledText: string
  summaryCount: number
  turnCount: number
  degraded: boolean
  notes: string[]
  traceId: string | null
}

/** 总结入口弹框每行（{个人} ∪ 已加入项目，标未覆盖数 + 自动勾选）。 */
export interface MemoryConsolidationTargetView {
  scopeKind: 'PERSONAL' | 'PROJECT'
  projectId: number | null
  displayName: string
  hasChange: boolean
  uncoveredCount: number
  autoEnabled: boolean
  /** 二期 P4（FR-302）：true=我是该项目 owner/admin，可写项目共享总结；false=仅可压到自己的总结。 */
  canWriteShared?: boolean
}

export interface MemoryConsolidationScopeRequest {
  scopeKind: 'PERSONAL' | 'PROJECT'
  projectId?: number | null
  autoEnabled?: boolean
  /** 二期 P4（FR-302）：true=项目条目压到「我自己的总结」（成员个人通道，仅自己可见）；
   *  缺省/false=项目共享总结（owner/admin 才允许，后端判）。 */
  toPersonal?: boolean
  /** I4-3 项目总结取数范围：SELF（仅自己，默认）/ SPECIFIC（authorIds）/ ALL（全部可召回人员）。 */
  authorFilter?: 'SELF' | 'SPECIFIC' | 'ALL'
  /** SPECIFIC 时的人员集；后端 ∩ readableAuthors 校验（向量 14 防越权读他人）。 */
  authorIds?: number[]
  /** 方向 INPUT/OUTPUT/BOTH，后端 null/非法 → BOTH。 */
  direction?: 'INPUT' | 'OUTPUT' | 'BOTH'
  /** L10「同步已离开人员」开关；false → 候选剔 DEPARTED（优先级高于人员多选）。 */
  includeDeparted?: boolean
  /** 二期 P3b：只总结这些标签（null/空 = 全部）。PERSONAL 过滤本人标签；PROJECT 过滤条目 tag_ids。 */
  tagIds?: number[]
  /** 二期 P3b：取数时间窗下界（按 turn/entry 创建时间，ISO）。 */
  start?: string
  /** 二期 P3b：取数时间窗上界（ISO）。 */
  end?: string
  /** 二期 P3b：近 N 天（非空优先于 start/end）。 */
  relativeDays?: number
}

export interface MemorySummarizeResult {
  summariesWritten: number
  conflictsCreated: number
  notes: string[]
}

export interface MemoryScopeAutoView {
  scopeKind: 'PERSONAL' | 'PROJECT'
  projectId: number | null
  autoEnabled: boolean
}

/** 新栈待裁决冲突（summary 时序互斥，MemoryConflictVO 简化版）。 */
export interface MemoryPendingConflictVO {
  conflictId: number
  status: string
  askText: string | null
  createdAt: string | null
  /** 二期 P4（FR-303）：true=项目共享总结冲突（裁决权=项目 owner/admin，我因管理身份可见）。 */
  projectShared?: boolean
}

/** 花名册行（含 DEPARTED 已离开，保交接）。 */
export interface MemoryRosterVO {
  userId: number
  username: string
  name: string | null
  role: 'OWNER' | 'ADMIN' | 'MEMBER'
  recallAdmin: boolean
  status: 'ACTIVE' | 'DEPARTED'
  departedAt: string | null
}

// ============================ 二期 P1 · 收录规则 + 条目审核 ============================

/**
 * 项目收录规则视图（二期 P1 · FR-001）。
 * negativeExamples 仅 owner/admin 可见（成员恒 null）；
 * anchorReady=false = embed 失败规则未生效（enabled 强制 false）。
 */
export interface MemoryProjectRuleVO {
  id: number | null
  projectId: number
  ruleText: string | null
  positiveExamples: string[] | null
  negativeExamples: string[] | null
  enabled: boolean
  anchorReady: boolean
  updatedAt: string | null
}

/** 收录规则保存请求（ruleText ≤2000 字；正/负例各 ≤5 条、单条 ≤500 字）。 */
export interface MemoryProjectRuleRequest {
  ruleText: string
  positiveExamples?: string[]
  negativeExamples?: string[]
  enabled: boolean
}

/**
 * 项目记忆条目（二期 P1 · FR-005）。
 * 「为何被收录」= ruleText + confidence；脱敏蒸馏产物，不含原文。
 */
export interface MemoryProjectEntryVO {
  id: number
  projectId: number
  authorUserId: number
  authorName: string | null
  l1Summary: string | null
  l2Detail: string | null
  confidence: number | null
  status: 'ACTIVE' | 'PENDING_REVIEW'
  contentType: 'TEXT' | 'FILE'
  ruleText: string | null
  createdAt: string | null
}

// ============================ 二期 P2 · 项目授权 ============================

/** 项目授权链（二期 P2 · FR-101；带双方项目名+发起/审批人名，列表直显）。 */
export interface MemoryProjectLinkVO {
  id: number
  parentProjectId: number
  parentProjectName: string | null
  childProjectId: number
  childProjectName: string | null
  grantedBy: number
  grantedByName: string | null
  approvedBy: number | null
  approvedByName: string | null
  status: 'PENDING' | 'ACTIVE' | 'REJECTED' | 'REVOKED'
  createdAt: string | null
  approvedAt: string | null
}

// ============================ 二期 P3 · 文件记忆（FR-201~205）============================

/** 聊天附件上传结果（FR-201；一文件一记忆，PROCESSING → worker 异步 READY/FAILED）。 */
export interface MemoryAssetUploadVO {
  memoryId: number
  fileId: string
  originalName: string
  fileKind: string
  size: number
  ingestStatus: string
}

/** 文件记忆行（记忆面板「文件记忆」页签列表数据源，对应 memory_asset_memories）。 */
export interface MemoryAssetMemoryVO {
  id: number
  fileId: string | null
  fileKind: 'IMAGE' | 'DOC' | 'PPT' | 'PDF' | 'AUDIO' | 'VIDEO' | 'OTHER'
  originalName: string | null
  l1Summary: string | null
  l2Detail: string | null
  ingestStatus: 'PROCESSING' | 'READY' | 'FAILED'
  ingestError: string | null
  retryCount: number | null
  weakMemory: boolean | null
  createdAt: string | null
}

/** 文件分块视图（FR-203 文件卡片「展开分块」；pageRef 页码锚点，仅 owner 可读）。 */
export interface FileChunkView {
  chunkNo: number
  pageRef: string | null
  chunkText: string | null
}

/** 召回命中的文件卡片（FR-203；FILE_CARDS 流帧 / 助手消息 metadata.fileCards）。 */
export interface RecalledFileCard {
  memoryId: number
  fileId: string
  originalName: string
  fileKind: string
  chunkCount: number
  weakMemory: boolean
  fileCleaned: boolean
  downloadable: boolean
  l1: string | null
  l2: string | null
}

/** 文件类型中文标签（与后端 MemoryAssetMemory.kindLabel 同源）。 */
export function fileKindLabel(fileKind: string | null | undefined): string {
  switch (fileKind) {
    case 'IMAGE': return '图片'
    case 'PDF': return 'PDF 文档'
    case 'PPT': return 'PPT 演示文稿'
    case 'DOC': return '文档'
    case 'AUDIO': return '音频'
    case 'VIDEO': return '视频'
    default: return '文件'
  }
}

// ============================ 客户端 ============================

export const memoryApi = {
  // ---- 通知（波及 badge 3s 轮询）----
  listNotifications() {
    return request.get<ApiResponse<MemoryNotificationVO[]>>('/chat/memory/notifications')
  },
  countNotifications() {
    return request.get<ApiResponse<number>>('/chat/memory/notifications/count')
  },
  ackNotification(id: number) {
    return request.post<ApiResponse<void>>(`/chat/memory/notifications/${id}/ack`)
  },

  // ---- gen 开关矩阵 ----
  getGenMatrix() {
    return request.get<ApiResponse<MemoryGenMatrixItemVO[]>>('/chat/memory/gen-matrix')
  },
  putGenOwner(projectId: number, genEnabled: boolean) {
    return request.put<ApiResponse<void>>(`/chat/memory/gen-matrix/projects/${projectId}/owner`, { genEnabled })
  },
  putGenMember(projectId: number, genEnabled: boolean) {
    return request.put<ApiResponse<void>>(`/chat/memory/gen-matrix/projects/${projectId}/member`, { genEnabled })
  },

  // ---- 总结列表 ----
  listSummaries(projectId?: number | null) {
    return request.get<ApiResponse<MemorySummaryVO[]>>('/chat/memory/summaries', {
      params: projectId != null ? { projectId } : {}
    })
  },
  /** 二期 P4（FR-301）：项目共享总结（scope_owner=PROJECT，成员咽喉在后端 service 层）。 */
  listProjectSharedSummaries(projectId: number) {
    return request.get<ApiResponse<MemorySummaryVO[]>>(`/chat/memory/summaries/project/${projectId}`)
  },

  // ---- 流水账 ----
  listTurns() {
    return request.get<ApiResponse<MemoryTurnVO[]>>('/chat/memory/turns')
  },
  listRawTurns() {
    return request.get<ApiResponse<MemoryRawView[]>>('/chat/memory/turns/raw')
  },
  deleteTurn(id: number) {
    return request.delete<ApiResponse<void>>(`/chat/memory/turns/${id}`)
  },
  batchDeleteRawTurns(ids: number[]) {
    return request.post<ApiResponse<number>>('/chat/memory/turns/raw/batch-delete', { ids })
  },

  // ---- 标签库 ----
  listTags() {
    return request.get<ApiResponse<MemoryTagVO[]>>('/chat/memory/tags')
  },
  editTag(id: number, data: { label?: string; addAliases?: string[]; accept?: boolean }) {
    return request.put<ApiResponse<MemoryTagVO>>(`/chat/memory/tags/${id}`, data)
  },
  /** P3a：主动新建标签（选定大类 topic + 自填 label + 可选别名；needs_review=false）。 */
  createTag(data: { subject?: string; topic: string; label: string; aliases?: string[] }) {
    return request.post<ApiResponse<MemoryTagVO>>('/chat/memory/tags', data)
  },

  // ---- 召回 scope（底栏持久化 + 预览）----
  getRecallScope() {
    return request.get<ApiResponse<MemoryRecallScopeView>>('/chat/memory/recall/scope')
  },
  putRecallScope(data: MemoryRecallScopeRequest) {
    return request.put<ApiResponse<MemoryRecallScopeView>>('/chat/memory/recall/scope', data)
  },
  previewRecall(query: string, scope?: MemoryRecallScopeRequest) {
    return request.post<ApiResponse<MemoryRecallResult>>('/chat/memory/recall/preview', { query, scope })
  },

  // ---- 总结触发 + 自动勾选 ----
  listConsolidationTargets() {
    return request.get<ApiResponse<MemoryConsolidationTargetView[]>>('/chat/memory/consolidation/targets')
  },
  triggerConsolidation(scopes: MemoryConsolidationScopeRequest[]) {
    return request.post<ApiResponse<MemorySummarizeResult>>('/chat/memory/consolidation/trigger', { scopes })
  },
  getAutoScopes() {
    return request.get<ApiResponse<MemoryScopeAutoView[]>>('/chat/memory/consolidation/auto')
  },
  saveAutoScopes(scopes: MemoryScopeAutoView[]) {
    return request.put<ApiResponse<void>>('/chat/memory/consolidation/auto', { scopes })
  },

  // ---- 冲突裁决（四选项 KEEP_BOTH/KEEP_NEW/KEEP_OLD/DISCARD）----
  listPendingConflicts() {
    return request.get<ApiResponse<MemoryPendingConflictVO[]>>('/chat/memory/conflicts/pending')
  },
  pendingConflictCount() {
    return request.get<ApiResponse<number>>('/chat/memory/conflicts/pending-count')
  },
  resolveConflict(id: number, decision: 'KEEP_BOTH' | 'KEEP_NEW' | 'KEEP_OLD' | 'DISCARD') {
    return request.post<ApiResponse<boolean>>(`/chat/memory/conflicts/${id}/resolve`, { decision })
  },

  // ---- 花名册（I4；recall-acl 二期 P1 下线——一期 ACL 矩阵废弃，FR-006）----
  getRoster(projectId: number) {
    return request.get<ApiResponse<MemoryRosterVO[]>>(`/chat/memory/projects/${projectId}/roster`)
  },

  // ---- 二期 P1 · 收录规则（FR-001；GET 成员可读，PUT 仅 owner/admin）----
  getProjectRule(projectId: number) {
    return request.get<ApiResponse<MemoryProjectRuleVO | null>>(`/chat/memory/projects/${projectId}/rule`)
  },
  putProjectRule(projectId: number, data: MemoryProjectRuleRequest) {
    return request.put<ApiResponse<MemoryProjectRuleVO>>(`/chat/memory/projects/${projectId}/rule`, data)
  },

  // ---- 二期 P1 · 收录条目审核（FR-005；list 成员可见自己产生的，review 仅 owner/admin）----
  listEntries(projectId: number, status?: string) {
    return request.get<ApiResponse<MemoryProjectEntryVO[]>>(`/chat/memory/projects/${projectId}/entries`, {
      params: status ? { status } : {}
    })
  },
  reviewEntry(entryId: number, action: 'approve' | 'reject') {
    return request.post<ApiResponse<void>>(`/chat/memory/entries/${entryId}/review`, { action })
  },
  withdrawEntry(entryId: number) {
    return request.delete<ApiResponse<void>>(`/chat/memory/entries/${entryId}`)
  },

  // ---- 二期 P2 · 项目授权（FR-101/103；发起=child owner，审批=parent owner/admin）----
  createLink(childProjectId: number, parentProjectId: number) {
    return request.post<ApiResponse<MemoryProjectLinkVO>>(`/chat/memory/projects/${childProjectId}/links`, { parentProjectId })
  },
  listMyLinks() {
    return request.get<ApiResponse<MemoryProjectLinkVO[]>>('/chat/memory/links/mine')
  },
  approveLink(linkId: number) {
    return request.post<ApiResponse<void>>(`/chat/memory/links/${linkId}/approve`)
  },
  rejectLink(linkId: number) {
    return request.post<ApiResponse<void>>(`/chat/memory/links/${linkId}/reject`)
  },
  revokeLink(linkId: number) {
    return request.delete<ApiResponse<void>>(`/chat/memory/links/${linkId}`)
  },

  // ---- 二期 P3 · 文件记忆（FR-201~205；端点挂在 ChatController /chat/attachments 下）----
  /** 聊天附件上传（multipart；一文件一记忆 PROCESSING，worker 异步 ingestion）。 */
  uploadAttachment(file: File) {
    const fd = new FormData()
    fd.append('file', file)
    return request.post<ApiResponse<MemoryAssetUploadVO>>('/chat/attachments', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000 // 单文件上限 50MB，放宽上传超时
    })
  },
  /** 我的文件记忆列表（「文件记忆」页签）。 */
  listAttachments() {
    return request.get<ApiResponse<MemoryAssetMemoryVO[]>>('/chat/attachments')
  },
  /** FAILED 手动重试（retry_count 硬卡上限）。 */
  retryAttachment(memoryId: number) {
    return request.post<ApiResponse<void>>(`/chat/attachments/${memoryId}/retry`)
  },
  /** 删除文件记忆（项目 FILE 条目同步失效 + 原文件硬删，FR-204）。 */
  deleteAttachment(memoryId: number) {
    return request.delete<ApiResponse<void>>(`/chat/attachments/${memoryId}`)
  },
  /** 文件分块列表（文件卡片「展开分块」；仅 owner，页码锚点随块返回）。 */
  listAttachmentChunks(memoryId: number) {
    return request.get<ApiResponse<FileChunkView[]>>(`/chat/attachments/${memoryId}/chunks`)
  }
}
