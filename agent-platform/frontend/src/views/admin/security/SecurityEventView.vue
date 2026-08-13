<!-- agent-platform/frontend/src/views/admin/security/SecurityEventView.vue
     安全事件中心（11x 加固 P4-C12）：筛选 + 分页表格 + 详情 + ACK 处置 + 批量物理删（二次确认）
     权限：security:event:read（看）；security:ban:manage（处置/删除） -->
<template>
  <div class="security-event-view">
    <n-card title="安全事件中心" size="small">
      <!-- 筛选栏 -->
      <n-space class="security-event-view__filters" wrap>
        <n-select v-model:value="query.eventType" :options="eventTypeOptions" placeholder="事件类型"
                  clearable style="width: 160px" />
        <n-select v-model:value="query.severity" :options="severityOptions" placeholder="严重度"
                  clearable style="width: 120px" />
        <n-select v-model:value="handledFilter" :options="handledOptions" placeholder="状态"
                  clearable style="width: 120px" />
        <n-button type="primary" @click="reload">查询</n-button>
        <n-button :disabled="selectedIds.length === 0" type="error" secondary @click="confirmBatchDelete">
          批量清理({{ selectedIds.length }})
        </n-button>
      </n-space>

      <n-data-table :columns="columns" :data="rows" :loading="loading" :pagination="pagination"
                    :row-key="(r: SecurityEventVO) => r.id" remote
                    :checked-row-keys="selectedIds" @update:checked-row-keys="(k: number[]) => (selectedIds = k)"
                    size="small" />

      <!-- 详情弹窗 -->
      <n-modal v-model:show="detailVisible" preset="card" title="事件详情" style="max-width: 640px">
        <n-descriptions v-if="current" :column="1" size="small" bordered>
          <n-descriptions-item label="类型">{{ typeCn(current.eventType) }} ({{ current.eventType }})</n-descriptions-item>
          <n-descriptions-item label="严重度">{{ severityCn(current.severity) }}</n-descriptions-item>
          <n-descriptions-item label="用户">{{ current.userId ?? '-' }}</n-descriptions-item>
          <n-descriptions-item label="IP">{{ current.clientIp ?? '-' }}</n-descriptions-item>
          <n-descriptions-item label="规则">{{ current.ruleId ?? '-' }}</n-descriptions-item>
          <n-descriptions-item label="traceId">{{ current.traceId ?? '-' }}</n-descriptions-item>
          <n-descriptions-item label="自动处置">{{ current.autoAction ?? 'NONE' }}</n-descriptions-item>
          <n-descriptions-item label="时间">{{ current.createdAt }}</n-descriptions-item>
          <n-descriptions-item label="详情">{{ current.detailJson }}</n-descriptions-item>
        </n-descriptions>
        <template #footer>
          <n-space justify="end">
            <n-button v-if="current && !current.handled" type="primary" @click="ack(current.id)">处置</n-button>
            <n-button @click="detailVisible = false">关闭</n-button>
          </n-space>
        </template>
      </n-modal>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { NButton, NTag, useDialog, useMessage, type DataTableColumns } from 'naive-ui'
import {
  ackSecurityEvent, batchDeleteSecurityEvents, listSecurityEvents,
  EVENT_TYPE_CN, SEVERITY_CN, type SecurityEventVO,
} from '@/api/security'

const message = useMessage()
const dialog = useDialog()

const loading = ref(false)
const rows = ref<SecurityEventVO[]>([])
const selectedIds = ref<number[]>([])
const detailVisible = ref(false)
const current = ref<SecurityEventVO | null>(null)

const query = ref<{ eventType: string | null; severity: string | null }>({ eventType: null, severity: null })
const handledFilter = ref<boolean | null>(null)
const page = ref(1)
const size = ref(10)
const total = ref(0)

const eventTypeOptions = Object.entries(EVENT_TYPE_CN).map(([value, label]) => ({ value, label }))
const severityOptions = Object.entries(SEVERITY_CN).map(([value, label]) => ({ value, label }))
const handledOptions = [
  { value: false, label: '未处置' },
  { value: true, label: '已处置' },
]

const pagination = computed(() => ({
  page: page.value, pageSize: size.value, itemCount: total.value,
  onUpdatePage: (p: number) => { page.value = p; reload() },
}))

const severityType = (s: string) =>
  ({ LOW: 'default', MEDIUM: 'warning', HIGH: 'error', CRITICAL: 'error' })[s] as 'default' | 'warning' | 'error'

const typeCn = (t: string) => EVENT_TYPE_CN[t] ?? t
const severityCn = (s: string) => SEVERITY_CN[s] ?? s

const columns: DataTableColumns<SecurityEventVO> = [
  { type: 'selection' },
  { title: '时间', key: 'createdAt', width: 160 },
  {
    title: '类型', key: 'eventType', width: 130,
    render: (r) => h(NTag, { size: 'small' }, { default: () => typeCn(r.eventType) }),
  },
  {
    title: '严重度', key: 'severity', width: 90,
    render: (r) => h(NTag, { size: 'small', type: severityType(r.severity) }, { default: () => severityCn(r.severity) }),
  },
  { title: '用户', key: 'userId', width: 80, render: (r) => r.userId ?? '-' },
  { title: 'IP', key: 'clientIp', width: 130, render: (r) => r.clientIp ?? '-' },
  { title: '自动处置', key: 'autoAction', width: 120, render: (r) => r.autoAction ?? 'NONE' },
  {
    title: '状态', key: 'handled', width: 90,
    render: (r) => h(NTag, { size: 'small', type: r.handled ? 'success' : 'warning' },
      { default: () => (r.handled ? '已处置' : '未处置') }),
  },
  {
    title: '操作', key: 'ops', width: 150,
    render: (r) => h('div', { style: 'display:flex;gap:8px' }, [
      h(NButton, { size: 'tiny', onClick: () => { current.value = r; detailVisible.value = true } }, { default: () => '详情' }),
      !r.handled && h(NButton, { size: 'tiny', type: 'primary', onClick: () => ack(r.id) }, { default: () => '处置' }),
    ]),
  },
]

async function reload() {
  loading.value = true
  try {
    const resp = await listSecurityEvents({
      eventType: query.value.eventType ?? undefined,
      severity: query.value.severity ?? undefined,
      handled: handledFilter.value ?? undefined,
      page: page.value, size: size.value,
    })
    rows.value = resp.data.data.records
    total.value = resp.data.data.total
  } catch (e: any) {
    message.error(e?.response?.data?.msg ?? '加载失败')
  } finally {
    loading.value = false
  }
}

async function ack(id: number) {
  try {
    await ackSecurityEvent(id)
    message.success('已处置')
    detailVisible.value = false
    reload()
  } catch (e: any) {
    message.error(e?.response?.data?.msg ?? '处置失败')
  }
}

function confirmBatchDelete() {
  dialog.warning({
    title: '批量物理删除',
    content: `确定物理删除选中的 ${selectedIds.value.length} 条事件？不可恢复！`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await batchDeleteSecurityEvents(selectedIds.value)
        message.success('已删除')
        selectedIds.value = []
        reload()
      } catch (e: any) {
        message.error(e?.response?.data?.msg ?? '删除失败')
      }
    },
  })
}

onMounted(reload)
</script>

<style scoped lang="scss">
.security-event-view {
  padding: 12px;
  &__filters {
    margin-bottom: 12px;
  }
}
</style>
