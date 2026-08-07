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
        <n-grid :cols="4" :x-gap="12" responsive="screen">
          <n-gi><n-card size="small"><n-statistic label="调用次数" :value="overview?.callCount ?? 0" /></n-card></n-gi>
          <n-gi><n-card size="small"><n-statistic label="输入 Token" :value="overview?.totalTokensInput ?? 0" /></n-card></n-gi>
          <n-gi><n-card size="small"><n-statistic label="真实金额 ¥" :value="fmtNum(overview?.totalCostYuan)" /></n-card></n-gi>
          <n-gi><n-card size="small"><n-statistic label="消耗积分" :value="fmtNum(overview?.totalPoints)" /></n-card></n-gi>
        </n-grid>

        <n-tabs type="line" style="margin-top: 16px" animated>
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
        </n-tabs>
      </template>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  NCard, NGrid, NGi, NStatistic, NTabs, NTabPane, NDataTable, NDatePicker, NButton, NEmpty
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { billingApi, KIND_LABEL } from '@/api/billing'
import type { UsageOverviewVO, UsageDimensionVO, DailyTrendVO, BillingKind } from '@/api/billing'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const canView = computed(() => authStore.hasPermission('usage:view'))

const overview = ref<UsageOverviewVO | null>(null)
const trend = ref<DailyTrendVO[]>([])
const byUser = ref<UsageDimensionVO[]>([])
const byModel = ref<UsageDimensionVO[]>([])
const byKind = ref<UsageDimensionVO[]>([])
const loading = ref(false)

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
}

onMounted(loadAll)
</script>

<style lang="scss" scoped>
.billing-admin {
  padding: var(--spacing-4);
}
.billing-admin__noperm {
  padding: var(--spacing-5) 0;
}
</style>
