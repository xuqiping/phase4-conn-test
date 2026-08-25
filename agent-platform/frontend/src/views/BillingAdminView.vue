<template>
  <div class="billing-admin">
    <n-card title="账单总览">
      <template #header-extra>
        <n-date-picker
          v-model:value="range"
          type="daterange"
          clearable
          :update-value-on-close="true"
          style="width: 280px"
        />
        <n-button style="margin-left: 8px" @click="loadAll">查询</n-button>
      </template>

      <div v-if="!canView" class="billing-admin__noperm">
        <n-empty description="无 usage:view 权限" />
      </div>
      <template v-else>
        <n-grid :cols="5" :x-gap="12" responsive="screen">
          <n-gi><n-card size="small"><n-statistic label="调用次数" :value="overview?.callCount ?? 0" /></n-card></n-gi>
          <n-gi><n-card size="small"><n-statistic label="输入 Token" :value="overview?.totalTokensInput ?? 0" /></n-card></n-gi>
          <n-gi><n-card size="small"><n-statistic label="输出 Token" :value="overview?.totalTokensOutput ?? 0" /></n-card></n-gi>
          <n-gi><n-card size="small"><n-statistic label="真实金额 ¥" :value="fmtNum(overview?.totalCostYuan)" /></n-card></n-gi>
          <n-gi><n-card size="small"><n-statistic label="消耗积分" :value="fmtNum(overview?.totalPoints)" /></n-card></n-gi>
        </n-grid>

        <n-tabs
          v-model:value="activeTab"
          type="line"
          style="margin-top: 16px"
          animated
          @update:value="onTabChange"
        >
          <n-tab-pane name="trend" tab="日趋势">
            <n-data-table :columns="trendColumns" :data="trend" :loading="loading" size="small" :max-height="420" />
          </n-tab-pane>
          <n-tab-pane name="user" tab="用户排行">
            <n-data-table :columns="userDimColumns" :data="byUser" :loading="loading" size="small" :max-height="420" />
          </n-tab-pane>
          <n-tab-pane name="model" tab="模型排行">
            <n-data-table :columns="dimColumns" :data="byModel" :loading="loading" size="small" :max-height="420" />
          </n-tab-pane>
          <n-tab-pane name="kind" tab="类型排行">
            <n-data-table :columns="dimKindColumns" :data="byKind" :loading="loading" size="small" :max-height="420" />
          </n-tab-pane>

          <!-- 调用明细：逐条 llm_usage_logs（时间/用户/模型/类型/token/¥/积分/状态），服务端分页 + 筛选 -->
          <n-tab-pane name="detail" tab="调用明细">
            <!-- 8x Chunk7：drill-down 激活时顶部提示反查键 + 清除按钮 -->
            <div v-if="drillActive" class="billing-admin__drill-banner">
              <span>关联筛选：</span>
              <n-tag v-if="traceIdDrill" size="small" type="info" round>traceId: {{ traceIdDrill }}</n-tag>
              <n-tag v-if="taskIdDrill != null" size="small" type="warning" round>taskId: {{ taskIdDrill }}</n-tag>
              <n-button size="small" quaternary @click="clearDrill">清除</n-button>
            </div>
            <div class="billing-admin__detail-filter">
              <n-select
                v-model:value="userFilter"
                :options="userOptions"
                placeholder="全部用户"
                clearable
                style="width: 180px"
              />
              <n-select
                v-model:value="kindFilter"
                :options="kindOptions"
                placeholder="全部类型"
                clearable
                style="width: 140px"
              />
              <n-select
                v-model:value="statusFilter"
                :options="statusOptions"
                placeholder="全部状态"
                clearable
                style="width: 140px"
              />
              <n-input
                v-model:value="modelFilter"
                placeholder="模型名（回车筛选）"
                clearable
                style="width: 220px"
                @keyup.enter="loadDetail(1)"
              />
              <!-- 计划5 Step8：项目组筛选（空=全部含个人行） -->
              <n-select
                v-model:value="groupFilter"
                :options="groupOptions"
                placeholder="全部项目组"
                clearable
                style="width: 180px"
              />
            </div>
            <n-data-table
              remote
              :columns="detailColumns"
              :data="detail"
              :loading="detailLoading"
              :pagination="detailPagination"
              :scroll-x="1320"
              size="small"
              :max-height="480"
              @update:page="onDetailPage"
              @update:page-size="onDetailPageSize"
            />
          </n-tab-pane>

          <!-- 20x#1：充值记录（筛选+分页+当前筛选下 Σ 条） -->
          <n-tab-pane name="recharges" tab="充值记录">
            <div class="billing-admin__detail-filter">
              <n-input
                v-model:value="rechargeKeyword"
                placeholder="用户名/姓名（回车筛选）"
                clearable
                style="width: 180px"
                @keyup.enter="loadRecharges(1)"
              />
              <n-select
                v-model:value="rechargeChannel"
                :options="rechargeChannelOptions"
                placeholder="全部渠道"
                clearable
                style="width: 140px"
              />
              <n-select
                v-model:value="rechargeStatus"
                :options="rechargeStatusOptions"
                placeholder="全部状态"
                clearable
                style="width: 140px"
              />
              <n-date-picker
                v-model:value="rechargeRange"
                type="daterange"
                clearable
                :update-value-on-close="true"
                style="width: 260px"
              />
              <n-button size="small" @click="loadRecharges(1)">查询</n-button>
            </div>
            <!-- 筛选联动 Σ（PAID 口径；筛非 PAID 状态归 0） -->
            <div class="billing-admin__recharge-summary">
              当前筛选：已付金额 <b>¥{{ fmtNum(rechargeSums?.filteredPaidAmount) }}</b>，
              已付积分 <b>{{ fmtNum(rechargeSums?.filteredPaidPoints) }}</b>
            </div>
            <n-data-table
              remote
              :columns="rechargeColumns"
              :data="recharges"
              :loading="rechargeLoading"
              :pagination="rechargePagination"
              :scroll-x="1080"
              size="small"
              :max-height="480"
              @update:page="onRechargePage"
              @update:page-size="onRechargePageSize"
            />
          </n-tab-pane>

          <!-- 20x#1：用户余额（三合计卡 + 排序分页表格） -->
          <n-tab-pane name="balances" tab="用户余额">
            <n-grid :cols="4" :x-gap="12" responsive="screen" style="margin-bottom: 12px">
              <n-gi><n-card size="small"><n-statistic label="用户数" :value="balanceTotals?.totalUsers ?? 0" /></n-card></n-gi>
              <n-gi><n-card size="small"><n-statistic label="余额合计" :value="fmtNum(balanceTotals?.sumBalance)" /></n-card></n-gi>
              <n-gi><n-card size="small"><n-statistic label="累计充值积分" :value="fmtNum(balanceTotals?.sumRechargePoints)" /></n-card></n-gi>
              <n-gi><n-card size="small"><n-statistic label="累计充值金额 ¥" :value="fmtNum(balanceTotals?.sumRechargeAmount)" /></n-card></n-gi>
            </n-grid>
            <div class="billing-admin__detail-filter">
              <n-input
                v-model:value="balanceKeyword"
                placeholder="用户名/姓名（回车筛选）"
                clearable
                style="width: 200px"
                @keyup.enter="loadBalances(1)"
              />
              <n-button size="small" @click="loadBalances(1)">查询</n-button>
              <span class="billing-admin__balance-hint">合计卡跟随下方用户/姓名筛选（未筛选=全平台；仅统计已支付充值单，管理员发放不计入）</span>
            </div>
            <n-data-table
              remote
              :columns="balanceColumns"
              :data="balances"
              :loading="balanceLoading"
              :pagination="balancePagination"
              size="small"
              :max-height="480"
              @update:page="onBalancePage"
              @update:page-size="onBalancePageSize"
              @update:sorter="onBalanceSorter"
            />
          </n-tab-pane>
        </n-tabs>
      </template>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import {
  NCard, NGrid, NGi, NStatistic, NTabs, NTabPane, NDataTable, NDatePicker, NButton, NEmpty,
  NSelect, NInput, NTag
} from 'naive-ui'
import type { DataTableColumns, PaginationProps, SelectOption } from 'naive-ui'
import {
  billingApi, KIND_LABEL, KIND_TAG_TYPE, USAGE_STATUS_LABEL, USAGE_STATUS_TAG_TYPE,
  PAYMENT_STATUS_LABEL, PAYMENT_STATUS_TAG_TYPE, PAYMENT_CHANNEL_LABEL
} from '@/api/billing'
import type {
  UsageOverviewVO, UsageDimensionVO, DailyTrendVO, BillingKind, UsageDetailVO, UsageDetailQuery,
  AdminRechargeRecordVO, AdminRechargeQuery, UserBalanceRowVO, UserBalanceQuery, UserBalanceSortBy,
  PaymentStatus
} from '@/api/billing'
import { adminApi } from '@/api/admin'
import { useAuthStore } from '@/stores/auth'
import { useRoute } from 'vue-router'

