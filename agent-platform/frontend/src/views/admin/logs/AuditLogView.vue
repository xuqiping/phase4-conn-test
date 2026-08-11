<template>
  <div class="audit-log">
    <div class="audit-log__header">
      <h2>审计日志</h2>
      <span class="audit-log__hint">敏感操作留痕（登录/权限变更/删除/充值/设置修改），只追加不篡改</span>
    </div>

    <!-- 无权限兜底（菜单隐藏 + 路由可达时页内拦截；API 403 为最终防线） -->
    <n-empty v-if="!canView" description="无 system:audit:read 权限" style="margin-top:80px" />

    <template v-else>
      <!-- 筛选栏 -->
      <div class="audit-log__filters">
        <n-input-number v-model:value="filters.userId" placeholder="用户ID" clearable :show-button="false" style="width:110px" />
        <n-select v-model:value="filters.module" :options="moduleOptions" placeholder="模块" clearable style="width:130px" />
        <n-input v-model:value="filters.action" placeholder="动作(如 login)" clearable style="width:130px" />
        <n-select v-model:value="filters.result" :options="resultOptions" placeholder="结果" clearable style="width:100px" />
        <n-input v-model:value="filters.traceId" placeholder="traceId" clearable style="width:200px" />
        <n-date-picker v-model:value="timeRange" type="datetimerange" clearable style="width:340px" />
        <n-button type="primary" :loading="loading" @click="search">查询</n-button>
        <n-button @click="reset">重置</n-button>
      </div>

      <!-- 审计表格 -->
      <n-data-table
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :scroll-x="1400"
        remote
        striped
        @update:page="loadLogs"
      />

      <!-- detailJson 查看弹窗 -->
      <n-modal v-model:show="showDetail" preset="card" title="审计详情" style="max-width:640px">
        <pre class="audit-log__detail">{{ prettyDetail }}</pre>
      </n-modal>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, h } from 'vue'
import {
  NDataTable, NButton, NInput, NInputNumber, NSelect, NDatePicker, NModal, NEmpty, NTag, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { auditApi, type AuditLogVO } from '@/api/audit'
import { useAuthStore } from '@/stores/auth'

const message = useMessage()
const authStore = useAuthStore()
/** 菜单隐藏(hasPermission) + 页内 canView + API 403 三重兜底 */
const canView = computed(() => authStore.hasPermission('system:audit:read'))

const loading = ref(false)
const rows = ref<AuditLogVO[]>([])
const showDetail = ref(false)
const detailText = ref('')
const timeRange = ref<[number, number] | null>(null)

const filters = reactive({
  userId: null as number | null,
  module: null as string | null,
  action: '',
  result: null as string | null,
  traceId: ''
})

const pagination = reactive({ page: 1, pageSize: 20, itemCount: 0 })

const moduleOptions = [
  { label: '认证 auth', value: 'auth' },
  { label: '用户 user', value: 'user' },
  { label: '角色 role', value: 'role' },
  { label: 'Agent agent', value: 'agent' },
  { label: '知识库 knowledge', value: 'knowledge' },
  { label: '计费 billing', value: 'billing' },
  { label: '系统设置 system', value: 'system' }
]

const resultOptions = [
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAIL' }
]

const prettyDetail = computed(() => {
  try {
    return JSON.stringify(JSON.parse(detailText.value), null, 2)
  } catch {
    return detailText.value || '(无详情)'
  }
})

const columns: DataTableColumns<AuditLogVO> = [
  { title: 'ID', key: 'id', width: 70 },
  {
    title: '时间', key: 'createdAt', width: 170,
    render: (row) => row.createdAt ? new Date(row.createdAt).toLocaleString('zh-CN') : '-'
  },
  { title: '用户', key: 'username', width: 110, ellipsis: { tooltip: true },
    render: (row) => row.username || '-' },
  { title: '模块', key: 'module', width: 110,
    render: (row) => row.moduleLabel || row.module || '-' },
  { title: '动作', key: 'action', width: 130, ellipsis: { tooltip: true },
    render: (row) => row.actionLabel || row.action || '-' },
  {
    title: '对象', key: 'target', width: 150, ellipsis: { tooltip: true },
    render: (row) => row.targetType ? `${row.targetType}#${row.targetId ?? '-'}` : '-'
  },
  {
    title: '结果', key: 'result', width: 80,
    render: (row) => h(NTag, {
      size: 'small', round: true,
      type: row.result === 'SUCCESS' ? 'success' : 'error'
    }, () => row.result === 'SUCCESS' ? '成功' : '失败')
  },
  { title: 'IP', key: 'clientIp', width: 120, render: (row) => row.clientIp || '-' },
  {
    title: 'traceId', key: 'traceId', width: 140, ellipsis: { tooltip: true },
    render: (row) => row.traceId || '-'
  },
  {
    title: '详情', key: 'detail', width: 90, fixed: 'right',
    render: (row) => h(NButton, {
      size: 'small', quaternary: true,
      onClick: () => { detailText.value = row.detailJson || ''; showDetail.value = true }
    }, () => '查看')
  }
]

async function loadLogs(page = 1) {
  loading.value = true
  try {
    const res = await auditApi.list({
      userId: filters.userId ?? undefined,
      module: filters.module ?? undefined,
      action: filters.action || undefined,
      result: filters.result ?? undefined,
      traceId: filters.traceId || undefined,
      startTime: timeRange.value ? new Date(timeRange.value[0]).toISOString() : undefined,
      endTime: timeRange.value ? new Date(timeRange.value[1]).toISOString() : undefined,
      page,
      size: pagination.pageSize
    })
    rows.value = res.data.data.records
    pagination.itemCount = res.data.data.total
    pagination.page = page
  } catch {
    message.error('加载审计日志失败')
  } finally {
    loading.value = false
  }
}

function search() {
  loadLogs(1)
}

function reset() {
  filters.userId = null
  filters.module = null
  filters.action = ''
  filters.result = null
  filters.traceId = ''
  timeRange.value = null
  loadLogs(1)
}

onMounted(() => {
  if (canView.value) loadLogs()
})
</script>

<style lang="scss" scoped>
.audit-log {
  padding: 24px;

  &__header {
    display: flex;
    align-items: baseline;
    gap: 12px;
    margin-bottom: 16px;

    h2 { margin: 0; color: var(--color-text-primary); }
  }

  &__hint {
    font-size: 12px;
    color: var(--color-text-secondary);
  }

  &__filters {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-bottom: 16px;
  }

  &__detail {
    margin: 0;
    padding: 12px;
    background: var(--color-bg-secondary, rgba(255, 255, 255, 0.04));
    border-radius: 6px;
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-all;
    max-height: 50vh;
    overflow: auto;
  }
}
</style>
