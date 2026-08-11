// ============================================================
// 积分计费模块 API（价表/阶梯/充值/账单查询/我的钱包）
// 对应后端 /api/billing/**
//   价表(pricing:manage):   GET/POST /billing/pricing, PUT /billing/pricing/{id}
//   阶梯(pricing:manage):   GET/POST /billing/ratio, PUT/DELETE /billing/ratio/{id}
//   充值(points:recharge):  POST /billing/recharge
//   账单(usage:view):       GET /billing/admin/overview|by-user|by-model|by-kind|trend
//   我的钱包(登录用户):     GET /billing/me/wallet, /billing/me/usage
// 用户侧 VO 不含 token/¥（后端 SELECT 列刻意省略，spec §3）。
// ============================================================

import request from './request'
import type { ApiResponse } from './request'
import type { PageResult } from './admin'

// === 类型定义 ===

export type BillingKind = 'CHAT' | 'EMBED' | 'IMAGE' | 'VIDEO'
export type VideoBillingMode = 'TOKEN' | 'SECOND'

/** 新增价表时可选择的 ACTIVE 全局模型。 */
export interface AvailablePricingModelVO {
  providerId: number
  providerName: string
  model: string
  kind: BillingKind
}

/** 价表行（GET /billing/pricing） */
export interface PricingRuleVO {
  id: number
  kind: BillingKind
  providerId: number | null
  model: string | null
  priceInputPerMillion: number | null
  priceOutputPerMillion: number | null
  videoBillingMode: VideoBillingMode | null
  pricePerSecond: number | null
  pricePerImage: number | null
  /** 7x-3：VIDEO 行才有意义（true=有参考视频价），其他 kind 始终 false */
  hasReference: boolean
  effectiveFrom: string
}

/** 价表创建/更新请求（effectiveFrom 空=立即生效） */
export interface PricingRuleRequest {
  kind: BillingKind
  providerId?: number | null
  model?: string | null
  priceInputPerMillion?: number | null
  priceOutputPerMillion?: number | null
  videoBillingMode?: VideoBillingMode | null
  pricePerSecond?: number | null
  pricePerImage?: number | null
  /** 7x-3：视频任务「是否带参考视频」的定价维度（仅 VIDEO kind 有效，其他强制 false） */
  hasReference?: boolean | null
  effectiveFrom?: string | null
}

/** 阶梯比例行（区间 [min,max)，max 空=∞） */
export interface RatioTierVO {
  id: number
  minAmount: number
  maxAmount: number | null
  ratio: number
  effectiveFrom: string
}

/** 7x-2：价表导出/导入行（镜像后端 PricingRuleExportItem） */
export interface PricingRuleExportItem {
  kind: BillingKind
  providerId: number
  /** 仅模板/可读性用，导入时忽略 */
  providerName?: string
  model: string
  /** 仅 VIDEO 有意义；true=带参考视频价，false=无参考/兜底 */
  hasReference?: boolean | null
  priceInputPerMillion?: number | null
  priceOutputPerMillion?: number | null
  videoBillingMode?: VideoBillingMode | null
  pricePerSecond?: number | null
  pricePerImage?: number | null
}

/** 7x-2：价表批量导入结果 */
export interface PricingImportResult {
  created: number
  updated: number
  failed: number
  errors: string[]
}

export interface RatioTierRequest {
  minAmount: number
  maxAmount?: number | null
  ratio: number
  effectiveFrom?: string | null
}

export interface RechargeRequest {
  userId: number
  points: number
  remark?: string
  /** 安全体系 S1 · SEC-FR-121：同一笔表单提交生成一次 UUID，双击/重试同键只到账一次 */
  idempotencyKey?: string
}

/** admin 账单总览 */
export interface UsageOverviewVO {
  totalTokensInput: number
  totalTokensOutput: number
  totalCostYuan: number
  totalPoints: number
  callCount: number
}

/** admin 排行维度行（by-user 的 dimensionKey=user_id，by-model=模型名，by-kind=CHAT 等） */
export interface UsageDimensionVO {
  dimensionKey: string
  tokensInput: number
  tokensOutput: number
  costYuan: number
  points: number
  callCount: number
}

export interface DailyTrendVO {
  day: string
  costYuan: number
  points: number
  callCount: number
}

/** 用户钱包流水行（仅积分维度，无 ¥/token） */
export interface LedgerItemVO {
  createdAt: string
  type: string
  deltaPoints: number
  balanceAfter: number
  remark: string | null
}

export interface UserWalletVO {
  balance: number
  recentLedger: LedgerItemVO[]
}

/** 用户积分明细（无 token/¥） */
export interface UserUsageVO {
  createdAt: string
  model: string | null
  kind: BillingKind
  pointsConsumed: number
  status: string
}

/** admin 调用明细行（逐条 llm_usage_logs，含 token/¥/积分 + username via JOIN） */
export interface UsageDetailVO {
  id: number
  createdAt: string
  userId: number | null
  username: string | null
  displayName: string | null
  model: string | null
  kind: BillingKind
  tokensInput: number
  tokensOutput: number
  costYuan: number
  pointsConsumed: number
  status: string
  errorMsg: string | null
}