const authStore = useAuthStore()
const route = useRoute()
const canView = computed(() => authStore.hasPermission('usage:view'))

const overview = ref<UsageOverviewVO | null>(null)
const trend = ref<DailyTrendVO[]>([])
const byUser = ref<UsageDimensionVO[]>([])
const byModel = ref<UsageDimensionVO[]>([])
const byKind = ref<UsageDimensionVO[]>([])
const loading = ref(false)

// ---------- 调用明细（独立状态，不进 loadAll 的 Promise.all） ----------
const detail = ref<UsageDetailVO[]>([])
const detailLoading = ref(false)
const activeTab = ref('trend')
const detailPagination = reactive<PaginationProps>({
  page: 1,
  pageSize: 20,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [20, 50, 100]
})
const userFilter = ref<number | null>(null)
const kindFilter = ref<string | null>(null)
const statusFilter = ref<string | null>(null)
const modelFilter = ref('')
const userOptions = ref<{ label: string; value: number }[]>([])
// 计划5 Step8：项目组筛选（数据源 /billing/admin/project-group-options）
const groupFilter = ref<number | null>(null)
const groupOptions = ref<{ label: string; value: number }[]>([])

// ---------- 8x Chunk7：审计行 drill-down 反查键（从 url query ?traceId= / ?taskId= 预填） ----------
const traceIdDrill = ref('')
const taskIdDrill = ref<number | null>(null)
/** drill-down 是否激活（来自审计行跳转，非用户手筛） */
const drillActive = computed(() => !!traceIdDrill.value || taskIdDrill.value != null)

