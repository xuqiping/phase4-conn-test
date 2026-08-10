<template>
  <n-modal
    :show="show"
    preset="card"
    :title="`访问审批 · ${projectName}`"
    :closable="!hasActiveMutation"
    :mask-closable="!hasActiveMutation"
    :close-on-esc="!hasActiveMutation"
    style="max-width: 780px"
    @update:show="handleShowUpdate"
  >
    <div class="public-access-dialog">
      <div class="public-access-dialog__summary">
        审核公众池的只读使用申请。批准后可随时撤销访问。
      </div>

      <n-alert v-if="error" type="error" :show-icon="true" role="alert">{{ error }}</n-alert>

      <n-data-table
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="false"
        :scroll-x="660"
        size="small"
      >
        <template #empty>
          <n-empty v-if="!error" description="暂无访问申请" class="public-access-dialog__empty" />
          <span v-else aria-hidden="true" />
        </template>
      </n-data-table>
    </div>

    <template #action>
      <n-space justify="end">
        <n-button :disabled="hasActiveMutation" @click="handleShowUpdate(false)">完成</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import { NAlert, NButton, NDataTable, NEmpty, NModal, NSpace, NTag, useMessage } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { publicPoolApi } from '@/api/assets'
import type { PublicAccessRequestVO, PublicAccessStatus } from '@/types/asset'

const props = defineProps<{
  show: boolean
  projectId: number
  projectName: string
}>()

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void
  (e: 'changed'): void
}>()

type RowAction = 'approve' | 'reject' | 'revoke'
interface AccessRow extends PublicAccessRequestVO {
  applicant: string
  statusLabel: string
  actions: RowAction[]
}
interface DialogContext {
  projectId: number
  version: number
}

const message = useMessage()
const requests = ref<PublicAccessRequestVO[]>([])
const loading = ref(false)
const error = ref('')
const inFlightMutationKeys = ref<Set<string>>(new Set())
const actingRequestIds = computed(() => {
  const prefix = `${props.projectId}:`
  return [...inFlightMutationKeys.value]
    .filter((key) => key.startsWith(prefix))
    .map((key) => Number(key.slice(prefix.length)))
})
const hasActiveMutation = computed(() => actingRequestIds.value.length > 0)
let contextVersion = 0
let reloadVersion = 0

const STATUS_META: Record<PublicAccessStatus, { label: string; type: 'warning' | 'success' | 'error' | 'default' }> = {
  PENDING: { label: '待审批', type: 'warning' },
  APPROVED: { label: '已批准', type: 'success' },
  REJECTED: { label: '已拒绝', type: 'error' },
  REVOKED: { label: '已撤销', type: 'default' }
}

function actionsFor(status: PublicAccessStatus): RowAction[] {
  if (status === 'PENDING') return ['approve', 'reject']
  if (status === 'APPROVED') return ['revoke']
  return []
}

const rows = computed<AccessRow[]>(() =>
  requests.value.map((item) => ({
    ...item,
    applicant: `用户 #${item.applicantId}`,
    statusLabel: STATUS_META[item.status].label,
    actions: actionsFor(item.status)
  }))
)

const columns = computed<DataTableColumns<AccessRow>>(() => [
  { title: '申请人', key: 'applicant', minWidth: 140 },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: (row) => h(NTag, { size: 'small', bordered: false, type: STATUS_META[row.status].type }, () => row.statusLabel)
  },
  {
    title: '申请时间',
    key: 'createdAt',
    minWidth: 180,
    render: (row) => formatDate(row.createdAt)
  },
  {
    title: '操作',
    key: 'actions',
    width: 210,
    render: (row) => {
      if (row.actions.length === 0) {
        return h('span', { class: 'public-access-dialog__no-action' }, '无需操作')
      }
      const busy = actingRequestIds.value.includes(row.id)
      if (row.status === 'PENDING') {
        return h(NSpace, { size: 6 }, () => [
          h(
            NButton,
            { size: 'small', type: 'primary', loading: busy, disabled: busy, onClick: () => decide(row.id, 'APPROVED') },
            () => '批准'
          ),
          h(
            NButton,
            { size: 'small', type: 'error', secondary: true, disabled: busy, onClick: () => decide(row.id, 'REJECTED') },
            () => '拒绝'
          )
        ])
      }
      return h(
        NButton,
        { size: 'small', type: 'warning', secondary: true, loading: busy, disabled: busy, onClick: () => revoke(row.id) },
        () => '撤销访问'
      )
    }
  }
])

