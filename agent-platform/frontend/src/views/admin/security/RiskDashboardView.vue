<!-- agent-platform/frontend/src/views/admin/security/RiskDashboardView.vue
     风险大盘（11x 加固 P4-C12）：24h 事件计数 + 严重度分布 + 类型 TOP + 待处置入口
     权限：security:event:read -->
<template>
  <div class="risk-dashboard-view">
    <!-- 雾中浮岛场景层（ART-DIR-0002R 方向二 admin 淡版，仅 ink 主题渲染） -->
    <ModuleScene scene="admin" lite />
    <PageHeader title="风险大盘" sub="24h 安全事件概览 · 严重度分布与类型 TOP" />
    <n-grid :cols="4" :x-gap="12" :y-gap="12">
      <n-grid-item v-for="card in severityCards" :key="card.label">
        <n-card size="small">
          <n-statistic :label="card.label" :value="card.count">
            <template #suffix>
              <n-tag :type="card.tagType" size="small">{{ card.label }}</n-tag>
            </template>
          </n-statistic>
        </n-card>
      </n-grid-item>
    </n-grid>

    <n-card title="24h 事件类型 TOP10" size="small" style="margin-top: 12px">
      <n-data-table :columns="typeColumns" :data="typeRows" :pagination="{ pageSize: 10 }" size="small" />
    </n-card>

    <n-card size="small" style="margin-top: 12px">
      <n-space align="center">
        <span>待处置事件：<b>{{ stats?.unhandled ?? 0 }}</b> 条</span>
        <n-button type="primary" size="small" @click="$router.push('/admin/security/events')">
          进入事件中心处置
        </n-button>
        <n-button size="small" @click="reload">刷新</n-button>
      </n-space>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { NTag, type DataTableColumns } from 'naive-ui'
import { securityStats, EVENT_TYPE_CN, SEVERITY_CN, type SecurityStats } from '@/api/security'
import PageHeader from '@/components/PageHeader.vue'
import ModuleScene from '@/components/ModuleScene.vue'

const stats = ref<SecurityStats | null>(null)

interface TypeRow { type: string; cnt: number }

const typeRows = computed<TypeRow[]>(() =>
  (stats.value?.byType ?? []).map((r) => ({ type: r.event_type ?? r.eventType ?? '-', cnt: Number(r.cnt) })))

const typeColumns: DataTableColumns<TypeRow> = [
  {
    title: '事件类型', key: 'type',
    render: (r) => h(NTag, { size: 'small' }, { default: () => EVENT_TYPE_CN[r.type] ?? r.type }),
  },
  { title: '24h 次数', key: 'cnt', width: 120 },
]

const severityCards = computed(() => {
  const map = new Map((stats.value?.bySeverity ?? []).map((r) => [r.severity, Number(r.cnt)]))
  return (['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const).map((sev) => ({
    label: `${SEVERITY_CN[sev]}（24h）`,
    count: map.get(sev) ?? 0,
    tagType: (sev === 'LOW' ? 'default' : sev === 'MEDIUM' ? 'warning' : 'error') as 'default' | 'warning' | 'error',
  }))
})

async function reload() {
  try {
    const resp = await securityStats()
    stats.value = resp.data.data
  } catch {
    // 静默：大盘加载失败不打扰（事件中心仍可查）
  }
}

onMounted(reload)
</script>

<style scoped lang="scss">
.risk-dashboard-view {
  padding: 12px;
}
</style>
