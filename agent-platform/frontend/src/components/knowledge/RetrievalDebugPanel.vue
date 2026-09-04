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
        <n-checkbox v-model:checked="form.generateAnswer">
          生成答案（调 LLM，慢 10s+；默认只检索）
        </n-checkbox>
      </div>
      <div class="rag-debug__row">
        <n-button type="primary" :loading="loading" :disabled="!canRun" @click="run">检索</n-button>
        <span v-if="expansionBadge" class="rag-debug__badge">{{ expansionBadge }}</span>
        <span v-if="result" class="rag-debug__meta">trace {{ result.traceId.slice(0, 8) }} · {{ result.latencyMs }}ms</span>
      </div>
    </div>

    <n-spin :show="loading">
      <div v-if="!result && !errored" class="rag-debug__empty">
        <n-empty description="输入 query 后点击「检索」查看候选 L0/L1/BM25 / 证据 L2 / 引用 / token 预算" />
      </div>

      <div v-else-if="result" class="rag-debug__result">
        <n-alert
          :type="result.abstained ? 'warning' : 'success'"
          :title="alertTitle"
          style="margin-bottom: var(--spacing-3)"
        />
        <!-- C7 GLOBAL（WP4 Step4）：全局分支标识——map-reduce 文档级引用，无 chunk 候选/证据 -->
        <div v-if="result.globalMode" class="rag-debug__global" :class="{ 'rag-debug__global--degraded': result.globalDegraded }">
          🌍 GLOBAL 全局问答（map-reduce · 文档级引用 [n]《标题》）
          · 参与文档 <b>{{ result.globalDocCount ?? 0 }}</b>
          · map <b>{{ result.globalBatches ?? 0 }}</b> 批
          · L-KB 概览 <b>{{ result.globalOverviewReady ? '就绪' : '未生成' }}</b>
          <template v-if="result.globalDegraded">· ⚠ 已降级（仅库级概览，建议缩小问题范围）</template>
        </div>
        <div v-if="result.bm25Fallback" class="rag-debug__fallback-hint">
          ⚠ BM25 词法兜底触发：有候选无向量父锚，纯词法命中进入 pool（见下「候选 BM25」）
        </div>
        <div v-if="result.retrievalTimeline?.length" class="rag-debug__section">
          <h4 class="rag-debug__section-title">QueryPlan / RRF / Ranking 时间线</h4>
          <div class="rag-debug__budget">
            <span v-for="stage in result.retrievalTimeline" :key="stage.stage">
              {{ stage.stage }}：{{ stage.effectiveMode || stage.configuredMode || '-' }} · {{ stage.candidateCount }} 条 · {{ stage.latencyMs }}ms · {{ stage.status }}
            </span>
          </div>
        </div>

        <div v-if="lastGen" class="rag-debug__section">
          <h4 class="rag-debug__section-title">回答</h4>
          <pre class="rag-debug__answer">{{ result.answer }}</pre>
        </div>

        <div v-if="lastGen && result.citations.length" class="rag-debug__section">
          <h4 class="rag-debug__section-title">引用</h4>
          <n-data-table :columns="citationCols" :data="result.citations" :pagination="false" size="small" />
        </div>

        <div v-if="result.candidatesL0.length" class="rag-debug__section">
          <h4 class="rag-debug__section-title">候选 L0（章节摘要向量召回）</h4>
          <n-data-table :columns="l0Cols" :data="result.candidatesL0" :pagination="false" size="small" />
        </div>

        <div v-if="result.candidatesL1.length" class="rag-debug__section">
          <h4 class="rag-debug__section-title">候选 L1（文档元数据向量召回，点行展开看元数据）</h4>
          <n-data-table :columns="l1Cols" :data="result.candidatesL1" :pagination="false" size="small" />
        </div>

        <div v-if="result.candidatesBm25.length" class="rag-debug__section">
          <h4 class="rag-debug__section-title">候选 BM25（词法兜底命中 topK）</h4>
          <n-data-table :columns="bm25Cols" :data="result.candidatesBm25" :pagination="false" size="small" />
        </div>

        <div v-if="result.evidenceL2.length" class="rag-debug__section">
          <h4 class="rag-debug__section-title">证据 L2（注入 prompt）</h4>
          <n-data-table :columns="l2Cols" :data="result.evidenceL2" :pagination="false" size="small" />
        </div>

        <!-- C1：MAY_BE_CITED 反读的相关文档（仅推荐区，不进证据/引用） -->
        <div v-if="result.relatedDocs?.length" class="rag-debug__section">
          <h4 class="rag-debug__section-title">相关文档（关联推荐，未注入证据）</h4>
          <div class="rag-debug__budget">
            <span v-for="d in result.relatedDocs" :key="d.documentId">
              🔗 <b>{{ d.title }}</b>（{{ relationLabel(d.relationType) }}）
            </span>
          </div>
        </div>

        <div class="rag-debug__section">
          <h4 class="rag-debug__section-title">Token 预算</h4>
          <div class="rag-debug__budget">
            <span>prompt <b>{{ result.tokenBudget.promptTokens }}</b></span>
            <span>cap <b>{{ result.tokenBudget.effectiveContextCap }}</b></span>
            <span>maxCtx <b>{{ result.tokenBudget.maxContextTokens }}</b></span>
            <span>modelMax <b>{{ result.tokenBudget.modelMaxContext }}</b></span>
            <span>reserve <b>{{ result.tokenBudget.answerTokenReserve }}</b></span>
            <!-- C3 多轮检索：0=round0 即覆盖（基线行为）；>0=触发了补充轮（缺口锚点补召回） -->
            <span :class="{ 'rag-debug__rounds-active': (result.tokenBudget.rounds ?? 0) > 0 }">
              补检索轮 <b>{{ result.tokenBudget.rounds ?? 0 }}</b>
            </span>
          </div>
        </div>
      </div>
    </n-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NAlert, NButton, NCheckbox, NDataTable, NEmpty, NInput, NInputNumber, NSelect, NSpin, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { useKnowledgeStore } from '@/stores/knowledge'