// 全部=空（clearable 清空 / 不选）；下拉只列具体值。placeholder 文案给「全部」语义。
const kindOptions: SelectOption[] =
  (['CHAT', 'EMBED', 'IMAGE', 'VIDEO'] as BillingKind[]).map(k => ({ label: KIND_LABEL[k], value: k }))
const statusOptions: SelectOption[] =
  (['SUCCESS', 'FAILED', 'ESTIMATED'] as const).map(s => ({ label: USAGE_STATUS_LABEL[s], value: s }))

/** daterange 毫秒数 [start, end]；null=默认不传（后端兜底近 30 天） */
const range = ref<[number, number] | null>(null)

const queryParams = computed(() => {
  if (!range.value) return {}
  const [s, e] = range.value
  return { from: new Date(s).toISOString(), to: new Date(e + 86400000).toISOString() }
})

const trendColumns: DataTableColumns<DailyTrendVO> = [
  { title: '日期', key: 'day' },
  { title: '调用次数', key: 'callCount' },
  { title: '真实金额 ¥', key: 'costYuan', render: r => fmtNum(r.costYuan) },
  { title: '消耗积分', key: 'points', render: r => fmtNum(r.points) }
]

const dimColumns: DataTableColumns<UsageDimensionVO> = [
  { title: '维度', key: 'dimensionKey' },
  { title: '调用次数', key: 'callCount' },
  { title: '输入 Token', key: 'tokensInput' },
  { title: '输出 Token', key: 'tokensOutput' },
  { title: '真实金额 ¥', key: 'costYuan', render: r => fmtNum(r.costYuan) },
  { title: '消耗积分', key: 'points', render: r => fmtNum(r.points) }
]