function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

function captureContext(): DialogContext {
  return { projectId: props.projectId, version: contextVersion }
}

function isCurrentContext(context: DialogContext) {
  return props.show && props.projectId === context.projectId && contextVersion === context.version
}

function handleShowUpdate(value: boolean) {
  if (!value && hasActiveMutation.value) return
  emit('update:show', value)
}

function resetState() {
  reloadVersion += 1
  requests.value = []
  loading.value = false
  error.value = ''
}

watch(
  [() => props.show, () => props.projectId],
  ([show, projectId]) => {
    contextVersion += 1
    resetState()
    if (show && projectId > 0) void reload({ projectId, version: contextVersion })
  },
  { immediate: true }
)

async function reload(context: DialogContext = captureContext()) {
  if (!isCurrentContext(context)) return
  const currentReloadVersion = ++reloadVersion
  loading.value = true
  error.value = ''
  try {
    const res = await publicPoolApi.listRequests(context.projectId)
    if (!isCurrentContext(context) || currentReloadVersion !== reloadVersion) return
    requests.value = res.data.data || []
  } catch {
    if (!isCurrentContext(context) || currentReloadVersion !== reloadVersion) return
    error.value = '申请列表加载失败，请稍后重试'
    message.error('刷新访问申请失败')
  } finally {
    if (isCurrentContext(context) && currentReloadVersion === reloadVersion) loading.value = false
  }
}

function mutationKey(projectId: number, requestId: number) {
  return `${projectId}:${requestId}`
}

function isMutationInFlight(projectId: number, requestId: number) {
  return inFlightMutationKeys.value.has(mutationKey(projectId, requestId))
}

function setActing(projectId: number, requestId: number, acting: boolean) {
  const next = new Set(inFlightMutationKeys.value)
  const key = mutationKey(projectId, requestId)
  if (acting) {
    next.add(key)
  } else {
    next.delete(key)
  }
  inFlightMutationKeys.value = next
}

async function decide(requestId: number, decision: 'APPROVED' | 'REJECTED') {
  const context = captureContext()
  if (!isCurrentContext(context) || isMutationInFlight(context.projectId, requestId)) return
  setActing(context.projectId, requestId, true)
  try {
    await publicPoolApi.decideRequest(context.projectId, requestId, { decision })
    if (!isCurrentContext(context)) return
    message.success(decision === 'APPROVED' ? '已批准访问申请' : '已拒绝访问申请')
    emit('changed')
    await reload(context)
  } catch {
    if (isCurrentContext(context)) message.error(decision === 'APPROVED' ? '批准失败，请重试' : '拒绝失败，请重试')
  } finally {
    setActing(context.projectId, requestId, false)
  }
}

async function revoke(requestId: number) {
  const context = captureContext()
  if (!isCurrentContext(context) || isMutationInFlight(context.projectId, requestId)) return
  setActing(context.projectId, requestId, true)
  try {
    await publicPoolApi.revokeApproval(context.projectId, requestId)
    if (!isCurrentContext(context)) return
    message.success('已撤销访问权限')
    emit('changed')
    await reload(context)
  } catch {
    if (isCurrentContext(context)) message.error('撤销访问失败，请重试')
  } finally {
    setActing(context.projectId, requestId, false)
  }
}

defineExpose({ rows, loading, error, reload, decide, revoke, hasActiveMutation, handleShowUpdate })
</script>

<style scoped lang="scss">
.public-access-dialog {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}

.public-access-dialog__summary {
  padding: var(--spacing-2) var(--spacing-3);
  border-left: 3px solid var(--color-primary);
  background: var(--color-bg-secondary);
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.public-access-dialog__empty {
  padding-block: var(--spacing-4);
}

:deep(.public-access-dialog__no-action) {
  color: var(--color-text-tertiary);
  font-size: 12px;
}
</style>
