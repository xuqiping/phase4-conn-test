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
        <n-input v-model:value="filters.username" placeholder="账号(模糊)" clearable style="width:130px" />
        <n-select v-model:value="filters.module" :options="moduleOptions" placeholder="模块" clearable style="width:140px" />
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

      <!-- detailJson 查看弹窗（8x-2：键值中文渲染，不再堆原始 JSON） -->
      <n-modal v-model:show="showDetail" preset="card" title="审计详情" style="max-width:640px">
        <DetailKvView :raw="detailText" />
        <!-- 8x Chunk7：模型调用动作 → 一键跳「账单总览·调用明细」查同请求模型/token/积分明细 -->
        <div v-if="drillUrl" class="audit-log__drill">
          <n-button size="small" type="primary" @click="openDrill">查看调用明细</n-button>
          <span class="audit-log__drill-hint">
            {{ drillByKey }}
          </span>
        </div>
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
import DetailKvView from '@/components/DetailKvView.vue'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'

const message = useMessage()
const authStore = useAuthStore()
const router = useRouter()
/** 菜单隐藏(hasPermission) + 页内 canView + API 403 三重兜底 */
const canView = computed(() => authStore.hasPermission('system:audit:read'))

const loading = ref(false)
const rows = ref<AuditLogVO[]>([])
const showDetail = ref(false)
const detailText = ref('')
const detailRow = ref<AuditLogVO | null>(null)
const timeRange = ref<[number, number] | null>(null)

/**
 * 8x Chunk7：模型调用动作的调用明细 drill-down URL。
 * chat 行（chat_completed/send_message）按 traceId 过滤；media 行（video/image_gen_success）按 targetId=taskId 过滤。
 * 旧数据无 traceId/taskId → drillUrl=null（按钮不显示，#9 边界③）。
 */
const drillUrl = computed(() => {
  const r = detailRow.value
  if (!r) return ''
  if (r.module === 'chat' && r.traceId) return `/admin/billing?traceId=${encodeURIComponent(r.traceId)}`
  if (r.module === 'media' && r.targetId != null) return `/admin/billing?taskId=${r.targetId}`
  return ''
})
const drillByKey = computed(() => {
  const r = detailRow.value
  if (!r) return ''
  if (r.module === 'chat' && r.traceId) return `按 traceId 关联本次对话的模型调用`
  if (r.module === 'media' && r.targetId != null) return `按任务 id 关联本次生成的模型调用`
  return ''
})
function openDrill() {
  if (!drillUrl.value) return
  // 新窗口打开账单总览（预填 traceId/taskId + 自动切到调用明细 tab）
  const href = router.resolve(drillUrl.value).href
  window.open(href, '_blank')
}

const filters = reactive({
  userId: null as number | null,
  username: '',
  module: null as string | null,
  action: '',
  result: null as string | null,
  traceId: ''
})

const pagination = reactive({ page: 1, pageSize: 20, itemCount: 0 })

// 模块下拉与后端实际 module 码对齐（修正旧 knowledge→kb 不匹配 bug，#3 补全）
const moduleOptions = [
  { label: '认证', value: 'auth' },
  { label: '用户', value: 'user' },
  { label: '角色权限', value: 'role' },
  { label: '智能体', value: 'agent' },
  { label: '知识库', value: 'kb' },
  { label: '积分计费', value: 'billing' },
  { label: '系统设置', value: 'system' },
  { label: '资产库', value: 'asset' },
  { label: '记忆', value: 'memory' },
  { label: '媒体生成', value: 'media' },
  { label: '模型供应商', value: 'llm' },
  { label: '智能对话', value: 'chat' },
  { label: '无限画布', value: 'canvas' },
  // 8x-2 B3：与后端 MODULE_LABEL 键集对齐（AuditLabelDictionaryCompletenessTest 同源）
  { label: '文件', value: 'file' },
  { label: '安全管理', value: 'security' },
  { label: '公告建议台', value: 'feedback' },
  { label: '项目组', value: 'project-group' },
  { label: '审计链', value: 'audit' }
]

const resultOptions = [
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAIL' }
]

const columns: DataTableColumns<AuditLogVO> = [
  { title: 'ID', key: 'id', width: 70 },
  {
    title: '时间', key: 'createdAt', width: 170,
    render: (row) => row.createdAt ? new Date(row.createdAt).toLocaleString('zh-CN') : '-'
  },
  { title: '用户', key: 'username', width: 110, ellipsis: { tooltip: true },
    // 8x-1：后端已按 userId 回填，双空（系统级操作）兜底「系统」，不再显示"-"
    render: (row) => row.username || (row.userId ? `用户#${row.userId}` : '系统') },
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
      onClick: () => { detailText.value = row.detailJson || ''; detailRow.value = row; showDetail.value = true }
    }, () => '查看')
  }
]

async function loadLogs(page = 1) {
  loading.value = true
  try {
    const res = await auditApi.list({
      userId: filters.userId ?? undefined,
      username: filters.username || undefined,
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
  filters.username = ''
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

  &__drill {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 12px;
    padding-top: 12px;
    border-top: 1px solid var(--color-border, rgba(255, 255, 255, 0.08));
  }

  &__drill-hint {
    font-size: 12px;
    color: var(--color-text-secondary);
  }
}
</style>
