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
  /** OWNER=我建的组长 / MEMBER=我加入的成员 */
  myRole: 'OWNER' | 'MEMBER'
  /** 组池余额（积分） */
  balancePoints: number
  /** 组长给我配的积分限额（null=不限） */
  myQuota: number | null
  /** 我已消耗（限额口径） */
  myUsed: number
  memberCount: number
  createdAt: string
}

/** 组成员行（ProjectGroupMemberVO，详情/总览共用）。 */
export interface ProjectGroupMemberVO {
  userId: number
  username: string | null
  displayName: string | null
  isOwner: boolean
  quotaLimitPoints: number | null
  usedPoints: number
  createdAt: string
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

  /** POST /project-groups/{id}/allocate — 划拨（个人→组池）。 */
  allocate(id: number, points: number, remark?: string) {
    return request.post<ApiResponse<unknown>>(`/project-groups/${id}/allocate`, { points, remark })
  },

  /** POST /project-groups/{id}/reclaim — 回收（组池→个人）。 */
  reclaim(id: number, points: number, remark?: string) {
    return request.post<ApiResponse<unknown>>(`/project-groups/${id}/reclaim`, { points, remark })
  }
}
