<!-- ============================================================
  检索审计面板 — rag_retrieval_logs（knowledge:manage，管理员）
  分页表 + 过滤（userId/kbId/mode/时间范围）+ 行删 + 按时间批量清 + 详情抽屉
  ============================================================ -->
<template>
  <div class="retrieval-audit">
    <!-- 过滤栏 -->
    <div class="retrieval-audit__filters">
      <n-input-number
        v-model:value="filter.userId"
        size="small"
        placeholder="用户ID"
        :show-button="false"
        clearable
        class="retrieval-audit__filter-item"
      />
      <n-input-number
        v-model:value="filter.kbId"
        size="small"
        placeholder="KB ID"
        :show-button="false"
        clearable
        class="retrieval-audit__filter-item"
      />
      <n-select
        v-model:value="filter.mode"
        size="small"
        placeholder="模式"
        :options="modeOptions"
        clearable
        class="retrieval-audit__filter-item"
      />
      <n-date-picker
        v-model:value="filter.range"
        type="datetimerange"
        size="small"
        clearable
        class="retrieval-audit__filter-item retrieval-audit__filter-item--wide"
      />
      <n-button size="small" type="primary" @click="onSearch">查询</n-button>
      <n-button size="small" @click="onReset">重置</n-button>
      <div style="flex:1" />
      <n-button size="small" quaternary type="warning" @click="confirmPurge">按时间清理</n-button>
    </div>

    <!-- 审计表 -->
    <n-data-table
      :columns="columns"
      :data="records"
      :loading="loading"
      :pagination="pagination"
      remote
      striped
      @update:page="onPage"
    />

    <!-- 详情抽屉 -->
    <n-drawer v-model:show="showDetail" :width="640" placement="right">
      <n-drawer-content :title="`检索记录 #${detail?.id || ''}`" closable>
        <div v-if="detail" class="retrieval-audit__detail">
          <n-descriptions :column="1" label-placement="left" bordered size="small">
            <n-descriptions-item label="traceId">{{ detail.traceId || '-' }}</n-descriptions-item>
            <n-descriptions-item label="用户">{{ detail.userId ?? '-' }}（{{ detail.identityType || '-' }}）</n-descriptions-item>
            <n-descriptions-item label="KB">{{ detail.kbIds || '-' }}</n-descriptions-item>
            <n-descriptions-item label="模式">{{ detail.mode || '-' }}</n-descriptions-item>
            <n-descriptions-item label="verdict">{{ detail.cragVerdict || '-' }}</n-descriptions-item>
            <n-descriptions-item label="延迟">{{ detail.latencyMs ?? '-' }} ms</n-descriptions-item>
            <n-descriptions-item label="BM25 fallback">{{ detail.l2LexicalFallback ? '是' : '否' }}</n-descriptions-item>
            <n-descriptions-item label="时间">{{ formatTime(detail.createdAt) }}</n-descriptions-item>
            <n-descriptions-item label="查询">{{ detail.query || '-' }}</n-descriptions-item>
          </n-descriptions>
          <div v-if="detail.tokenBudget" class="retrieval-audit__json">
            <div class="retrieval-audit__json-label">token 预算</div>
            <pre>{{ pretty(detail.tokenBudget) }}</pre>
          </div>
          <div v-if="detail.candidatesL0" class="retrieval-audit__json">
            <div class="retrieval-audit__json-label">候选 L0</div>
            <pre>{{ pretty(detail.candidatesL0) }}</pre>
          </div>
          <div v-if="detail.evidenceL2" class="retrieval-audit__json">
            <div class="retrieval-audit__json-label">证据 L2</div>
            <pre>{{ pretty(detail.evidenceL2) }}</pre>
          </div>
        </div>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, reactive, ref } from 'vue'