import { useAuthStore } from '@/stores/auth'
import {
  knowledgeApi,
  type RagRetrieveVO,
  type RagCitation,
  type RagRecallHit,
  type RagL1RecallHit,
  type RagBm25Hit,
  type RagEvidence
} from '@/api/knowledge'
import { systemApi } from '@/api/system'

const store = useKnowledgeStore()
const authStore = useAuthStore()
const message = useMessage()

const form = ref({
  kbId: null as number | null,
  query: '',
  maxL0: null as number | null,
  generateAnswer: false
})
const docTypesText = ref('')
const result = ref<RagRetrieveVO | null>(null)
const loading = ref(false)
const errored = ref(false)
/** 当前结果是否含生成的答案（控制回答/引用栏显隐） */
const lastGen = ref(false)
/** 当前全局扩展态徽章（只读，证明调试用的就是真实配置；null=非管理员读不到则不显示） */
const expansionBadge = ref<string | null>(null)

onMounted(async () => {
  try {
    const res = await systemApi.getRagRecallSettings()
    const s = res.data.data
    expansionBadge.value = s.enabled ? `扩展: 开 · 阈值 ${s.threshold ?? 200}字` : '扩展: 关 · 单query'
  } catch {
    expansionBadge.value = null   // 非管理员(role:manage)读不到 → 不显示徽章
  }
})

/** 14x#3：保密库对非 owner/admin 成员禁选（检索调试整接口 403，灰显+原因提示；问答不受限） */
const kbOptions = computed(() =>
  store.bases.filter(b => b.canRead).map(b => {
    const restricted = !!b.confidential && b.createdBy !== authStore.userInfo?.id && !authStore.isAdmin
    return restricted
      ? { label: `${b.name}（保密库，仅问答）`, value: b.id, disabled: true }
      : { label: b.name, value: b.id }
  })
)
const canRun = computed(() => !!form.value.kbId && form.value.query.trim().length > 0)

/** C7 GLOBAL：告警标题区分常规命中与全局分支（降级时 PARTIAL） */
const alertTitle = computed(() => {
  if (result.value?.abstained) return `拒答（${result.value.abstainReason || ''}）`
  if (result.value?.globalMode) {
    return result.value.globalDegraded ? '命中（PARTIAL · GLOBAL 降级）' : '命中（SUPPORTED · GLOBAL）'
  }
  return '命中（SUPPORTED）'
})

const citationCols: DataTableColumns<RagCitation> = [
  { title: '[n]', key: 'index', width: 60 },
  { title: '文档ID', key: 'documentId', width: 80 },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  { title: '节点ID', key: 'nodeId', width: 90 },
  { title: '来源', key: 'fileRef', width: 110, render: r => renderAssetCell(r) }
]

/** IMAGE/FILE 原件回显 cell：IMAGE→缩略图，FILE→下载链，其余→'-'。URL 走 KB 读权限端点（跨用户）。 */
function renderAssetCell(row: { docType?: string | null; documentId: number; originalName?: string | null }) {
  const dt = row.docType
  if (dt !== 'IMAGE' && dt !== 'FILE') return '-'
  const url = `/api/knowledge/documents/${row.documentId}/asset`
  if (dt === 'IMAGE') {
    return h('img', { src: url, alt: 'image', style: 'max-width:90px;max-height:90px;border-radius:4px;object-fit:contain' })
  }
  const name = row.originalName || ''
  return h('a', { href: url, target: '_blank', download: name }, '下载')
}

const l0Cols: DataTableColumns<RagRecallHit> = [
  { title: '节点ID', key: 'nodeId', width: 90 },
  { title: '文档ID', key: 'documentId', width: 80 },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  { title: '摘要', key: 'content', ellipsis: { tooltip: true }, render: r => r.content ?? '-' },
  {
    title: 'cosSim', key: 'cosineSimilarity', width: 100,
    sorter: (a, b) => a.cosineSimilarity - b.cosineSimilarity,
    render: r => r.cosineSimilarity.toFixed(4)
  }
]

