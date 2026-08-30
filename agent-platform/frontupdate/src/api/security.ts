// ============================================================
// 安全管理 API（11x 加固 P4-C12）
// 后端：/api/security/events + /api/security/ip + /api/security/rules
// 三权：security:event:read / security:ban:manage / security:rule:manage
// ============================================================

import request from './request'
import type { ApiResponse } from './request'
import type { PageResult } from './admin'

/** 安全事件行（对应后端 SecurityEvent） */
export interface SecurityEventVO {
  id: number
  eventType: string
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  userId: number | null
  clientIp: string | null
  traceId: string | null
  ruleId: string | null
  detailJson: string | null
  autoAction: string | null
  handled: boolean
  handledBy: string | null
  handledAt: string | null
  createdAt: string
  /** 13x-1：后端按 userId 批量回填的账号名（展示用，非表列） */
  username?: string | null
}

/** 事件筛选 */
export interface SecurityEventQuery {
  eventType?: string
  severity?: string
  handled?: boolean
  page: number
  size: number
}

/** 24h 统计（风险大盘） */
export interface SecurityStats {
  bySeverity: Array<{ severity: string; cnt: number }>
  byType: Array<{ event_type?: string; eventType?: string; cnt: number }>
  unhandled: number
}

/** IP 黑名单行（对应后端 IpBlacklist 实体） */
export interface IpBlacklistVO {
  id: number
  ip: string
  source: string | null
  reason: string | null
  /** 到期时间（null=永久） */
  bannedUntil: string | null
  createdBy: string | null
  createdAt: string
}

export function listSecurityEvents(query: SecurityEventQuery) {
  return request.get<ApiResponse<PageResult<SecurityEventVO>>>('/security/events', { params: query })
}

export function unhandledEventCount() {
  return request.get<ApiResponse<number>>('/security/events/unhandled-count')
}

export function ackSecurityEvent(id: number) {
  return request.post<ApiResponse<null>>(`/security/events/${id}/ack`)
}

export function batchDeleteSecurityEvents(ids: number[]) {
  return request.delete<ApiResponse<number>>('/security/events/batch', { data: ids })
}

export function securityStats() {
  return request.get<ApiResponse<SecurityStats>>('/security/events/stats')
}

export function listIpBlacklist(page = 1, size = 50) {
  return request.get<ApiResponse<PageResult<IpBlacklistVO>>>('/security/ip/list', { params: { page, size } })
}

export function blockIp(ip: string, reason: string, permanent: boolean) {
  return request.post<ApiResponse<null>>('/security/ip/block', { ip, reason, permanent })
}

export function unblockIp(ip: string) {
  return request.post<ApiResponse<null>>('/security/ip/unblock', { ip })
}

export function listSecurityRules() {
  return request.get<ApiResponse<Record<string, string>>>('/security/rules')
}

export function updateSecurityRule(key: string, value: string) {
  return request.put<ApiResponse<null>>(`/security/rules/${key}`, { value })
}

/** 事件类型中文（与后端 DingtalkCardBuilder 同源口径） */
export const EVENT_TYPE_CN: Record<string, string> = {
  LOGIN_BRUTE_FORCE: '登录暴破',
  CREDENTIAL_STUFFING: '撞库攻击',
  SQLI_PROBE: 'SQL注入探测',
  XSS_PROBE: 'XSS探测',
  PATH_PROBE: '路径穿越探测',
  RATE_BURST: '频率突发',
  IP_BLOCKED_HIT: '黑名单IP命中',
  IDOR_PROBE: '越权探测',
  IMPOSSIBLE_TRAVEL: '异地登录',
  OFF_HOURS_SENSITIVE: '凌晨敏感操作',
  DATA_EXFIL: '数据外带',
  POINTS_ABUSE: '积分滥用',
  MEDIA_ABUSE: '媒体滥用',
  PROMPT_INJECTION: 'Prompt注入',
  KB_INJECTION: 'KB文档注入隔离',
  PROMPT_LEAK: 'Prompt泄露遮蔽',
  LLM_SESSION_CAP: '会话Token超限',
  TOKEN_REUSE: 'Token盗号疑似',
  PRIVILEGE_CHANGE: '特权变更',
}

export const SEVERITY_CN: Record<string, string> = {
  LOW: '低危',
  MEDIUM: '中危',
  HIGH: '高危',
  CRITICAL: '危急',
}

/** 自动处置动作中文（13x-1：原 autoAction 裸英文码 → 中文）。 */
export const AUTO_ACTION_CN: Record<string, string> = {
  NONE: '仅记录告警',
  IP_BLOCKED: '封禁来源IP',
  ACCOUNT_LOCKED: '锁定账号',
  ACCOUNT_BANNED: '封禁账号',
  TOKEN_REVOKED: '吊销登录凭证',
}
