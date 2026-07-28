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
 * - MemoryRosterController        花名册 + ACL 配置（/projects/{pid}）
 */
import request from './request'
import type { ApiResponse } from './request'

// ============================ 类型 ============================

/** 波及通知（跨用户：他人撤回 turn 波及我的 summary / 项目删除影响）。 */
export interface MemoryNotificationVO {
  id: number
  type: 'SUMMARY_AFFECTED_BY_RECALL' | 'PROJECT_DELETED_AFFECTED'
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
  status: 'CLEAN' | 'PENDING_CONFLICT' | 'STALE'
  summarizedAt: string | null
  createdAt: string | null
  updatedAt: string | null
}

/** 流水账展示（本人全量，tag label + 项目名回填）。 */
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
  projectIds: number[]
  projectNames: (string | null)[]
  bornPersonal: boolean
  createdAt: string
}

/** raw 流水账（gen_done=false，在线查看无导出）。 */
export interface MemoryRawView {
  id: number
  sessionId: number | null
  direction: 'INPUT' | 'OUTPUT'
  rawContent: string | null
  bornPersonal: boolean
  projectIds: number[]
  createdAt: string
}

/** 标签库行（对外只露 label/subject/topic/usageCount，不露 aliases/anchor）。 */
export interface MemoryTagVO {
  id: number
  subject: string
  topic: string
  label: string
  usageCount: number
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
  departedAuthorNotes: string[]
}

/** 总结入口弹框每行（{个人} ∪ 已加入项目，标未覆盖数 + 自动勾选）。 */
export interface MemoryConsolidationTargetView {
  scopeKind: 'PERSONAL' | 'PROJECT'
  projectId: number | null
  displayName: string
  hasChange: boolean
  uncoveredCount: number
  autoEnabled: boolean
}

export interface MemoryConsolidationScopeRequest {
  scopeKind: 'PERSONAL' | 'PROJECT'
  projectId?: number | null
  autoEnabled?: boolean
  includeDeparted?: boolean
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

/** ACL 矩阵行（reader→target 授权，带 username 回显）。 */
export interface MemoryRecallAclVO {
  readerUserId: number
  readerUsername: string
  targetUserId: number
  targetUsername: string
  createdBy: number
}

export interface MemoryRecallAclRequest {
  readerUserId: number
  targetUserIds: number[]
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
  editTag(id: number, data: { label?: string; addAliases?: string[] }) {
    return request.put<ApiResponse<MemoryTagVO>>(`/chat/memory/tags/${id}`, data)
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

  // ---- 花名册 + ACL 配置（I4）----
  getRoster(projectId: number) {
    return request.get<ApiResponse<MemoryRosterVO[]>>(`/chat/memory/projects/${projectId}/roster`)
  },
  getRecallAcl(projectId: number) {
    return request.get<ApiResponse<MemoryRecallAclVO[]>>(`/chat/memory/projects/${projectId}/recall-acl`)
  },
  putRecallAcl(projectId: number, data: MemoryRecallAclRequest) {
    return request.put<ApiResponse<number>>(`/chat/memory/projects/${projectId}/recall-acl`, data)
  }
}
