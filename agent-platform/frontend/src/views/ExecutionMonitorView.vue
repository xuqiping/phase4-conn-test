<template>
  <div class="execution-monitor">
    <div class="execution-monitor__toolbar">
      <div class="execution-monitor__toolbar-main">
        <h2>执行监控</h2>
        <span>{{ scopeText }}</span>
      </div>
      <div class="execution-monitor__toolbar-actions">
        <n-input
          v-model:value="keywordFilter"
          class="execution-monitor__keyword-filter"
          clearable
          placeholder="筛选执行ID、工作流、用户、Trace"
        />
        <n-select
          v-model:value="statusFilter"
          class="execution-monitor__status-filter"
          :options="statusOptions"
        />
        <n-select
          v-model:value="sourceTypeFilter"
          class="execution-monitor__source-filter"
          :options="sourceOptions"
        />
        <n-input-number
          v-model:value="executionId"
          class="execution-monitor__input"
          :min="1"
          placeholder="执行 ID"
        />
        <n-button :loading="detailLoading" @click="loadById">查询</n-button>
        <n-button type="primary" :loading="loading" @click="loadExecutions">刷新</n-button>
      </div>
    </div>

    <section class="execution-monitor__panel">
      <div class="execution-monitor__summary">
        <strong>执行任务</strong>
        <n-tag>{{ filteredExecutions.length }}</n-tag>
        <span v-if="filteredExecutions.length !== executions.length" class="execution-monitor__summary-muted">
          共 {{ executions.length }} 条
        </span>
      </div>

      <InkEmptyState v-if="!filteredExecutions.length" type="data" description="暂无执行任务" />
      <div v-else class="execution-monitor__table-wrap">
        <table class="execution-monitor__table">
          <thead>
            <tr>
              <th>执行 ID</th>
              <th>工作流</th>
              <th>状态</th>
              <th>调用用户</th>
              <th>来源</th>
              <th>开始时间</th>
              <th>耗时</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in pagedExecutions"
              :key="item.id"
              :class="{ 'execution-monitor__row--active': selectedExecution?.id === item.id }"
              @click="selectExecution(item)"
            >
              <td>#{{ item.id }}</td>
              <td>{{ item.workflowName || '未命名工作流' }}</td>
              <td>
                <n-tag size="small" :type="statusType(item.status)">{{ statusText(item.status) }}</n-tag>
              </td>
              <td>
                <span>{{ triggerUserText(item) }}</span>
                <span v-if="item.triggeredBy" class="execution-monitor__muted">#{{ item.triggeredBy }}</span>
              </td>
              <td>{{ item.sourceType || 'WORKFLOW' }}</td>
              <td>{{ formatDate(item.startedAt) }}</td>
              <td>{{ formatDuration(item.duration) }}</td>
              <td>
                <n-button size="small" quaternary @click.stop="selectExecution(item)">查看</n-button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="filteredExecutions.length" class="execution-monitor__pagination">
        <div class="execution-monitor__page-size">
          <span>每页</span>
          <n-select
            v-model:value="pageSizeMode"
            class="execution-monitor__page-size-select"
            :options="pageSizeOptions"
          />
          <n-input-number
            v-if="pageSizeMode === 'CUSTOM'"
            v-model:value="customPageSize"
            class="execution-monitor__custom-page-size"
            :min="1"
            :max="500"
            :precision="0"
            size="small"
          />
          <span>条</span>
        </div>
        <n-pagination
          v-model:page="page"
          :page-size="pageSize"
          :item-count="filteredExecutions.length"
          :page-slot="5"
        />
      </div>
    </section>

    <InkEmptyState v-if="!selectedExecution" type="data" description="请选择一个执行任务查看详情" />

    <section v-else class="execution-monitor__panel">
      <div class="execution-monitor__summary">
        <n-tag :type="statusType(selectedExecution.status)">{{ statusText(selectedExecution.status) }}</n-tag>
        <strong>执行 #{{ selectedExecution.id }}</strong>
        <span>{{ selectedExecution.workflowName || '未命名工作流' }}</span>
      </div>

      <dl class="execution-monitor__details">
        <div>
          <dt>工作流 ID</dt>
          <dd>{{ selectedExecution.workflowId || '-' }}</dd>
        </div>
        <div>
          <dt>调用用户</dt>
          <dd>{{ triggerUserText(selectedExecution) }}</dd>
        </div>
        <div>
          <dt>Trace ID</dt>
          <dd>{{ selectedExecution.traceId || '-' }}</dd>
        </div>
        <div>
          <dt>Checkpoint</dt>
          <dd>{{ selectedExecution.checkpointRef || recoveryInfo?.checkpointRef || '-' }}</dd>
        </div>
        <div>
          <dt>开始时间</dt>
          <dd>{{ formatDate(selectedExecution.startedAt) }}</dd>
        </div>
        <div>
          <dt>完成时间</dt>
          <dd>{{ formatDate(selectedExecution.completedAt) }}</dd>
        </div>
      </dl>

      <p v-if="recoveryInfo?.errorMessage || selectedExecution.errorMessage" class="execution-monitor__error">
        {{ recoveryInfo?.errorMessage || selectedExecution.errorMessage }}
      </p>

      <div class="execution-monitor__actions">
        <n-button :loading="actionLoading === 'retry'" @click="runAction('retry')">重试</n-button>
        <n-button
          :disabled="!recoveryInfo?.checkpointRef"
          :loading="actionLoading === 'resume'"
          @click="runAction('resume')"
        >
          恢复
        </n-button>
        <n-button
          :disabled="selectedExecution.status !== 'WAITING_APPROVAL'"
          :loading="actionLoading === 'approve'"
          @click="runAction('approve')"
        >
          通过审批
        </n-button>
        <n-button
          type="error"
          :disabled="selectedExecution.status !== 'WAITING_APPROVAL'"
          :loading="actionLoading === 'reject'"
          @click="runAction('reject')"
        >
          拒绝审批
        </n-button>
      </div>

      <div class="execution-monitor__timeline">
        <h3>事件时间线</h3>
        <n-empty v-if="!timeline.length" description="暂无运行时事件" />
        <div v-else class="execution-monitor__events">
          <div
            v-for="item in timeline"
            :key="item.key"
            class="execution-monitor__event"
            :class="`execution-monitor__event--${item.status.toLowerCase()}`"
          >
            <span class="execution-monitor__event-dot" />
            <div class="execution-monitor__event-main">
              <div class="execution-monitor__event-title">
                <strong>{{ item.type }}</strong>
                <span>{{ item.nodeId || '工作流' }}</span>
                <span v-if="item.timestamp">{{ formatDate(item.timestamp) }}</span>
              </div>
              <div class="execution-monitor__event-meta">
                <span v-if="item.checkpointRef">checkpoint={{ item.checkpointRef }}</span>
                <span v-if="item.selectedRoute">route={{ item.selectedRoute }}</span>
                <span v-if="item.selectedTarget">target={{ item.selectedTarget }}</span>
                <span v-if="item.failedNodeId">failed={{ item.failedNodeId }}</span>
                <span v-if="item.approvalKey">approval={{ item.approvalKey }}</span>
                <span v-if="item.agentName">agent={{ item.agentName }}</span>
                <span v-if="item.selectedSkillIds.length">skills={{ item.selectedSkillIds.join(',') }}</span>
              </div>
              <div v-if="item.inputPayload || item.outputPayload" class="execution-monitor__payloads">
                <div v-if="item.inputPayload" class="execution-monitor__payload">
                  <span>输入参数</span>
                  <pre>{{ formatPayload(item.inputPayload) }}</pre>
                </div>
                <div v-if="item.outputPayload" class="execution-monitor__payload">
                  <span>输出参数</span>
                  <pre>{{ formatPayload(item.outputPayload) }}</pre>
                </div>
              </div>
              <p v-if="item.outputText" class="execution-monitor__event-output">
                {{ item.outputText }}
              </p>
              <div v-if="item.stepOutputs.length" class="execution-monitor__steps">
                <div
                  v-for="(step, stepIndex) in item.stepOutputs"
                  :key="`${item.key}-step-${stepIndex}`"
                  class="execution-monitor__step"
                >
                  <span>#{{ step.skillId || step.stepId || stepIndex + 1 }}</span>
                  <span>{{ step.output || step.error || '-' }}</span>
                </div>
              </div>
              <p v-if="item.errorMessage" class="execution-monitor__event-error">
                {{ item.errorMessage }}
              </p>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { NButton, NEmpty, NInput, NInputNumber, NPagination, NSelect, NTag, useMessage } from 'naive-ui'