/** D2（20x-1）：用户排行显「昵称（账号）」；昵称空回退账号，user_id 空为系统调用 */
const userDimColumns: DataTableColumns<UsageDimensionVO> = [
  {
    title: '用户', key: 'username', width: 170, ellipsis: { tooltip: true },
    render: r => r.displayName ? `${r.displayName}（${r.username}）` : (r.username || '系统')
  },
  { title: '调用次数', key: 'callCount' },
  { title: '输入 Token', key: 'tokensInput' },
  { title: '输出 Token', key: 'tokensOutput' },
  { title: '真实金额 ¥', key: 'costYuan', render: r => fmtNum(r.costYuan) },
  { title: '消耗积分', key: 'points', render: r => fmtNum(r.points) }
]

const dimKindColumns: DataTableColumns<UsageDimensionVO> = [
  { title: '类型', key: 'dimensionKey', render: r => KIND_LABEL[r.dimensionKey as BillingKind] ?? r.dimensionKey },
  { title: '调用次数', key: 'callCount' },
  { title: '输入 Token', key: 'tokensInput' },
  { title: '真实金额 ¥', key: 'costYuan', render: r => fmtNum(r.costYuan) },
  { title: '消耗积分', key: 'points', render: r => fmtNum(r.points) }
]

const detailColumns: DataTableColumns<UsageDetailVO> = [
  {
    title: '时间', key: 'createdAt', width: 170,
    render: r => r.createdAt ? new Date(r.createdAt).toLocaleString('zh-CN', { hour12: false }) : '—'
  },
  {
    title: '用户', key: 'username', width: 110,
    render: r => r.displayName || r.username || '系统'
  },
  { title: '模型', key: 'model', width: 160, ellipsis: { tooltip: true } },
  {
    title: '类型', key: 'kind', width: 90,
    render: r => h(NTag, {
      size: 'small', round: true,
      type: KIND_TAG_TYPE[r.kind as BillingKind] || 'default'
    }, { default: () => KIND_LABEL[r.kind as BillingKind] ?? r.kind })
  },
  // 计划5 Step8：项目组列（组池消耗显组名；个人消耗/软删历史组名缺失=「—」）
  {
    title: '项目组', key: 'projectGroupName', width: 120, ellipsis: { tooltip: true },
    render: r => r.projectGroupName || '—'
  },
  { title: '输入 Token', key: 'tokensInput', width: 100 },
  { title: '输出 Token', key: 'tokensOutput', width: 100 },
  { title: '真实金额 ¥', key: 'costYuan', width: 110, render: r => fmtNum(r.costYuan) },
  { title: '消耗积分', key: 'pointsConsumed', width: 100, render: r => fmtNum(r.pointsConsumed) },
  {
    title: '状态', key: 'status', width: 80,
    render: r => h(NTag, {
      size: 'small', round: true,
      type: USAGE_STATUS_TAG_TYPE[r.status] || 'default'
    }, { default: () => USAGE_STATUS_LABEL[r.status] ?? r.status })
  },
  // 8x Chunk7：关联键（chat→traceId / 媒体→taskId），drill-down 落地页核对用
  {
    title: '关联', key: 'link', width: 150, ellipsis: { tooltip: true },
    render: r => r.traceId ? `trace:${r.traceId}` : (r.taskId != null ? `task:${r.taskId}` : '—')
  },
  { title: '错误信息', key: 'errorMsg', ellipsis: { tooltip: true }, render: r => r.errorMsg || '—' }
]

function fmtNum(n: number | null | undefined | string): string {
  return n == null ? '—' : Number(n).toFixed(4).replace(/\.?0+$/, '')
}

async function loadAll() {
  if (!canView.value) return
  loading.value = true
  try {
    const p = queryParams.value
    const [o, t, u, m, k] = await Promise.all([
      billingApi.overview(p),
      billingApi.dailyTrend(p),
      billingApi.rankByUser(p),
      billingApi.rankByModel(p),
      billingApi.rankByKind(p)
    ])
    overview.value = o.data.data
    trend.value = t.data.data ?? []
    byUser.value = u.data.data ?? []
    byModel.value = m.data.data ?? []
    byKind.value = k.data.data ?? []
  } finally {
    loading.value = false
  }
  // 日期区间变更后，若明细已展开则同步刷新
  if (activeTab.value === 'detail') {
    loadDetail(1)
  }
}

