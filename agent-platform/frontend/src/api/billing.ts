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

export type BillingKind = 'CHAT' | 'EMBED' | 'RERANK' | 'IMAGE' | 'VIDEO'
export type VideoBillingMode = 'TOKEN' | 'SECOND'

/** 新增价表时可选择的 ACTIVE 全局模型。 */
export interface AvailablePricingModelVO {
  providerId: number
  providerName: string
  model: string
  kind: BillingKind
  /** VIDEO 只配了一面参考维度时提示本次新增的是哪一面（7x-1） */
  hint?: string
  /** 7x-1（V152）：VIDEO 候选的参考视频维度（true=本候选配「有参考」价行）；非 VIDEO 恒 false */
  hasReference?: boolean
  /** 7x-1（V152）：VIDEO 候选的分辨率槽位（null=通用行；480p/720p/1080p/4k）；非 VIDEO 恒 null */
  resolution?: string | null
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
  /** 7x-1（V152）：VIDEO SECOND 分辨率行（null=通用兜底）；其他行恒 null */
  resolution?: string | null
  /** 7x-2（V153）：VIDEO TOKEN 提交期预估秒价（general/480p/720p/1080p/4k → ¥/秒；仅预检，不计费）；其他行恒 null */
  estPerResolution?: Record<string, number> | null
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
  /** 7x-1（V152）：分辨率定价维度，仅 VIDEO SECOND 有效（480p/720p/1080p/4k；null=通用行） */
  resolution?: string | null
  /** 7x-2（V153）：提交期预估秒价（一行多分辨率参数），仅 VIDEO TOKEN 有效（余额预检用，不参与真实扣费）；general=通用兜底 */
  estPerResolution?: Record<string, number | null> | null
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
  /** 7x-1（V152）：仅 VIDEO SECOND 有意义（480p/720p/1080p/4k；null=通用行），upsert 匹配键之一 */
  resolution?: string | null
  /** 7x-2（V153）：仅 VIDEO TOKEN 有意义——提交期预估秒价（general/分辨率 → ¥/秒，仅预检） */
  estPerResolution?: Record<string, number> | null
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

/** admin 排行维度行（by-user 的 dimensionKey=user_id，by-model=模型名，by-kind=CHAT 等）
 *  D2（20x-1）：by-user 行附 username/displayName；by-model/by-kind 恒 null */
export interface UsageDimensionVO {
  dimensionKey: string
  username?: string | null
  displayName?: string | null
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
  /** B5（Q10=A）：未偿还欠款（>0 时消费全拦，充值自动冲抵）；老后端无此字段=0 */
  debtPoints?: number | null
  recentLedger: LedgerItemVO[]
}

/** 用户积分明细（无 token/¥） */
export interface UserUsageVO {
  createdAt: string
  model: string | null
  kind: BillingKind
  pointsConsumed: number
  status: string
  /** 计划5 Step8：所属项目组 id（个人消耗=null） */
  projectGroupId: number | null
  /** 计划5 Step8：所属项目组名（个人行=null，显「—」） */
  projectGroupName: string | null
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
  /** 9x-1（V160 D4）：缓存命中读 token；null=协议未上报（tokensInput 为未命中输入口径） */
  cachedTokens: number | null
  costYuan: number
  pointsConsumed: number
  status: string
  errorMsg: string | null
  /** 8x Chunk7：请求 traceId（chat 路径关联键，与 audit_logs.trace_id 同值） */
  traceId: string | null
  /** 8x Chunk7：媒体任务 id（媒体路径关联键，与 media 审计行 targetId 对齐） */
  taskId: number | null
  /** 计划5 Step8：所属项目组 id（个人消耗=null） */
  projectGroupId: number | null
  /** 计划5 Step8：所属项目组名（个人行=null，显「—」） */
  projectGroupName: string | null
}

/** 调用明细分页查询参数（page/size/userId/model/kind/status/from/to + 8x Chunk7 traceId/taskId drill-down + 计划5 projectGroupId） */
export interface UsageDetailQuery {
  page?: number
  size?: number
  userId?: number
  model?: string
  kind?: BillingKind
  status?: string
  /** chat 路径 drill-down 反查键（与审计行 traceId 对齐） */
  traceId?: string
  /** 媒体路径 drill-down 反查键（与审计行 targetId=taskId 对齐） */
  taskId?: number
  /** 计划5 Step8：项目组筛选（null=全部含个人行） */
  projectGroupId?: number
  from?: string
  to?: string
}

// === 自助充值支付（7x#3 / 20x#1） ===

export type PaymentStatus = 'PENDING' | 'PAID' | 'FAILED' | 'CLOSED'

/** 支付订单（用户侧；payToken 仅 PENDING 下发） */
export interface PaymentOrderVO {
  id: number
  createdAt: string
  amountYuan: number
  pointsGranted: number
  status: PaymentStatus
  channel: string
  payerAccount: string | null
  expireAt: string | null
  paidAt: string | null
  payToken: string | null
}

export interface CreatePaymentOrderRequest {
  amountYuan: number
  channel: string
  /** 表单会话 UUID——同键同金额返原单，不同金额 409 */
  idemKey?: string
}

/** 我的充值记录行（六字段；未入账状态 balanceAfter=null 显「—」） */
export interface RechargeRecordVO {
  id: number
  createdAt: string
  channel: string
  payerAccount: string | null
  amountYuan: number
  pointsGranted: number
  balanceAfter: number | null
  status: PaymentStatus
}

/** 我的充值记录分页 + 累计条（仅 PAID 计入） */
export interface RechargePageVO {
  page: PageResult<RechargeRecordVO>
  totalPaidAmount: number
  totalPaidPoints: number
}

/** admin 充值记录行（六字段 + userId/username/name；D2：name=昵称/姓名可空） */
export interface AdminRechargeRecordVO extends RechargeRecordVO {
  userId: number
  username: string
  name?: string | null
}

/** admin 充值记录分页 + 当前筛选下 Σ（PAID 口径；筛非 PAID 状态自然归 0） */
export interface AdminRechargePageVO {
  page: PageResult<AdminRechargeRecordVO>
  filteredPaidAmount: number
  filteredPaidPoints: number
}

export interface AdminRechargeQuery {
  page?: number
  size?: number
  userId?: number
  /** 用户名模糊 */
  keyword?: string
  channel?: string
  status?: PaymentStatus
  from?: string
  to?: string
}

/** 用户余额视图行（无钱包行/无充值用户各项为 0；D2：name=昵称/姓名可空） */
export interface UserBalanceRowVO {
  userId: number
  username: string
  name?: string | null
  balancePoints: number
  totalRechargePoints: number
  totalRechargeAmount: number
  lastRechargeAt: string | null
}

/** 用户余额视图分页 + 全平台合计卡（不受 keyword 筛选影响） */
export interface UserBalancePageVO {
  page: PageResult<UserBalanceRowVO>
  totalUsers: number
  sumBalance: number
  sumRechargePoints: number
  sumRechargeAmount: number
}

export type UserBalanceSortBy = 'balance' | 'rechargePoints' | 'rechargeAmount'

export interface UserBalanceQuery {
  page?: number
  size?: number
  keyword?: string
  sortBy?: UserBalanceSortBy
  order?: 'asc' | 'desc'
}

/**
 * D3（20x-2）：项目组分配视图行。
 * quotaLimit null=不限（组长行）；毛额/净额来自组流水 MEMBER_ALLOCATE/MEMBER_RECLAIM 聚合，无流水=0；
 * lastAllocatedAt null=从未分配过。
 */
export interface GroupAllocationRowVO {
  groupId: number
  groupName: string
  userId: number
  username: string
  name?: string | null
  role: 'OWNER' | 'MANAGER' | 'MEMBER'
  quotaLimit: number | null
  usedPoints: number
  remaining: number | null
  totalAllocated: number
  reclaimed: number
  netAllocated: number
  lastAllocatedAt: string | null
}

export interface GroupAllocationQuery {
  page?: number
  size?: number
  /** 用户名/姓名模糊 */
  keyword?: string
  groupId?: number
}

/**
 * D4（20x-3）：组池对账异常组行（仅返回不平组）。
 * expected=划入净额+退款−消耗；diff=balance−expected（正=池里钱比流水多）；
 * crossDiff=组账本净额−个人账本 GROUP 腿净流出（双账本交叉，0=一致）。
 */
export interface GroupReconcileRowVO {
  groupId: number
  groupName: string
  netAllocated: number
  consumed: number
  refunded: number
  expected: number
  balance: number
  diff: number
  crossDiff: number
}

/** D4+7x-1：组池对账总览（totals/balanced 跟随响应口径：全平台/单组） */
export interface GroupReconcileVO {
  balanced: boolean
  totals: {
    netAllocated: number
    consumed: number
    refunded: number
    balance: number
    diff: number
    crossDiff: number
  }
  abnormalGroups: GroupReconcileRowVO[]
  /** 7x-1 下钻：groupId/includeAll 请求时=口径内全组行（含平组）；默认请求为 null */
  groups?: GroupReconcileRowVO[] | null
}

/** 支付状态 → 中文 */
export const PAYMENT_STATUS_LABEL: Record<PaymentStatus, string> = {
  PENDING: '待支付',
  PAID: '已支付',
  FAILED: '支付失败',
  CLOSED: '已关闭'
}

/** 支付状态 → NTag 色调 */
export const PAYMENT_STATUS_TAG_TYPE: Record<PaymentStatus, 'info' | 'success' | 'error' | 'warning'> = {
  PENDING: 'info',
  PAID: 'success',
  FAILED: 'error',
  CLOSED: 'warning'
}

/** 支付渠道 → 中文 */
export const PAYMENT_CHANNEL_LABEL: Record<string, string> = {
  MOCK: '模拟支付',
  ALIPAY: '支付宝',
  WECHAT: '微信支付'
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
  /** 删除价表行（配错模型/价格的清理入口；历史账单不受影响） */
  deletePricingRule(id: number) {
    return request.delete<ApiResponse<null>>(`/billing/pricing/${id}`)
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
  /** 7x#2：admin 充值页用户下拉选项（账号+昵称/姓名，远端搜索，限 20 条） */
  rechargeUserOptions(keyword = '') {
    return request.get<ApiResponse<{ userId: number; username: string; name: string | null }[]>>(
      '/billing/admin/user-options', { params: { keyword } })
  },

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
  /** 计划5 Step8：账单页「项目组」筛选下拉数据源（usage:view） */
  projectGroupOptions() {
    return request.get<ApiResponse<ProjectGroupOptionVO[]>>('/billing/admin/project-group-options')
  },
  // 我的钱包
  myWallet() {
    return request.get<ApiResponse<UserWalletVO>>('/billing/me/wallet')
  },
  myUsage(params: BillingQueryParams) {
    return request.get<ApiResponse<UserUsageVO[]>>('/billing/me/usage', { params })
  },
  // ---- 自助充值支付（7x#3，/billing/payment/**） ----
  /** 当前可用支付渠道（空数组=隐藏充值入口） */
  paymentChannels() {
    return request.get<ApiResponse<string[]>>('/billing/payment/channels')
  },
  /** 下单（idemKey=表单会话 UUID，防双击/重试双扣） */
  createPaymentOrder(data: CreatePaymentOrderRequest) {
    return request.post<ApiResponse<PaymentOrderVO>>('/billing/payment/orders', data)
  },
  /** 查单（PENDING 单响应带 payToken 供续付） */
  getPaymentOrder(id: number) {
    return request.get<ApiResponse<PaymentOrderVO>>(`/billing/payment/orders/${id}`)
  },
  cancelPaymentOrder(id: number) {
    return request.post<ApiResponse<null>>(`/billing/payment/orders/${id}/cancel`)
  },
  /** mock 收银台模拟支付（仅 mock-enabled 环境可用；走真实回调链路） */
  mockTrigger(data: { orderId: number; success: boolean; payerAccount?: string }) {
    return request.post<ApiResponse<{ orderId: number; accepted: boolean }>>('/billing/payment/mock/trigger', data)
  },
  /** 我的充值记录（六字段分页 + 累计条） */
  myRecharges(params: { page?: number; size?: number }) {
    return request.get<ApiResponse<RechargePageVO>>('/billing/payment/me/recharges', { params })
  },
  // ---- admin 充值/余额（20x#1，usage:view） ----
  /** admin 充值记录（分页 + 筛选 + 当前筛选下 Σ） */
  adminRecharges(params: AdminRechargeQuery) {
    return request.get<ApiResponse<AdminRechargePageVO>>('/billing/admin/recharges', { params })
  },
  /** admin 用户余额视图（分页 + 排序 + 全平台合计卡） */
  adminUserBalances(params: UserBalanceQuery) {
    return request.get<ApiResponse<UserBalancePageVO>>('/billing/admin/user-balances', { params })
  },
  /** D3（20x-2）：admin 项目组分配视图（每用户每组 quota/used/剩余 + 累计被分配/净额） */
  adminGroupAllocations(params: GroupAllocationQuery) {
    return request.get<ApiResponse<PageResult<GroupAllocationRowVO>>>('/billing/admin/group-allocations', { params })
  },
  /** D4（20x-3）+7x-1 下钻：groupId=单组行+totals=该组；includeAll=全组行含平组；都不传=仅异常组 */
  adminGroupReconcile(params?: { groupId?: number | null; includeAll?: boolean }) {
    return request.get<ApiResponse<GroupReconcileVO>>('/billing/admin/group-reconcile', { params })
  },
  // ---- admin 支付渠道配置（7x 追加，payment:config） ----
  /** 两渠道脱敏配置状态（tails 永不含明文） */
  adminPaymentChannels() {
    return request.get<ApiResponse<PaymentChannelConfigVO[]>>('/billing/admin/payment-channels')
  },
  /** 保存渠道密钥（merge：留空字段保持原值；整体 AES 落库） */
  savePaymentChannelConfig(channel: string, config: Record<string, string>) {
    return request.put<ApiResponse<null>>(`/billing/admin/payment-channels/${channel}`, config)
  }
}

/** 支付渠道配置·脱敏视图（admin；tails 每字段形如 "****3f2a"） */
export interface PaymentChannelConfigVO {
  channel: 'ALIPAY' | 'WECHAT'
  configured: boolean
  tails: Record<string, string>
  updatedAt: string | null
  updatedBy: number | null
}

/** 渠道表单字段说明（前端表单顺序/标签真相源，与后端 REQUIRED_KEYS 对齐） */
export const PAYMENT_CHANNEL_FIELDS: Record<string, { key: string; label: string; secret: boolean }[]> = {
  ALIPAY: [
    { key: 'appId', label: 'APPID（开放平台我的应用）', secret: false },
    { key: 'privateKey', label: '应用私钥（密钥工具生成）', secret: true },
    { key: 'alipayPublicKey', label: '支付宝公钥（上传应用公钥后页面显示）', secret: true }
  ],
  WECHAT: [
    { key: 'mchId', label: '商户号（10 位数字）', secret: false },
    { key: 'appId', label: 'AppID（服务号 wx... 开头）', secret: false },
    { key: 'apiV3Key', label: 'APIv3 密钥（商户平台自设 32 位）', secret: true }
  ]
}

/** 计划5 Step8：账单筛选下拉的项目组选项 */
export interface ProjectGroupOptionVO {
  id: number
  name: string
}

export interface BillingQueryParams {
  from?: string
  to?: string
  limit?: number
  /** 计划5 Step8：me/usage 可选组筛选（只看我在该组的消耗行；不传=全部） */
  projectGroupId?: number
}

// === 标签/色映射 ===

export const KIND_LABEL: Record<BillingKind, string> = {
  CHAT: '文本对话',
  EMBED: '向量嵌入',
  RERANK: '知识库重排',
  IMAGE: '图片生成',
  VIDEO: '视频生成'
}

export const KIND_TAG_TYPE: Record<BillingKind, 'success' | 'info' | 'warning' | 'error'> = {
  CHAT: 'success',
  EMBED: 'info',
  RERANK: 'info',
  IMAGE: 'warning',
  VIDEO: 'error'
}

/** 流水类型 → 中文 */
export const LEDGER_TYPE_LABEL: Record<string, string> = {
  CONSUME: '消耗',
  REFUND: '退款',
  ADMIN_GRANT: '管理员发放',
  RECHARGE: '充值',
  // 计划5 组划拨 + B5 欠款兜底（V157）
  GROUP_ALLOCATE: '划入项目组',
  GROUP_RECLAIM: '从项目组回收',
  DEBT: '欠款挂账',
  DEBT_REPAY: '欠款冲抵'
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