import { executionApi, type ExecutionLog, type ExecutionRecoveryInfo } from '@/api/execution'
import { useAuthStore } from '@/stores/auth'
import { parseExecutionTimeline, type TimelineItem } from '@/utils/executionTimeline'
import InkEmptyState from '@/components/InkEmptyState.vue'
import { filterExecutions, paginateExecutions } from '@/utils/executionFilters'
import {
  loadExecutionMonitorPrefs,
  saveExecutionMonitorPrefs,
  type ExecutionPageSizeMode
} from '@/utils/executionMonitorPrefs'

type ActionName = 'retry' | 'resume' | 'approve' | 'reject'

const message = useMessage()
const authStore = useAuthStore()
const initialPrefs = loadExecutionMonitorPrefs()
const executionId = ref<number | null>(null)
const executions = ref<ExecutionLog[]>([])
const selectedExecution = ref<ExecutionLog | null>(null)
const recoveryInfo = ref<ExecutionRecoveryInfo | null>(null)
const timeline = ref<TimelineItem[]>([])
const statusFilter = ref<string>(initialPrefs.status)
const loading = ref(false)
const detailLoading = ref(false)
const actionLoading = ref<ActionName | null>(null)
const keywordFilter = ref(initialPrefs.keyword)
const sourceTypeFilter = ref(initialPrefs.sourceType)
const page = ref(initialPrefs.page)
const pageSizeMode = ref<ExecutionPageSizeMode>(initialPrefs.pageSizeMode)
const customPageSize = ref(initialPrefs.customPageSize)