/** 调用明细分页加载。page 省略=1；筛选/日期区间合并为 UsageDetailQuery。 */
async function loadDetail(page = 1) {
  if (!canView.value) return
  detailLoading.value = true
  try {
    const q: UsageDetailQuery = { ...queryParams.value, page, size: detailPagination.pageSize }
    if (userFilter.value != null) q.userId = userFilter.value
    const m = modelFilter.value.trim()
    if (m) q.model = m
    if (kindFilter.value) q.kind = kindFilter.value as BillingKind
    if (statusFilter.value) q.status = statusFilter.value
    // 计划5 Step8：项目组筛选
    if (groupFilter.value != null) q.projectGroupId = groupFilter.value
    // 8x Chunk7：drill-down 反查键（chat→traceId / 媒体→taskId）
    if (traceIdDrill.value) q.traceId = traceIdDrill.value
    if (taskIdDrill.value != null) q.taskId = taskIdDrill.value
    const res = await billingApi.listUsageDetail(q)
    detail.value = res.data.data.records
    detailPagination.itemCount = res.data.data.total
    detailPagination.page = page
  } catch {
    detail.value = []
    detailPagination.itemCount = 0
  } finally {
    detailLoading.value = false
  }
}

function onDetailPage(page: number) {
  loadDetail(page)
}
function onDetailPageSize(pageSize: number) {
  detailPagination.pageSize = pageSize
  loadDetail(1)
}

/** 8x Chunk7：清除 drill-down 反查键（回到全量明细）。 */
function clearDrill() {
  traceIdDrill.value = ''
  taskIdDrill.value = null
  loadDetail(1)
}

/** tab 首次切到「调用明细/充值记录/用户余额」懒加载。 */
function onTabChange(name: string | number) {
  if (name === 'detail' && detail.value.length === 0 && detailPagination.itemCount === 0) {
    loadDetail(1)
  }
  if (name === 'recharges' && recharges.value.length === 0 && rechargePagination.itemCount === 0) {
    loadRecharges(1)
  }
  if (name === 'balances' && balances.value.length === 0 && balancePagination.itemCount === 0) {
    loadBalances(1)
  }
}

// ---------- 20x#1：充值记录 tab ----------
const recharges = ref<AdminRechargeRecordVO[]>([])
const rechargeSums = ref<{ filteredPaidAmount: number; filteredPaidPoints: number } | null>(null)
const rechargeLoading = ref(false)
const rechargePagination = reactive<PaginationProps>({
  page: 1, pageSize: 20, itemCount: 0, showSizePicker: true, pageSizes: [20, 50, 100]
})
const rechargeKeyword = ref('')
const rechargeChannel = ref<string | null>(null)
const rechargeStatus = ref<PaymentStatus | null>(null)
const rechargeRange = ref<[number, number] | null>(null)

const rechargeChannelOptions: SelectOption[] =
  (['MOCK', 'ALIPAY', 'WECHAT'] as const).map(c => ({ label: PAYMENT_CHANNEL_LABEL[c], value: c }))
const rechargeStatusOptions: SelectOption[] =
  (['PENDING', 'PAID', 'FAILED', 'CLOSED'] as PaymentStatus[]).map(s => ({
    label: PAYMENT_STATUS_LABEL[s], value: s
  }))

