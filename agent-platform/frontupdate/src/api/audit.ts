// ============================================================
// 审计日志 API（日志系统 LOG-FR-12）
// 对应后端 GET /api/audit/logs —— 权限 system:audit:read（无权限 403）
// 数据已脱敏（入库前 LogMasker + 200 字符截断），前端原样展示。
// ============================================================

import request from './request'
import type { ApiResponse } from './request'
import type { PageResult } from './admin'

/** 审计日志行（对应后端 AuditLogVO） */
export interface AuditLogVO {
  id: number
  createdAt: string
  traceId: string | null
  userId: number | null
  username: string | null
  module: string
  action: string
  targetType: string | null
  targetId: string | null
  detailJson: string | null
  clientIp: string | null
  userAgent: string | null
  result: 'SUCCESS' | 'FAIL'
  /** 模块中文（后端 AuditLabelDictionary 翻译，显示用） */
  moduleLabel: string | null
  /** 动作中文（后端 AuditLabelDictionary 翻译，显示用） */
  actionLabel: string | null
  /** 修复III E2（12x#3）：操作人当前姓名（详情层 JOIN 现值；username 列=写入时快照不随改名漂） */
  operatorName: string | null
  /** 修复III E2（12x#3）：操作人备注（如「A 班」，认人辅助） */
  operatorRemark: string | null
}

/** 查询筛选条件（全部可选；时间段为 ISO 字符串） */
export interface AuditLogQuery {
  userId?: number
  username?: string
  module?: string
  action?: string
  result?: string
  traceId?: string
  startTime?: string
  endTime?: string
  page?: number
  size?: number
}

export const auditApi = {
  /** 分页查询审计日志（size 后端封顶 100） */
  list(params: AuditLogQuery) {
    return request.get<ApiResponse<PageResult<AuditLogVO>>>('/audit/logs', { params })
  }
}