import {
  NButton, NDataTable, NDescriptions, NDescriptionsItem, NDrawer, NDrawerContent,
  NInputNumber, NSelect, NDatePicker, NSpace, NTag, useDialog, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { knowledgeApi, type RagRetrievalLog } from '@/api/knowledge'

const message = useMessage()
const dialog = useDialog()

const loading = ref(false)
const records = ref<RagRetrievalLog[]>([])
const detail = ref<RagRetrievalLog | null>(null)
const showDetail = ref(false)

const filter = reactive<{ userId: number | null; kbId: number | null; mode: string | null; range: [number, number] | null }>({
  userId: null, kbId: null, mode: null, range: null
})

const modeOptions = [
  { label: 'CHAT', value: 'CHAT' },
  { label: 'AGENT', value: 'AGENT' },
  { label: 'WORKFLOW', value: 'WORKFLOW' },
  { label: 'DEBUG', value: 'DEBUG' }
]

const pagination = reactive({
  page: 1, pageSize: 15, itemCount: 0, showSizePicker: false,
  prefix: (info: { itemCount?: number }) => `共 ${info.itemCount ?? 0} 条`
})

const verdictType: Record<string, 'success' | 'warning' | 'error' | 'default'> = {
  SUPPORTED: 'success', LOW_CONFIDENCE: 'warning', NO_DENSE_HITS: 'default',
  NO_VISIBLE_DOCS: 'default', CITATION_CHECK_FAIL: 'error', ERROR: 'error', CACHE_HIT: 'success'
}

const columns: DataTableColumns<RagRetrievalLog> = [
  { title: 'ID', key: 'id', width: 70 },
  {
    title: '时间', key: 'createdAt', width: 160,
    render: r => formatTime(r.createdAt)
  },
  { title: '用户', key: 'userId', width: 80, render: r => r.userId ?? '-' },
  { title: '模式', key: 'mode', width: 90, render: r => r.mode || '-' },
  {
    title: 'verdict', key: 'cragVerdict', width: 150,
    render: r => h(NTag, { size: 'small', type: verdictType[r.cragVerdict || ''] || 'default', bordered: false }, () => r.cragVerdict || '-')
  },
  { title: '查询', key: 'query', ellipsis: { tooltip: true }, render: r => r.query || '-' },
  { title: '延迟', key: 'latencyMs', width: 90, render: r => r.latencyMs != null ? `${r.latencyMs}ms` : '-' },
  { title: 'KB', key: 'kbIds', width: 90, ellipsis: { tooltip: true }, render: r => r.kbIds || '-' },
  {
    title: '操作', key: 'actions', width: 140, fixed: 'right',
    render: r => h(NSpace, { size: 4 }, () => [
      h(NButton, { size: 'small', onClick: () => openDetail(r) }, () => '详情'),
      h(NButton, { size: 'small', quaternary: true, type: 'error', onClick: () => confirmDelete(r) }, () => '删除')
    ])
  }
]

function formatTime(iso: string): string {
  try { return new Date(iso).toLocaleString('zh-CN') } catch { return iso }
}

function pretty(json: string | null): string {
  try { return JSON.stringify(JSON.parse(json || '{}'), null, 2) } catch { return json || '' }
}

function buildQuery() {
  return {
    page: pagination.page,
    size: pagination.pageSize,
    userId: filter.userId ?? undefined,
    kbId: filter.kbId ?? undefined,
    mode: filter.mode ?? undefined,
    from: filter.range ? new Date(filter.range[0]).toISOString() : undefined,
    to: filter.range ? new Date(filter.range[1]).toISOString() : undefined
  }
}

async function load() {
  loading.value = true
  try {
    const res = await knowledgeApi.pageRetrievalLogs(buildQuery())
    records.value = res.data.data.records
    pagination.itemCount = res.data.data.total
  } catch {
    message.error('加载审计失败')
  } finally {
    loading.value = false
  }
}

function onSearch() { pagination.page = 1; void load() }
function onReset() {
  filter.userId = null; filter.kbId = null; filter.mode = null; filter.range = null
  pagination.page = 1; void load()
}
function onPage(p: number) { pagination.page = p; void load() }

function openDetail(r: RagRetrievalLog) { detail.value = r; showDetail.value = true }

function confirmDelete(r: RagRetrievalLog) {
  dialog.warning({
    title: '确认删除',
    content: `删除检索记录 #${r.id}？`,
    positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await knowledgeApi.deleteRetrievalLog(r.id)
        message.success('已删除')
        await load()
      } catch { message.error('删除失败') }
    }
  })
}

function confirmPurge() {
  dialog.warning({
    title: '按时间批量清理',
    content: '清理早于指定时间的检索记录。建议输入较早时间（如 7 天前）。格式：ISO-8601 或 yyyy-MM-dd。确认后不可恢复。',
    positiveText: '清理', negativeText: '取消',
    onPositiveClick: async () => {
      // 默认清理 7 天前；保守默认，管理员按需
      const before = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString()
      try {
        const res = await knowledgeApi.deleteRetrievalLogsBefore(before)
        message.success(`已清理 ${res.data.data} 条早于 7 天前的记录`)
        await load()
      } catch { message.error('清理失败') }
    }
  })
}

onMounted(() => { void load() })
</script>

<style lang="scss" scoped>
.retrieval-audit {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}
.retrieval-audit__filters {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  flex-wrap: wrap;
}
.retrieval-audit__filter-item { width: 130px; }
.retrieval-audit__filter-item--wide { width: 340px; }
.retrieval-audit__detail {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}
.retrieval-audit__json {
  pre {
    margin: 4px 0 0;
    padding: var(--spacing-2);
    max-height: 240px;
    overflow: auto;
    background: var(--color-bg-secondary, rgba(255, 255, 255, 0.04));
    border-radius: 6px;
    font-size: 12px;
    line-height: 1.5;
    white-space: pre-wrap;
    word-break: break-all;
  }
}
.retrieval-audit__json-label {
  font-size: 12px;
  color: var(--color-text-secondary, #999);
}
</style>