const rechargeColumns: DataTableColumns<AdminRechargeRecordVO> = [
  {
    title: '时间', key: 'createdAt', width: 165,
    render: r => r.createdAt ? new Date(r.createdAt).toLocaleString('zh-CN', { hour12: false }) : '—'
  },
  { title: '用户', key: 'username', width: 150, ellipsis: { tooltip: true },
    render: r => r.name ? `${r.name}（${r.username}）` : r.username },
  {
    title: '渠道', key: 'channel', width: 90,
    render: r => PAYMENT_CHANNEL_LABEL[r.channel] ?? r.channel
  },
  { title: '付款账号', key: 'payerAccount', width: 150, ellipsis: { tooltip: true }, render: r => r.payerAccount || '—' },
  { title: '金额 ¥', key: 'amountYuan', width: 100, render: r => fmtNum(r.amountYuan) },
  { title: '积分', key: 'pointsGranted', width: 100, render: r => fmtNum(r.pointsGranted) },
  { title: '充值后余额', key: 'balanceAfter', width: 110, render: r => fmtNum(r.balanceAfter) },
  {
    title: '状态', key: 'status', width: 90,
    render: r => h(NTag, {
      size: 'small', round: true,
      type: PAYMENT_STATUS_TAG_TYPE[r.status] ?? 'default'
    }, { default: () => PAYMENT_STATUS_LABEL[r.status] ?? r.status })
  }
]

async function loadRecharges(page = 1) {
  if (!canView.value) return
  rechargeLoading.value = true
  try {
    const q: AdminRechargeQuery = { page, size: rechargePagination.pageSize }
    const kw = rechargeKeyword.value.trim()
    if (kw) q.keyword = kw
    if (rechargeChannel.value) q.channel = rechargeChannel.value
    if (rechargeStatus.value) q.status = rechargeStatus.value
    if (rechargeRange.value) {
      const [s, e] = rechargeRange.value
      q.from = new Date(s).toISOString()
      q.to = new Date(e + 86400000).toISOString()
    }
    const res = await billingApi.adminRecharges(q)
    const data = res.data.data
    recharges.value = data?.page.records ?? []
    rechargePagination.itemCount = data?.page.total ?? 0
    rechargePagination.page = page
    rechargeSums.value = data
      ? { filteredPaidAmount: data.filteredPaidAmount, filteredPaidPoints: data.filteredPaidPoints }
      : null
  } catch {
    recharges.value = []
    rechargePagination.itemCount = 0
    rechargeSums.value = null
  } finally {
    rechargeLoading.value = false
  }
}

function onRechargePage(page: number) {
  loadRecharges(page)
}
function onRechargePageSize(pageSize: number) {
  rechargePagination.pageSize = pageSize
  loadRecharges(1)
}

// ---------- 20x#1：用户余额 tab ----------
const balances = ref<UserBalanceRowVO[]>([])
const balanceTotals = ref<{
  totalUsers: number; sumBalance: number; sumRechargePoints: number; sumRechargeAmount: number
} | null>(null)
const balanceLoading = ref(false)
const balancePagination = reactive<PaginationProps>({
  page: 1, pageSize: 20, itemCount: 0, showSizePicker: true, pageSizes: [20, 50, 100]
})
const balanceKeyword = ref('')
const balanceSortBy = ref<UserBalanceSortBy>('balance')
const balanceSortOrder = ref<'asc' | 'desc'>('desc')

const balanceColumns: DataTableColumns<UserBalanceRowVO> = [
  /** D2（20x-1）：显「昵称（账号）」；昵称空回退账号 */
  { title: '用户', key: 'username', width: 170, ellipsis: { tooltip: true },
    render: r => r.name ? `${r.name}（${r.username}）` : r.username },
  {
    title: '当前余额', key: 'balancePoints', width: 130,
    sorter: true, sortOrder: false,
    render: r => fmtNum(r.balancePoints)
  },
  {
    title: '累计充值积分', key: 'totalRechargePoints', width: 140,
    sorter: true, sortOrder: false,
    render: r => fmtNum(r.totalRechargePoints)
  },
  {
    title: '累计充值金额 ¥', key: 'totalRechargeAmount', width: 150,
    sorter: true, sortOrder: false,
    render: r => fmtNum(r.totalRechargeAmount)
  },
  {
    title: '最近充值时间', key: 'lastRechargeAt', width: 170,
    render: r => r.lastRechargeAt ? new Date(r.lastRechargeAt).toLocaleString('zh-CN', { hour12: false }) : '—'
  }
]

/** 前端列 key → 后端排序白名单字段（仅三列，其余不落参）。 */
const BALANCE_SORT_KEY_MAP: Record<string, UserBalanceSortBy> = {
  balancePoints: 'balance',
  totalRechargePoints: 'rechargePoints',
  totalRechargeAmount: 'rechargeAmount'
}