const isAdmin = computed(() => authStore.userInfo?.roles?.includes('admin') ?? false)
const scopeText = computed(() => isAdmin.value ? '管理员可查看所有人的执行任务' : '当前仅展示你触发的执行任务')

const statusOptions = [
  { label: '全部状态', value: 'ALL' },
  { label: '运行中', value: 'RUNNING' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILED' },
  { label: '等待审批', value: 'WAITING_APPROVAL' }
]

const sourceOptions = [
  { label: '全部来源', value: 'ALL' },
  { label: '工作流', value: 'WORKFLOW' },
  { label: 'Agent', value: 'AGENT' },
  { label: '工作流引用', value: 'WORKFLOW_REF' }
]

const pageSizeOptions = [
  { label: '5', value: 5 },
  { label: '10', value: 10 },
  { label: '20', value: 20 },
  { label: '50', value: 50 },
  { label: '100', value: 100 },
  { label: '自定义', value: 'CUSTOM' }
]

const pageSize = computed(() => {
  if (pageSizeMode.value !== 'CUSTOM') {
    return pageSizeMode.value
  }
  return Math.max(1, customPageSize.value || 1)
})

const filteredExecutions = computed(() => {
  return filterExecutions(executions.value, {
    status: statusFilter.value,
    sourceType: sourceTypeFilter.value,
    keyword: keywordFilter.value
  })
})

const pagedExecutions = computed(() => paginateExecutions(filteredExecutions.value, {
  page: page.value,
  pageSize: pageSize.value
}))

function statusType(status: string) {
  if (status === 'FAILED') return 'error'
  if (status === 'WAITING_APPROVAL') return 'warning'
  if (status === 'SUCCESS') return 'success'
  return 'info'
}

function statusText(status: string) {
  const map: Record<string, string> = {
    RUNNING: '运行中',
    SUCCESS: '成功',
    FAILED: '失败',
    WAITING_APPROVAL: '等待审批'
  }
  return map[status] || status
}

async function loadExecutions() {
  loading.value = true
  try {
    const response = await executionApi.listExecutions()
    executions.value = response.data.data || []
    if (selectedExecution.value) {
      selectedExecution.value = executions.value.find(item => item.id === selectedExecution.value?.id) || selectedExecution.value
    }
  } finally {
    loading.value = false
  }
}

async function loadById() {
  if (!executionId.value) return
  await loadExecutionDetail(executionId.value)
}

async function selectExecution(item: ExecutionLog) {
  executionId.value = item.id
  await loadExecutionDetail(item.id, item)
}

async function loadExecutionDetail(id: number, knownLog?: ExecutionLog) {
  detailLoading.value = true
  try {
    const [recoveryResponse, executionResponse] = await Promise.all([
      executionApi.getRecoveryInfo(id),
      executionApi.getExecution(id)
    ])
    selectedExecution.value = executionResponse.data.data || knownLog || null
    recoveryInfo.value = recoveryResponse.data.data
    timeline.value = parseExecutionTimeline(selectedExecution.value?.nodeLogs)
  } finally {
    detailLoading.value = false
  }
}

async function runAction(action: ActionName) {
  if (!selectedExecution.value) return
  actionLoading.value = action
  try {
    if (action === 'retry') {
      await executionApi.retry(selectedExecution.value.id)
    } else if (action === 'resume' && recoveryInfo.value?.checkpointRef) {
      await executionApi.resume(recoveryInfo.value.checkpointRef)
    } else if (action === 'approve') {
      await executionApi.approve(selectedExecution.value.id)
    } else if (action === 'reject') {
      await executionApi.reject(selectedExecution.value.id, 'frontend rejected')
    }
    message.success('操作已提交')
    await loadExecutions()
    await loadExecutionDetail(selectedExecution.value.id)
  } finally {
    actionLoading.value = null
  }
}

function formatDate(value: string | null | undefined) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}

