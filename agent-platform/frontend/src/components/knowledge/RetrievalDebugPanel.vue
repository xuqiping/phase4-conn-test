<template>
  <div class="rag-debug">
    <!-- 输入区 -->
    <div class="rag-debug__form">
      <div class="rag-debug__row">
        <n-select
          v-model:value="form.kbId"
          :options="kbOptions"
          filterable
          placeholder="选择知识库"
          style="width: 260px"
        />
        <n-input-number
          v-model:value="form.maxL0"
          :min="1"
          :max="100"
          placeholder="maxL0（可选）"
          clearable
          style="width: 160px"
        />
        <n-input
          v-model:value="docTypesText"
          placeholder="docTypes 过滤（逗号分隔，可选）"
          style="width: 260px"
        />
      </div>
      <n-input
        v-model:value="form.query"
        type="textarea"
        :rows="2"
        placeholder="输入检索 query，如：如何安装部署系统"
      />
      <div class="rag-debug__row">
        <n-button type="primary" :loading="loading" :disabled="!canRun" @click="run">检索</n-button>
        <span v-if="result" class="rag-debug__meta">trace {{ result.traceId.slice(0, 8) }} · {{ result.latencyMs }}ms</span>
      </div>
    </div>

    <n-spin :show="loading">
      <div v-if="!result && !errored" class="rag-debug__empty">
        <n-empty description="输入 query 后点击「检索」查看候选 L0 / 证据 L2 / 引用 / token 预算" />
      </div>

      <div v-else-if="result" class="rag-debug__result">
        <n-alert
          :type="result.abstained ? 'warning' : 'success'"
          :title="result.abstained ? `拒答（${result.abstainReason || ''}）` : '命中（SUPPORTED）'"
          style="margin-bottom: var(--spacing-3)"
        />

        <div class="rag-debug__section">
          <h4 class="rag-debug__section-title">回答</h4>
          <pre class="rag-debug__answer">{{ result.answer }}</pre>
        </div>

        <div v-if="result.citations.length" class="rag-debug__section">
          <h4 class="rag-debug__section-title">引用</h4>
          <n-data-table :columns="citationCols" :data="result.citations" :pagination="false" size="small" />
        </div>

        <div v-if="result.candidatesL0.length" class="rag-debug__section">
          <h4 class="rag-debug__section-title">候选 L0（dense 召回）</h4>
          <n-data-table :columns="l0Cols" :data="result.candidatesL0" :pagination="false" size="small" />
        </div>

        <div v-if="result.evidenceL2.length" class="rag-debug__section">
          <h4 class="rag-debug__section-title">证据 L2（注入 prompt）</h4>
          <n-data-table :columns="l2Cols" :data="result.evidenceL2" :pagination="false" size="small" />
        </div>

        <div class="rag-debug__section">
          <h4 class="rag-debug__section-title">Token 预算</h4>
          <div class="rag-debug__budget">
            <span>prompt <b>{{ result.tokenBudget.promptTokens }}</b></span>
            <span>cap <b>{{ result.tokenBudget.effectiveContextCap }}</b></span>
            <span>maxCtx <b>{{ result.tokenBudget.maxContextTokens }}</b></span>
            <span>modelMax <b>{{ result.tokenBudget.modelMaxContext }}</b></span>
            <span>reserve <b>{{ result.tokenBudget.answerTokenReserve }}</b></span>
          </div>
        </div>
      </div>
    </n-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  NAlert, NButton, NDataTable, NEmpty, NInput, NInputNumber, NSelect, NSpin, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { useKnowledgeStore } from '@/stores/knowledge'
import {
  knowledgeApi,
  type RagRetrieveVO,
  type RagCitation,
  type RagRecallHit,
  type RagEvidence
} from '@/api/knowledge'

const store = useKnowledgeStore()
const message = useMessage()

const form = ref({
  kbId: null as number | null,
  query: '',
  maxL0: null as number | null
})
const docTypesText = ref('')
const result = ref<RagRetrieveVO | null>(null)
const loading = ref(false)
const errored = ref(false)

const kbOptions = computed(() =>
  store.bases.filter(b => b.canRead).map(b => ({ label: b.name, value: b.id }))
)
const canRun = computed(() => !!form.value.kbId && form.value.query.trim().length > 0)

const citationCols: DataTableColumns<RagCitation> = [
  { title: '[n]', key: 'index', width: 60 },
  { title: '文档ID', key: 'documentId', width: 80 },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  { title: '节点ID', key: 'nodeId', width: 90 }
]

const l0Cols: DataTableColumns<RagRecallHit> = [
  { title: '节点ID', key: 'nodeId', width: 90 },
  { title: '文档ID', key: 'documentId', width: 80 },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  {
    title: 'cosSim', key: 'cosineSimilarity', width: 100,
    sorter: (a, b) => a.cosineSimilarity - b.cosineSimilarity,
    render: r => r.cosineSimilarity.toFixed(4)
  }
]

const l2Cols: DataTableColumns<RagEvidence> = [
  { title: '[n]', key: 'citationIndex', width: 60 },
  { title: '节点ID', key: 'nodeId', width: 90 },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  { title: '类型', key: 'docType', width: 80 },
  { title: 'rerank', key: 'rerankScore', width: 90, render: r => r.rerankScore.toFixed(4) },
  { title: '内容', key: 'content', ellipsis: { tooltip: true }, render: r => r.content }
]

async function run() {
  if (!canRun.value) return
  loading.value = true
  errored.value = false
  result.value = null
  try {
    const docTypes = docTypesText.value.trim()
      ? docTypesText.value.split(',').map(s => s.trim()).filter(Boolean)
      : undefined
    const res = await knowledgeApi.retrieve({
      kbId: form.value.kbId!,
      query: form.value.query.trim(),
      maxL0: form.value.maxL0 || undefined,
      docTypes,
      mode: 'BALANCED'
    })
    result.value = res.data.data
  } catch {
    errored.value = true
    message.error('检索失败')
  } finally {
    loading.value = false
  }
}
</script>

<style lang="scss" scoped>
.rag-debug {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}
.rag-debug__form {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}
.rag-debug__row {
  display: flex;
  gap: var(--spacing-2);
  align-items: center;
}
.rag-debug__meta {
  color: var(--color-text-tertiary);
  font-size: 12px;
}
.rag-debug__empty {
  padding: var(--spacing-6);
}
.rag-debug__result {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}
.rag-debug__section {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-3);
}
.rag-debug__section-title {
  margin: 0 0 var(--spacing-2);
  font-size: 14px;
  color: var(--color-text-primary);
}
.rag-debug__answer {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-text-primary);
  font-family: inherit;
}
.rag-debug__budget {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-3);
  color: var(--color-text-secondary);
  font-size: 13px;
  b { color: var(--color-text-primary); }
}
</style>
