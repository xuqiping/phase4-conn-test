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

      <!-- 详情弹窗（13x-1/2：详情中文化 + 处置过程时间线说明） -->
      <n-modal v-model:show="detailVisible" preset="card" title="事件详情" style="max-width: 640px">
        <n-descriptions v-if="current" :column="1" size="small" bordered>
          <n-descriptions-item label="类型">{{ typeCn(current.eventType) }}（{{ current.eventType }}）</n-descriptions-item>
          <n-descriptions-item label="严重度">{{ severityCn(current.severity) }}</n-descriptions-item>
          <n-descriptions-item label="用户">{{ current.userId ? `用户#${current.userId}` : '—（未关联用户）' }}</n-descriptions-item>
          <n-descriptions-item label="IP">{{ current.clientIp ?? '—' }}</n-descriptions-item>
          <n-descriptions-item label="检测规则">{{ current.ruleId ?? '内置阈值规则' }}</n-descriptions-item>
          <n-descriptions-item label="traceId">{{ current.traceId ?? '—' }}</n-descriptions-item>
          <n-descriptions-item label="发生时间">{{ current.createdAt }}</n-descriptions-item>
        </n-descriptions>

        <!-- 13x-2：处置过程时间线——自动处置在事件落库瞬间已执行，人工「处置」只是确认标记 -->
        <div v-if="current" class="security-event-view__process">
          <div class="security-event-view__process-title">处置过程</div>
          <div class="security-event-view__step">
            <span class="security-event-view__step-label">① 事件发生</span>
            <span>系统检测到「{{ typeCn(current.eventType) }}」（{{ severityCn(current.severity) }}），记录本条事件并推送告警。</span>
          </div>
          <div class="security-event-view__step">
            <span class="security-event-view__step-label">② 自动处置（发生时已执行）</span>
            <span>{{ autoActionExplain(current) }}</span>
          </div>
          <div class="security-event-view__step">
            <span class="security-event-view__step-label">③ 人工处置</span>
            <span v-if="current.handled">
              已由 {{ current.handledBy || '管理员' }} 于 {{ current.handledAt }} 确认处置。
              确认动作会记录处置人和时间并关闭待办，不会重复执行封禁/锁定；
              如需额外封禁请前往「IP 黑名单 / 账号封禁」手动操作。
            </span>
            <span v-else>
              待确认：点「处置」将本条标记为已处置并记录处置人与时间（自动处置已在事件发生时执行过，不会重做）。
            </span>
          </div>
        </div>

        <!-- 13x-1：详情键值中文渲染，不再堆原始 JSON -->
        <div v-if="current" class="security-event-view__detail">
          <div class="security-event-view__detail-title">事件详情</div>
          <DetailKvView :raw="current.detailJson" />
        </div>

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
  EVENT_TYPE_CN, SEVERITY_CN, AUTO_ACTION_CN, type SecurityEventVO,
} from '@/api/security'
import DetailKvView from '@/components/DetailKvView.vue'

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
const autoActionCn = (a: string | null | undefined) => AUTO_ACTION_CN[a ?? 'NONE'] ?? (a ?? '未执行')

/**
 * 13x-2：按 AutoResponder 处置矩阵解释「事件发生时系统实际做了什么」。
 * 时长与后端 AutoResponder 常量同源（锁号 15min / 高危封IP 60min / 危急封IP 24h）。
 */
function autoActionExplain(r: SecurityEventVO): string {
  if (r.severity === 'CRITICAL') {
    return '事件达到「危急」级：系统已自动锁定涉事账号 15 分钟，并封禁来源 IP 24 小时（若自动处置总闸被管理员关闭，则降级为仅告警）。'
  }
  const a = r.autoAction ?? 'NONE'
  if (a === 'ACCOUNT_LOCKED') {
    return '系统已自动锁定涉事账号 15 分钟，锁定期间该账号无法登录和调用接口（登录时会提示稍后再试）。'
  }
  if (a === 'IP_BLOCKED') {
    return '系统已自动封禁来源 IP 60 分钟，封禁期间该 IP 的所有请求被直接拒绝。'
  }
  if (a === 'ACCOUNT_BANNED') {
    return '系统已自动封禁涉事账号，需要管理员手动解封。'
  }
  if (a === 'TOKEN_REVOKED') {
    return '系统已自动吊销涉事用户的登录凭证，其所有会话立即失效，需重新登录。'
  }
  return '按处置矩阵，本级别事件不执行自动封禁：系统仅记录事件并推送告警，由管理员判断是否手动处置。'
}

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
  { title: '用户', key: 'userId', width: 90, render: (r) => (r.userId != null ? `用户#${r.userId}` : '—') },
  { title: 'IP', key: 'clientIp', width: 130, render: (r) => r.clientIp ?? '—' },
  { title: '自动处置', key: 'autoAction', width: 110, render: (r) => autoActionCn(r.autoAction) },
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
    // 13x-2：明示「处置」按钮的实际语义（仅确认标记，自动动作发生时已执行）
    message.success('已标记处置：已记录处置人与时间。自动处置动作在事件发生时已执行，不会重复触发。')
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

  &__process,
  &__detail {
    margin-top: 12px;
  }

  &__process-title,
  &__detail-title {
    font-size: 13px;
    font-weight: 600;
    margin-bottom: 6px;
    color: var(--color-text-primary);
  }

  &__step {
    display: flex;
    gap: 10px;
    padding: 4px 0;
    font-size: 13px;
    line-height: 1.6;
    color: var(--color-text-primary);
  }

  &__step-label {
    flex: 0 0 170px;
    color: var(--color-text-secondary);
    white-space: nowrap;
  }
}
</style>
