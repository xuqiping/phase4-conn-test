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
            <n-data-table :columns="dimColumns" :data="byUser" :loading="loading" size="small" :max-height="420" />
          </n-tab-pane>
          <n-tab-pane name="model" tab="模型排行">
            <n-data-table :columns="dimColumns" :data="byModel" :loading="loading" size="small" :max-height="420" />
          </n-tab-pane>
          <n-tab-pane name="kind" tab="类型排行">
            <n-data-table :columns="dimKindColumns" :data="byKind" :loading="loading" size="small" :max-height="420" />
          </n-tab-pane>

          <!-- 调用明细：逐条 llm_usage_logs（时间/用户/模型/类型/token/¥/积分/状态），服务端分页 + 筛选 -->
          <n-tab-pane name="detail" tab="调用明细">
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
            </div>
            <n-data-table
              remote
              :columns="detailColumns"
              :data="detail"
              :loading="detailLoading"
              :pagination="detailPagination"
              :scroll-x="1200"
              size="small"
              :max-height="480"
              @update:page="onDetailPage"
              @update:page-size="onDetailPageSize"
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
  billingApi, KIND_LABEL, KIND_TAG_TYPE, USAGE_STATUS_LABEL, USAGE_STATUS_TAG_TYPE
} from '@/api/billing'
import type {
  UsageOverviewVO, UsageDimensionVO, DailyTrendVO, BillingKind, UsageDetailVO, UsageDetailQuery
} from '@/api/billing'
import { adminApi } from '@/api/admin'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
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

/** tab 首次切到「调用明细」懒加载。 */
function onTabChange(name: string | number) {
  if (name === 'detail' && detail.value.length === 0 && detailPagination.itemCount === 0) {
    loadDetail(1)
  }
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

// 筛选下拉变化 → 回到第 1 页重查（模型名走回车，不在此列）
watch([userFilter, kindFilter, statusFilter], () => {
  if (activeTab.value === 'detail') loadDetail(1)
})

onMounted(() => {
  loadAll()
  loadUserOptions()
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
</style>
