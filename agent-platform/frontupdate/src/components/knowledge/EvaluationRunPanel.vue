<template>
  <n-space vertical :size="16">
    <n-card title="评测数据集" size="small">
      <n-form label-placement="left" label-width="90">
        <n-form-item label="知识库 ID">
          <n-input-number v-model:value="kbId" data-test="kb-id" :min="1" />
        </n-form-item>
        <n-form-item label="数据集名称">
          <n-input v-model:value="datasetName" data-test="dataset-name" placeholder="例如：生产回归集" />
        </n-form-item>
        <n-form-item label="说明">
          <n-input v-model:value="description" type="textarea" placeholder="数据集用途和维护范围" />
        </n-form-item>
        <n-button
          type="primary"
          data-test="create-dataset"
          :loading="creating"
          :disabled="!kbId || !datasetName.trim()"
          @click="createDataset"
        >
          创建数据集
        </n-button>
      </n-form>
      <n-alert v-if="datasetId" type="success" style="margin-top: 12px">
        数据集已创建，ID：{{ datasetId }}
      </n-alert>
    </n-card>

    <n-card title="导入评测用例（JSONL）" size="small">
      <n-input
        v-model:value="jsonl"
        data-test="jsonl"
        type="textarea"
        :autosize="{ minRows: 6, maxRows: 16 }"
        placeholder='每行一个 JSON，例如：{"queryType":"FACT","question":"退款期限？","expectedChunkIds":["123"]}'
      />
      <n-space style="margin-top: 12px">
        <n-button
          type="primary"
          data-test="import-jsonl"
          :loading="importing"
          :disabled="!datasetId || !jsonl.trim()"
          @click="importCases"
        >
          导入用例
        </n-button>
        <n-tag v-if="importResult" type="success">已导入 {{ importResult.imported }} 条</n-tag>
      </n-space>
      <n-alert v-if="importResult?.errors.length" type="warning" style="margin-top: 12px">
        <div v-for="item in importResult.errors" :key="item.line">
          第 {{ item.line }} 行：{{ item.message }}
        </div>
      </n-alert>
      <n-data-table v-if="cases.length" :columns="columns" :data="cases" style="margin-top: 12px" />
    </n-card>

    <n-card title="运行评测" size="small">
      <n-space vertical>
        <n-input v-model:value="pipelineVersion" data-test="pipeline-version" placeholder="Pipeline 版本，例如 pipeline-v2" />
        <n-button
          type="primary"
          data-test="start-run"
          :loading="startingRun"
          :disabled="!datasetId || !pipelineVersion.trim()"
          @click="startRun"
        >
          启动真实评测
        </n-button>
        <n-alert v-if="run" :type="run.status === 'FAILED' ? 'error' : 'info'">
          Run #{{ run.id }} · {{ run.status }}
          <template v-if="run.errorSummary"> · {{ run.errorSummary }}</template>
        </n-alert>
        <n-descriptions v-if="run && Object.keys(run.summaryMetrics).length" :column="3" bordered>
          <n-descriptions-item v-for="(value, key) in run.summaryMetrics" :key="key" :label="metricLabel(key)">
            {{ formatMetric(key, value) }}
          </n-descriptions-item>
        </n-descriptions>
      </n-space>
    </n-card>

    <n-alert type="info">
      评测任务在专用线程池异步执行；离开页面不会中断。完成后页面展示真实汇总指标。
    </n-alert>
  </n-space>
</template>

<script setup lang="ts">
import { h, ref } from 'vue'
import {
  NAlert, NButton, NCard, NDataTable, NDescriptions, NDescriptionsItem, NForm, NFormItem, NInput, NInputNumber,
  NSpace, NTag, useMessage, type DataTableColumns
} from 'naive-ui'
import { knowledgeApi, type EvaluationCase, type EvaluationImportResult, type EvaluationRun } from '@/api/knowledge'

const message = useMessage()
const kbId = ref<number | null>(null)
const datasetName = ref('')
const description = ref('')
const datasetId = ref<number | null>(null)
const jsonl = ref('')
const creating = ref(false)
const importing = ref(false)
const importResult = ref<EvaluationImportResult | null>(null)
const cases = ref<EvaluationCase[]>([])
const pipelineVersion = ref('')
const startingRun = ref(false)
const run = ref<EvaluationRun | null>(null)

const columns: DataTableColumns<EvaluationCase> = [
  { title: 'ID', key: 'id', width: 80 },
  { title: '类型', key: 'queryType', width: 120 },
  { title: '问题', key: 'question' },
  { title: '可回答', key: 'answerable', width: 90, render: row => h(NTag, { type: row.answerable ? 'success' : 'warning' }, { default: () => row.answerable ? '是' : '否' }) }
]

async function createDataset() {
  if (!kbId.value || !datasetName.value.trim()) return
  creating.value = true
  try {
    const response = await knowledgeApi.createEvaluationDataset({
      kbId: kbId.value,
      name: datasetName.value.trim(),
      description: description.value.trim() || undefined
    })
    datasetId.value = response.data.data.id
    cases.value = []
    importResult.value = null
    message.success('评测数据集创建成功')
  } finally {
    creating.value = false
  }
}

async function importCases() {
  if (!datasetId.value || !jsonl.value.trim()) return
  importing.value = true
  try {
    const response = await knowledgeApi.importEvaluationJsonl(datasetId.value, jsonl.value)
    importResult.value = response.data.data
    const caseResponse = await knowledgeApi.listEvaluationCases(datasetId.value)
    cases.value = caseResponse.data.data
    message.success(`已导入 ${importResult.value.imported} 条评测用例`)
  } finally {
    importing.value = false
  }
}

async function startRun() {
  if (!datasetId.value || !pipelineVersion.value.trim()) return
  startingRun.value = true
  try {
    const response = await knowledgeApi.startEvaluationRun(datasetId.value, pipelineVersion.value.trim())
    run.value = response.data.data
    await refreshRun()
  } finally {
    startingRun.value = false
  }
}

async function refreshRun() {
  if (!run.value) return
  const response = await knowledgeApi.getEvaluationRun(run.value.id)
  run.value = response.data.data
}

function metricLabel(key: string) {
  return ({ recall: 'Recall@10', mrr: 'MRR', ndcg: 'nDCG', citationCoverage: '引用覆盖',
    faithfulness: '忠实度', abstentionAccuracy: '拒答准确率', supportedRate: '充分支持率',
    caseCount: '用例数' } as Record<string, string>)[key] || key
}
function formatMetric(key: string, value: number) {
  return key === 'caseCount' ? String(value) : `${(value * 100).toFixed(1)}%`
}
</script>
