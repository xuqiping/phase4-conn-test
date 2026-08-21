// ============================================================
// 项目组 API（计划5 · 项目组与积分划拨）
// 对应后端 /api/project-groups/**
//   我的组（登录用户）: GET /project-groups/mine — 我建的+我在的（选择器数据源）
// 后端 ProjectGroupController：管理端点（建组/成员/划拨）另走 admin 视图，此处先只封装用户侧。
// ============================================================

import request from './request'
import type { ApiResponse } from './request'

// === 类型定义 ===

/** 我的组列表行（对应后端 ProjectGroupMineVO）。 */
export interface ProjectGroupMineVO {
  id: number
  name: string
  description: string | null
  ownerUserId: number
  /** OWNER=我建的组长 / MANAGER=组管理（管人不管钱） / MEMBER=普通成员（V139） */
  myRole: 'OWNER' | 'MANAGER' | 'MEMBER'
  /** 组池余额（积分） */
  balancePoints: number
  /** 组长给我配的积分限额（null=不限） */
  myQuota: number | null
  /** 我已消耗（限额口径） */
  myUsed: number
  memberCount: number
  createdAt: string
}

/** 组成员行（ProjectGroupMemberVO，详情/总览共用；V139 起带角色/功能开关/可见性覆盖）。 */
export interface ProjectGroupMemberVO {
  userId: number
  username: string | null
  displayName: string | null
  owner: boolean
  /** OWNER/MANAGER/MEMBER（V139；OWNER 恒为组长行） */
  role: 'OWNER' | 'MANAGER' | 'MEMBER'
  /** 可用功能开关（V139）：null=不限，[]=全禁，否则为 CHAT/EMBED/RERANK/IMAGE/VIDEO 子集 */
  allowedKinds: string[] | null
  /** 成员级可见性覆盖（V139）：{kind: 'OWN'|'ALL'}，null/缺 key=跟随组设置 */
  memberVisibilityOverrides: Record<string, 'OWN' | 'ALL'> | null
  quotaLimitPoints: number | null
  usedPoints: number
  joinedAt: string
}

/** 组详情（ProjectGroupDetailVO）。 */
export interface ProjectGroupDetailVO {
  id: number
  name: string
  description: string | null
  ownerUserId: number
  ownerUsername: string | null
  balancePoints: number
  /** 在途占用（提交未结算任务的预估和）。 */
  inflightPoints: number
  members: ProjectGroupMemberVO[]
  createdAt: string
  /** 成员产出可见性（17x#2，OWN/ALL） */
  memberOutputVisibility: 'OWN' | 'ALL'
  /** 按模块可见性覆盖 JSON 串（17x#2；JSON.parse 后 {kind: 'OWN'|'ALL'}） */
  moduleVisibilityOverrides: string | null
  /** 公共池招募开关（17x#4） */
  publicPool: boolean
}

/** 组池流水行（ProjectGroupLedgerRowVO）。 */
export interface ProjectGroupLedgerRowVO {
  id: number
  createdAt: string
  actorUserId: number | null
  actorUsername: string | null
  /** ALLOCATE/RECLAIM/CONSUME/REFUND/ADMIN_ADJUST/BACKSTOP */
  type: string
  deltaPoints: number
  balanceAfter: number
  refType: string | null
  refId: string | null
  remark: string | null
}

/** 组长总览（ProjectGroupOverviewVO = 详情 + 流水分页）。 */
export interface ProjectGroupOverviewVO {
  group: ProjectGroupDetailVO
  ledger: PageResult<ProjectGroupLedgerRowVO>
}

/** 通用分页结构（与后端 PageResult 对齐）。 */
export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

/** 组产出行（ProjectGroupOutputVO；媒体概要仅媒体任务行有值）。 */
export interface ProjectGroupOutputVO {
  id: number
  createdAt: string
  userId: number | null
  username: string | null
  /** CHAT/EMBED/RERANK/IMAGE/VIDEO */
  kind: string
  model: string | null
  pointsConsumed: number
  /** SUCCESS/FAILED/ESTIMATED（usage 侧） */
  status: string
  taskId: number | null
  mediaStatus: string | null
  mediaPrompt: string | null
  /** 视频产物 fileId（17x#1；按组可见性可见时才有值） */
  resultFileId: string | null
  /** 图片产物 fileId 列表（17x#1） */
  imageFileIds: string[] | null
}

/** 组邀请行（ProjectGroupInviteVO，17x#3）。 */
export interface ProjectGroupInviteVO {
  id: number
  groupId: number
  groupName: string | null
  inviterUserId: number
  inviterName: string | null
  inviteeUserId: number
  inviteeName: string | null
  quotaLimitPoints: number | null
  /** PENDING/ACCEPTED/DECLINED/CANCELED */
  status: string
  createdAt: string
  decidedAt: string | null
}

