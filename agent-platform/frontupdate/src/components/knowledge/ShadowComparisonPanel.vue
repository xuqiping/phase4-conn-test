<template>
  <n-card title="Champion / Challenger 真实对比" size="small">
    <n-space vertical>
      <n-space>
        <n-input-number v-model:value="kbId" data-test="shadow-kb-id" :min="1" placeholder="知识库 ID" />
        <n-select v-model:value="status" clearable :options="statusOptions" placeholder="全部状态" style="width: 180px" />
        <n-button data-test="load-shadows" type="primary" :disabled="!kbId" :loading="loading" @click="load">
          查询对比
        </n-button>
      </n-space>
      <n-alert type="info">仅展示 Trace、配置版本、状态、证据 ID、成本和安全错误摘要，不展示 Query、Prompt 或 Chunk 正文。</n-alert>
      <n-empty v-if="!loading && rows.length === 0" description="暂无 Shadow 对比记录" />
      <n-data-table v-else :columns="columns" :data="rows" :pagination="{ pageSize: 10 }" :scroll-x="1300" />
    </n-space>
  </n-card>
</template>

<script setup lang="ts">
import { h, ref } from 'vue'
import { NAlert, NButton, NCard, NDataTable, NEmpty, NInputNumber, NSelect, NSpace, NTag, useMessage } from 'naive-ui'
import { knowledgeApi, type ShadowComparison } from '@/api/knowledge'

const kbId = ref<number | null>(null)
const status = ref<string | null>(null)
const loading = ref(false)
const rows = ref<ShadowComparison[]>([])
const message = useMessage()
const statusOptions = ['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'BUDGET_EXHAUSTED', 'BUDGET_EXCEEDED', 'SKIPPED']
  .map(value => ({ label: value, value }))
const columns = [
  { title: '状态', key: 'status', width: 150, render: (row: ShadowComparison) => h(NTag, { type: row.status === 'SUCCEEDED' ? 'success' : 'warning' }, { default: () => row.status }) },
  { title: 'Champion 版本', key: 'championVersion', width: 150 },
  { title: 'Challenger 版本', key: 'challengerVersion', width: 160 },
  { title: 'Champion Trace', key: 'championTraceId', width: 220 },
  { title: 'Challenger Trace', key: 'challengerTraceId', width: 220 },
  { title: '排序证据 ID', key: 'rankedChunkIds', width: 200, render: (row: ShadowComparison) => row.rankedChunkIds.join(', ') || '-' },
  { title: '成本', key: 'cost', width: 90 },
  { title: '错误摘要', key: 'errorSummary', width: 160 },
  { title: '时间', key: 'createdAt', width: 190 }
]

async function load() {
  if (!kbId.value) return
  loading.value = true
  try {
    rows.value = (await knowledgeApi.listShadowComparisons(kbId.value, status.value || undefined, 50)).data.data
  } catch {
    message.error('Shadow 对比记录加载失败')
  } finally {
    loading.value = false
  }
}
</script>
