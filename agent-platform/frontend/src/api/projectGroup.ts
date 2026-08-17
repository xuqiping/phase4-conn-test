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

// === API ===

export const projectGroupApi = {
  /** GET /project-groups/mine — 我的组列表（选择器数据源；非成员不可用组池计费的 403 文案在组件层映射）。 */
  mine() {
    return request.get<ApiResponse<ProjectGroupMineVO[]>>('/project-groups/mine')
  }
}