/** 公共池组行（ProjectGroupPoolItemVO，17x#4）。 */
export interface ProjectGroupPoolItemVO {
  id: number
  name: string
  description: string | null
  ownerUsername: string | null
  memberCount: number
  publishedAt: string | null
  alreadyMember: boolean
  /** 我在该组的申请状态（PENDING/APPROVED/REJECTED/REVOKED；无申请 null） */
  myRequestStatus: string | null
}

/** 公共池入组申请行（ProjectGroupJoinRequestVO，17x#4）。 */
export interface ProjectGroupJoinRequestVO {
  id: number
  groupId: number
  groupName: string | null
  userId: number
  username: string | null
  message: string | null
  /** PENDING/APPROVED/REJECTED/REVOKED */
  status: string
  createdAt: string
  decidedAt: string | null
}

/** 组产出筛选参数。 */
export interface GroupOutputsQuery {
  memberUserId?: number
  kind?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

// === API ===

export const projectGroupApi = {
  /** GET /project-groups/mine — 我的组列表（选择器数据源；非成员不可用组池计费的 403 文案在组件层映射）。 */
  mine() {
    return request.get<ApiResponse<ProjectGroupMineVO[]>>('/project-groups/mine')
  },

  /** GET /project-groups/{id}/overview — 组长总览（组详情+组池流水分页；成员 403 走管理页口径）。 */
  overview(id: number, page = 1, size = 10) {
    return request.get<ApiResponse<ProjectGroupOverviewVO>>(`/project-groups/${id}/overview`, {
      params: { page, size }
    })
  },

  /** GET /project-groups/{id}/outputs — 组产出列表（组长全员可筛 / 成员仅自己）。 */
  outputs(id: number, q: GroupOutputsQuery = {}) {
    return request.get<ApiResponse<PageResult<ProjectGroupOutputVO>>>(`/project-groups/${id}/outputs`, {
      params: q
    })
  },

  // ==================== Step7 管理动作（Step3 后端端点，组长/admin） ====================

  /** POST /project-groups — 建组（返回组 id）。 */
  create(name: string, description?: string) {
    return request.post<ApiResponse<number>>('/project-groups', { name, description })
  },

  /** GET /project-groups/{id}/members/candidates — 候选用户搜索。 */
  candidates(id: number, keyword = '') {
    return request.get<ApiResponse<{ userId: number; username: string }[]>>(
      `/project-groups/${id}/members/candidates`, { params: { keyword } })
  },

  /** POST /project-groups/{id}/members — 加成员（quota null=不限）。 */
  addMember(id: number, userId: number, quotaLimitPoints: number | null) {
    return request.post<ApiResponse<null>>(`/project-groups/${id}/members`,
      { userId, quotaLimitPoints })
  },

  /** DELETE /project-groups/{id}/members/{uid} — 移除成员。 */
  removeMember(id: number, uid: number) {
    return request.delete<ApiResponse<null>>(`/project-groups/${id}/members/${uid}`)
  },

  /** PUT /project-groups/{id}/members/{uid}/quota — 调限额（null=不限）。 */
  updateQuota(id: number, uid: number, quotaLimitPoints: number | null) {
    return request.put<ApiResponse<null>>(`/project-groups/${id}/members/${uid}/quota`,
      { quotaLimitPoints })
  },

  /** POST /project-groups/{id}/members/{uid}/reset-used — 重置成员已用。 */
  resetUsed(id: number, uid: number) {
    return request.post<ApiResponse<null>>(`/project-groups/${id}/members/${uid}/reset-used`)
  },

  // ==================== 17x#2 成员权限（V139）：角色任免 / 功能开关 / 成员级可见性 ====================

  /** PUT /project-groups/{id}/members/{uid}/role — 任免角色（仅组长；MEMBER↔MANAGER）。 */
  updateMemberRole(id: number, uid: number, role: 'MEMBER' | 'MANAGER') {
    return request.put<ApiResponse<null>>(`/project-groups/${id}/members/${uid}/role`, { role })
  },

  /** PUT /project-groups/{id}/members/{uid}/kinds — 功能开关（组长/管理；null=不限，[]=全禁）。 */
  updateMemberKinds(id: number, uid: number, allowedKinds: string[] | null) {
    return request.put<ApiResponse<null>>(`/project-groups/${id}/members/${uid}/kinds`, { allowedKinds })
  },

  /** PUT /project-groups/{id}/members/{uid}/visibility-overrides — 成员级可见性覆盖（组长/管理；{}=清空跟随组设置）。 */
  updateMemberVisibility(id: number, uid: number, overrides: Record<string, 'OWN' | 'ALL'>) {
    return request.put<ApiResponse<null>>(`/project-groups/${id}/members/${uid}/visibility-overrides`, { overrides })
  },

  /** POST /project-groups/{id}/allocate — 划拨（个人→组池）。 */
  allocate(id: number, points: number, remark?: string) {
    return request.post<ApiResponse<unknown>>(`/project-groups/${id}/allocate`, { points, remark })
  },

  /** POST /project-groups/{id}/reclaim — 回收（组池→个人）。 */
  reclaim(id: number, points: number, remark?: string) {
    return request.post<ApiResponse<unknown>>(`/project-groups/${id}/reclaim`, { points, remark })
  },

  // ==================== 17x#3：邀请同意（V138） ====================

  /** POST /project-groups/{id}/members — 现语义=发邀请（被邀请人同意后才入组；quota null=不限）。 */
  inviteMember(id: number, userId: number, quotaLimitPoints: number | null) {
    return request.post<ApiResponse<null>>(`/project-groups/${id}/members`,
      { userId, quotaLimitPoints })
  },

  /** GET /project-groups/{id}/invites — 组邀请列表（组长，全状态）。 */
  listInvites(id: number) {
    return request.get<ApiResponse<ProjectGroupInviteVO[]>>(`/project-groups/${id}/invites`)
  },

  /** GET /project-groups/invites/mine — 我的待处理邀请。 */
  myInvites() {
    return request.get<ApiResponse<ProjectGroupInviteVO[]>>('/project-groups/invites/mine')
  },

  /** POST /project-groups/invites/{inviteId}/accept — 接受邀请（入组）。 */
  acceptInvite(inviteId: number) {
    return request.post<ApiResponse<null>>(`/project-groups/invites/${inviteId}/accept`)
  },

  /** POST /project-groups/invites/{inviteId}/decline — 拒绝邀请。 */
  declineInvite(inviteId: number) {
    return request.post<ApiResponse<null>>(`/project-groups/invites/${inviteId}/decline`)
  },

  /** DELETE /project-groups/invites/{inviteId} — 取消邀请（组长）。 */
  cancelInvite(inviteId: number) {
    return request.delete<ApiResponse<null>>(`/project-groups/invites/${inviteId}`)
  },

  // ==================== 17x#2：产出可见性设置（V138） ====================

  /** PUT /project-groups/{id}/visibility — 更新成员产出可见性（OWN/ALL + 按模块覆盖）。 */
  updateVisibility(id: number, payload: {
    memberOutputVisibility?: 'OWN' | 'ALL'
    moduleVisibilityOverrides?: Record<string, 'OWN' | 'ALL'>
  }) {
    return request.put<ApiResponse<null>>(`/project-groups/${id}/visibility`, payload)
  },

  // ==================== 17x#4：公共池招募（V138） ====================

  /** GET /project-groups/pool — 公共池列表（全平台）。 */
  pool() {
    return request.get<ApiResponse<ProjectGroupPoolItemVO[]>>('/project-groups/pool')
  },

  /** POST /project-groups/{id}/publish — 推入公共池（组长）。 */
  publish(id: number) {
    return request.post<ApiResponse<null>>(`/project-groups/${id}/publish`)
  },

  /** DELETE /project-groups/{id}/publish — 撤出公共池（组长；级联 PENDING 申请失效）。 */
  unpublish(id: number) {
    return request.delete<ApiResponse<null>>(`/project-groups/${id}/publish`)
  },

  /** POST /project-groups/{id}/join-requests — 申请加入（本人）。 */
  applyJoin(id: number, message?: string) {
    return request.post<ApiResponse<null>>(`/project-groups/${id}/join-requests`, { message })
  },

  /** GET /project-groups/{id}/join-requests — 组的申请列表（组长审批）。 */
  listJoinRequests(id: number) {
    return request.get<ApiResponse<ProjectGroupJoinRequestVO[]>>(`/project-groups/${id}/join-requests`)
  },

  /** GET /project-groups/join-requests/mine — 我的申请。 */
  myJoinRequests() {
    return request.get<ApiResponse<ProjectGroupJoinRequestVO[]>>('/project-groups/join-requests/mine')
  },

  /** PUT /project-groups/join-requests/{rid}/decision — 审批（组长）。 */
  decideJoinRequest(rid: number, approve: boolean) {
    return request.put<ApiResponse<null>>(`/project-groups/join-requests/${rid}/decision`, { approve })
  },

  /** DELETE /project-groups/join-requests/{rid} — 取消我的待审批申请。 */
  cancelJoinRequest(rid: number) {
    return request.delete<ApiResponse<null>>(`/project-groups/join-requests/${rid}`)
  }
}