/** 调用明细分页查询参数（page/size/userId/model/kind/status/from/to） */
export interface UsageDetailQuery {
  page?: number
  size?: number
  userId?: number
  model?: string
  kind?: BillingKind
  status?: string
  from?: string
  to?: string
}

// === API 函数 ===

export const billingApi = {
  // 价表
  listPricingRules() {
    return request.get<ApiResponse<PricingRuleVO[]>>('/billing/pricing')
  },
  availablePricingModels() {
    return request.get<ApiResponse<AvailablePricingModelVO[]>>('/billing/pricing/available-models')
  },
  createPricingRule(data: PricingRuleRequest) {
    return request.post<ApiResponse<PricingRuleVO>>('/billing/pricing', data)
  },
  updatePricingRule(id: number, data: PricingRuleRequest) {
    return request.put<ApiResponse<PricingRuleVO>>(`/billing/pricing/${id}`, data)
  },
  // 7x-2：导出当前全量价表（blob 触发下载）
  exportPricingRules() {
    return request.get<Blob>('/billing/pricing/export', { responseType: 'blob' })
  },
  // 7x-2：下载填充模板（联动全局供应商未配置模型）
  downloadPricingTemplate() {
    return request.get<Blob>('/billing/pricing/template', { responseType: 'blob' })
  },
  // 7x-2：批量导入价表（upsert，返 created/updated/failed）
  importPricingRules(items: PricingRuleExportItem[]) {
    return request.post<ApiResponse<PricingImportResult>>('/billing/pricing/import', items)
  },
  // 阶梯比例
  listRatioTiers() {
    return request.get<ApiResponse<RatioTierVO[]>>('/billing/ratio')
  },
  createRatioTier(data: RatioTierRequest) {
    return request.post<ApiResponse<RatioTierVO>>('/billing/ratio', data)
  },
  updateRatioTier(id: number, data: RatioTierRequest) {
    return request.put<ApiResponse<RatioTierVO>>(`/billing/ratio/${id}`, data)
  },
  deleteRatioTier(id: number) {
    return request.delete<ApiResponse<void>>(`/billing/ratio/${id}`)
  },
  // 充值
  recharge(data: RechargeRequest) {
    return request.post<ApiResponse<{ userId: number; balanceAfter: number }>>('/billing/recharge', data)
  },
  // admin 账单查询
  overview(params: BillingQueryParams) {
    return request.get<ApiResponse<UsageOverviewVO>>('/billing/admin/overview', { params })
  },
  rankByUser(params: BillingQueryParams) {
    return request.get<ApiResponse<UsageDimensionVO[]>>('/billing/admin/by-user', { params })
  },
  rankByModel(params: BillingQueryParams) {
    return request.get<ApiResponse<UsageDimensionVO[]>>('/billing/admin/by-model', { params })
  },
  rankByKind(params: BillingQueryParams) {
    return request.get<ApiResponse<UsageDimensionVO[]>>('/billing/admin/by-kind', { params })
  },
  dailyTrend(params: BillingQueryParams) {
    return request.get<ApiResponse<DailyTrendVO[]>>('/billing/admin/trend', { params })
  },
  // admin 调用明细（逐条，含 token/¥/积分 + 用户名，分页 + 筛选）
  listUsageDetail(params: UsageDetailQuery) {
    return request.get<ApiResponse<PageResult<UsageDetailVO>>>('/billing/admin/call-log', { params })
  },
  // 我的钱包
  myWallet() {
    return request.get<ApiResponse<UserWalletVO>>('/billing/me/wallet')
  },
  myUsage(params: BillingQueryParams) {
    return request.get<ApiResponse<UserUsageVO[]>>('/billing/me/usage', { params })
  }
}

export interface BillingQueryParams {
  from?: string
  to?: string
  limit?: number
}

// === 标签/色映射 ===

export const KIND_LABEL: Record<BillingKind, string> = {
  CHAT: '文本对话',
  EMBED: '向量嵌入',
  IMAGE: '图片生成',
  VIDEO: '视频生成'
}

export const KIND_TAG_TYPE: Record<BillingKind, 'success' | 'info' | 'warning' | 'error'> = {
  CHAT: 'success',
  EMBED: 'info',
  IMAGE: 'warning',
  VIDEO: 'error'
}

/** 流水类型 → 中文 */
export const LEDGER_TYPE_LABEL: Record<string, string> = {
  CONSUME: '消耗',
  REFUND: '退款',
  ADMIN_GRANT: '管理员发放',
  RECHARGE: '充值'
}

/** 调用状态 → 中文（SUCCESS 成功 / FAILED 失败 / ESTIMATED 预估） */
export const USAGE_STATUS_LABEL: Record<string, string> = {
  SUCCESS: '成功',
  FAILED: '失败',
  ESTIMATED: '预估'
}

/** 调用状态 → NTag 色调 */
export const USAGE_STATUS_TAG_TYPE: Record<string, 'success' | 'error' | 'warning'> = {
  SUCCESS: 'success',
  FAILED: 'error',
  ESTIMATED: 'warning'
}