const l1Cols: DataTableColumns<RagL1RecallHit> = [
  {
    type: 'expand',
    renderExpand: (row: RagL1RecallHit) => h('div', { class: 'rag-debug__l1-meta' }, [
      h('p', [h('b', '摘要：'), row.summary ?? '—']),
      h('p', [h('b', '大纲：'), row.outline ?? '—']),
      h('p', [h('b', '要点：'), row.importantRules ?? '—'])
    ])
  },
  { title: '文档ID', key: 'documentId', width: 90 },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  {
    title: 'cosSim', key: 'cosineSimilarity', width: 100,
    sorter: (a, b) => a.cosineSimilarity - b.cosineSimilarity,
    render: r => r.cosineSimilarity.toFixed(4)
  }
]

const bm25Cols: DataTableColumns<RagBm25Hit> = [
  { title: '节点ID', key: 'nodeId', width: 90 },
  { title: '文档ID', key: 'documentId', width: 80 },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  {
    title: 'bm25Rank', key: 'bm25Rank', width: 110,
    render: r => (r.bm25Rank == null ? '-' : r.bm25Rank.toFixed(4))
  }
]

const l2Cols: DataTableColumns<RagEvidence> = [
  { title: '[n]', key: 'citationIndex', width: 60 },
  { title: '节点ID', key: 'nodeId', width: 90 },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  { title: '类型', key: 'docType', width: 80 },
  {
    // C1 step6.5：关联图带出标记（RELATION_MUST=必须引用带出 / RELATION_MAY=按需引用带出）
    // C2 Step6：📎=附件型证据（content 已含注入块——文本全文/图片识图/「原件内容暂缺」降级标注）
    title: '带出', key: 'injectedBy', width: 120,
    render: r => h('span', { style: 'display:inline-flex;align-items:center;gap:4px' }, [
      r.attachment
        ? h('span', { class: 'rag-debug__rel-badge', title: '附件型证据：内容含注入的原件内容/描述' }, '📎')
        : null,
      r.injectedBy === 'RELATION_MUST'
        ? h('span', { class: 'rag-debug__rel-badge rag-debug__rel-badge--must', title: '必须引用边带出（优先保序）' }, '🔗 必带')
        : r.injectedBy === 'RELATION_MAY'
          ? h('span', { class: 'rag-debug__rel-badge', title: '按需引用边带出（重打分过阈）' }, '🔗 关联带出')
          : null
    ])
  },
  { title: 'rerank', key: 'rerankScore', width: 90, render: r => r.rerankScore.toFixed(4) },
  { title: '内容', key: 'content', ellipsis: { tooltip: true }, render: r => r.content },
  { title: '来源', key: 'fileRef', width: 110, render: r => renderAssetCell(r) }
]

/** C1：相关文档区关系类型短标签 */
function relationLabel(t: string): string {
  if (t === 'MUST_BE_CITED') return '随场必现'
  if (t === 'MAY_BE_CITED') return '相关推荐'
  return t
}

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
      mode: 'BALANCED',
      generateAnswer: form.value.generateAnswer
    })
    lastGen.value = form.value.generateAnswer
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
.rag-debug__badge {
  padding: 2px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
  font-size: 12px;
}
.rag-debug__fallback-hint {
  margin-top: calc(-1 * var(--spacing-2));
  padding: var(--spacing-2) var(--spacing-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-card);
  color: var(--color-text-secondary);
  font-size: 12px;
}

/* C7 GLOBAL：全局分支标识条（降级=警示描边） */
.rag-debug__global {
  margin-top: calc(-1 * var(--spacing-2));
  padding: var(--spacing-2) var(--spacing-3);
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
  font-size: 12px;

  b { color: var(--color-primary); }
}
.rag-debug__global--degraded {
  border-color: var(--color-warning, #f0a020);
}
.rag-debug__l1-meta {
  padding: var(--spacing-2) var(--spacing-3);
  color: var(--color-text-secondary);
  font-size: 12px;
  p { margin: 0 0 var(--spacing-1); word-break: break-word; }
  b { color: var(--color-text-primary); }
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
/* C3 补检索轮 >0 高亮（触发补充轮=round0 有缺口被补上） */
.rag-debug__rounds-active {
  padding: 0 6px;
  border-radius: var(--radius-sm);
  background: var(--color-primary-bg, rgba(255, 255, 255, 0.08));
  b { color: var(--color-primary, #63e2b7); }
}
.rag-debug__rel-badge {
  padding: 1px 6px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: 11px;
  color: var(--color-text-secondary);
  white-space: nowrap;
}
.rag-debug__rel-badge--must {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

@media (max-width: 768px) {
  .rag-debug__row {
    flex-wrap: wrap;
  }
  // 覆盖内联 style="width:260px/160px" 的输入/选择器，移动端撑满
  .rag-debug__row :deep(.n-input),
  .rag-debug__row :deep(.n-base-selection) {
    width: 100% !important;
  }
}
</style>
