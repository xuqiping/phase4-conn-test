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

// === 类型定义 ===

export type BillingKind = 'CHAT' | 'EMBED' | 'IMAGE' | 'VIDEO'
export type VideoBillingMode = 'TOKEN' | 'SECOND'

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

// === API 函数 ===

export const billingApi = {
  // 价表
  listPricingRules() {
    return request.get<ApiResponse<PricingRuleVO[]>>('/billing/pricing')
  },
  createPricingRule(data: PricingRuleRequest) {
    return request.post<ApiResponse<PricingRuleVO>>('/billing/pricing', data)
  },
  updatePricingRule(id: number, data: PricingRuleRequest) {
    return request.put<ApiResponse<PricingRuleVO>>(`/billing/pricing/${id}`, data)
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