function formatDuration(value: number | null | undefined) {
  if (value === null || value === undefined) return '-'
  if (value < 1000) return `${value}ms`
  return `${(value / 1000).toFixed(2)}s`
}

function triggerUserText(item: ExecutionLog | null) {
  if (!item) return '-'
  return item.triggeredByUsername || (item.triggeredBy ? `用户 ${item.triggeredBy}` : '-')
}

function formatPayload(value: Record<string, unknown>) {
  return JSON.stringify(value, null, 2)
}

watch([statusFilter, sourceTypeFilter, keywordFilter, pageSizeMode, customPageSize], () => {
  page.value = 1
})

watch(filteredExecutions, () => {
  const maxPage = Math.max(1, Math.ceil(filteredExecutions.value.length / pageSize.value))
  if (page.value > maxPage) {
    page.value = maxPage
  }
})

watch([keywordFilter, statusFilter, sourceTypeFilter, page, pageSizeMode, customPageSize], () => {
  saveExecutionMonitorPrefs({
    keyword: keywordFilter.value,
    status: statusFilter.value,
    sourceType: sourceTypeFilter.value,
    page: page.value,
    pageSizeMode: pageSizeMode.value,
    customPageSize: customPageSize.value || 1
  })
})

onMounted(() => {
  loadExecutions()
})
</script>

<style scoped lang="scss">
.execution-monitor {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
}

.execution-monitor__toolbar {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.execution-monitor__toolbar-main h2 {
  margin: 0;
  font-size: 20px;
  color: var(--color-text-primary);
}

.execution-monitor__toolbar-main span {
  display: block;
  margin-top: 4px;
  color: var(--color-text-tertiary);
  font-size: 13px;
}

.execution-monitor__toolbar-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.execution-monitor__keyword-filter {
  width: 280px;
}

.execution-monitor__status-filter {
  width: 132px;
}

.execution-monitor__source-filter {
  width: 132px;
}

.execution-monitor__input {
  width: 160px;
}

.execution-monitor__panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-base);
  background: var(--color-bg-secondary);
}