async function loadBalances(page = 1) {
  if (!canView.value) return
  balanceLoading.value = true
  try {
    const q: UserBalanceQuery = {
      page, size: balancePagination.pageSize,
      sortBy: balanceSortBy.value, order: balanceSortOrder.value
    }
    const kw = balanceKeyword.value.trim()
    if (kw) q.keyword = kw
    const res = await billingApi.adminUserBalances(q)
    const data = res.data.data
    balances.value = data?.page.records ?? []
    balancePagination.itemCount = data?.page.total ?? 0
    balancePagination.page = page
    balanceTotals.value = data
      ? {
          totalUsers: data.totalUsers, sumBalance: data.sumBalance,
          sumRechargePoints: data.sumRechargePoints, sumRechargeAmount: data.sumRechargeAmount
        }
      : null
  } catch {
    balances.value = []
    balancePagination.itemCount = 0
  } finally {
    balanceLoading.value = false
  }
}

function onBalancePage(page: number) {
  loadBalances(page)
}
function onBalancePageSize(pageSize: number) {
  balancePagination.pageSize = pageSize
  loadBalances(1)
}

/** 服务端排序：sorter 变化 → 映射白名单字段重查（false=清除排序回落默认）。 */
function onBalanceSorter(sorter: { columnKey?: string; order?: 'ascend' | 'descend' | false } | null) {
  if (sorter?.columnKey && sorter.order && BALANCE_SORT_KEY_MAP[sorter.columnKey]) {
    balanceSortBy.value = BALANCE_SORT_KEY_MAP[sorter.columnKey]
    balanceSortOrder.value = sorter.order === 'ascend' ? 'asc' : 'desc'
  } else {
    balanceSortBy.value = 'balance'
    balanceSortOrder.value = 'desc'
  }
  loadBalances(1)
}

/** 用户筛选下拉：取用户列表（需 user 权限；403 容错→空下拉，表格用户名仍由后端 JOIN 返）。 */
async function loadUserOptions() {
  try {
    const res = await adminApi.listUsers(1, 1000)
    userOptions.value = (res.data.data.records ?? []).map(u => ({
      label: u.name || u.username,
      value: u.id
    }))
  } catch {
    userOptions.value = []
  }
}

/** 计划5 Step8：项目组筛选下拉（403/无组容错→空下拉，行内组名仍由后端 JOIN 返）。 */
async function loadGroupOptions() {
  try {
    const res = await billingApi.projectGroupOptions()
    groupOptions.value = (res.data.data ?? []).map(g => ({ label: g.name, value: g.id }))
  } catch {
    groupOptions.value = []
  }
}

// 筛选下拉变化 → 回到第 1 页重查（模型名走回车，不在此列）
watch([userFilter, kindFilter, statusFilter, groupFilter], () => {
  if (activeTab.value === 'detail') loadDetail(1)
})

onMounted(() => {
  loadAll()
  loadUserOptions()
  loadGroupOptions()
  // 8x Chunk7：审计行 drill-down 跳转带 ?traceId= / ?taskId= → 预填 + 直跳调用明细 tab
  const qt = route.query.traceId
  const qtask = route.query.taskId
  if (typeof qt === 'string' && qt) traceIdDrill.value = qt
  if (typeof qtask === 'string' && qtask) taskIdDrill.value = Number(qtask)
  if (drillActive.value) {
    activeTab.value = 'detail'
    loadDetail(1)
  }
})
</script>

<style lang="scss" scoped>
.billing-admin {
  padding: var(--spacing-4);
}
.billing-admin__noperm {
  padding: var(--spacing-5) 0;
}
.billing-admin__detail-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.billing-admin__drill-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding: 6px 12px;
  background: var(--color-bg-secondary, rgba(255, 255, 255, 0.04));
  border-radius: 6px;
  font-size: 13px;
}
.billing-admin__recharge-summary {
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--color-text-secondary, rgba(255, 255, 255, 0.6));
}
.billing-admin__balance-hint {
  font-size: 12px;
  color: var(--color-text-tertiary, rgba(255, 255, 255, 0.45));
  align-self: center;
}
</style>