.execution-monitor__summary,
.execution-monitor__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.execution-monitor__summary-muted,
.execution-monitor__muted {
  color: var(--color-text-tertiary);
  font-size: 12px;
}

.execution-monitor__muted {
  margin-left: 6px;
}

.execution-monitor__table-wrap {
  overflow: auto;
}

.execution-monitor__table {
  width: 100%;
  border-collapse: collapse;
  min-width: 880px;
  font-size: 13px;
}

.execution-monitor__table th,
.execution-monitor__table td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--color-border-light);
  text-align: left;
  white-space: nowrap;
}

.execution-monitor__table th {
  color: var(--color-text-tertiary);
  font-weight: 500;
}

.execution-monitor__table tbody tr {
  cursor: pointer;
}

.execution-monitor__table tbody tr:hover,
.execution-monitor__row--active {
  background: rgba(59, 130, 246, 0.08);
}

.execution-monitor__pagination {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}

.execution-monitor__page-size {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.execution-monitor__page-size-select {
  width: 88px;
}

.execution-monitor__custom-page-size {
  width: 92px;
}

.execution-monitor__details {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 0;
}

.execution-monitor__details div {
  min-width: 0;
}

.execution-monitor__details dt {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.execution-monitor__details dd {
  margin: 4px 0 0;
  word-break: break-word;
}

.execution-monitor__error {
  margin: 0;
  color: var(--color-error);
}

.execution-monitor__timeline {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.execution-monitor__timeline h3 {
  margin: 0;
  font-size: 14px;
  color: var(--color-text-primary);
}

.execution-monitor__events {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.execution-monitor__event {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-base);
  background: rgba(255, 255, 255, 0.025);
}

.execution-monitor__event-dot {
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--color-text-tertiary);
  flex-shrink: 0;
}

.execution-monitor__event--success .execution-monitor__event-dot {
  background: #22c55e;
}

.execution-monitor__event--failed .execution-monitor__event-dot {
  background: #ef4444;
}

.execution-monitor__event--running .execution-monitor__event-dot,
.execution-monitor__event--waiting_approval .execution-monitor__event-dot {
  background: #f59e0b;
}

.execution-monitor__event-main {
  min-width: 0;
  flex: 1;
}

.execution-monitor__event-title,
.execution-monitor__event-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.execution-monitor__event-title {
  color: var(--color-text-primary);
  font-size: 13px;
}

.execution-monitor__event-meta {
  margin-top: 4px;
  color: var(--color-text-tertiary);
  font-size: 12px;
}

.execution-monitor__event-error {
  margin: 6px 0 0;
  color: var(--color-error);
  font-size: 12px;
}

.execution-monitor__event-output {
  margin: 6px 0 0;
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}

.execution-monitor__payloads {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 8px;
}

.execution-monitor__payload {
  min-width: 0;
}

.execution-monitor__payload span {
  display: block;
  margin-bottom: 4px;
  color: var(--color-text-tertiary);
  font-size: 12px;
}

.execution-monitor__payload pre {
  max-height: 220px;
  margin: 0;
  padding: 8px;
  overflow: auto;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-base);
  background: rgba(15, 23, 42, 0.24);
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.execution-monitor__steps {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
}

.execution-monitor__step {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 8px;
  padding: 6px 8px;
  border-radius: var(--radius-base);
  background: rgba(255, 255, 255, 0.035);
  color: var(--color-text-tertiary);
  font-size: 12px;
}

.execution-monitor__step span {
  min-width: 0;
  overflow-wrap: anywhere;
}

@media (max-width: 900px) {
  .execution-monitor__toolbar {
    flex-direction: column;
  }

  .execution-monitor__toolbar-actions {
    justify-content: flex-start;
    width: 100%;
  }

  .execution-monitor__keyword-filter {
    width: min(100%, 320px);
  }

  .execution-monitor__details {
    grid-template-columns: 1fr;
  }

  .execution-monitor__payloads {
    grid-template-columns: 1fr;
  }
}
</style>
