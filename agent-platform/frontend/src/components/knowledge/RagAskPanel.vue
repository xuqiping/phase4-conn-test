<!-- ============================================================
  RAG 流式问答面板 — /api/knowledge/ask SSE（knowledge:read）
  KB 多选 + query → CHUNK 流式答案 + CITATION 引用列表 + abstain 文案
  ============================================================ -->
<template>
  <div class="rag-ask">
    <!-- 输入区 -->
    <div class="rag-ask__input">
      <n-select
        v-model:value="kbIds"
        multiple
        size="small"
        placeholder="选择知识库（可多选，与权限求交）"
        :options="kbOptions"
        class="rag-ask__kb-select"
      />
      <n-input
        v-model:value="query"
        type="textarea"
        size="small"
        :autosize="{ minRows: 2, maxRows: 5 }"
        placeholder="输入问题，基于所选知识库作答并标注引用"
        class="rag-ask__query"
        @keydown.enter.exact.prevent="ask"
      />
      <div class="rag-ask__actions">
        <n-button size="small" type="primary" :loading="asking" :disabled="!canAsk" @click="ask">提问</n-button>
        <n-button v-if="asking" size="small" quaternary @click="stop">停止</n-button>
      </div>
    </div>

    <!-- 答案区 -->
    <div v-if="answer || asking || error" class="rag-ask__answer-wrap">
      <div v-if="asking && !answer" class="rag-ask__thinking">
        <n-spin size="small" /> 检索与生成中…
      </div>
      <div v-else class="rag-ask__answer">{{ answer }}</div>
      <div v-if="error" class="rag-ask__error">{{ error }}</div>

      <div v-if="answer && !asking" class="rag-ask__feedback">
        <n-select v-model:value="feedbackCategory" size="small" :options="feedbackOptions" placeholder="选择反馈类型" />
        <n-input v-model:value="feedbackComment" size="small" maxlength="1000" placeholder="补充说明（可选）" />
        <n-button size="small" :disabled="!feedbackCategory || kbIds.length !== 1" @click="submitFeedback">
          提交反馈
        </n-button>
      </div>

      <!-- 引用列表（CITATION）-->
      <div v-if="citations.length" class="rag-ask__citations">
        <div class="rag-ask__citations-title">引用（{{ citations.length }}）</div>
        <div class="rag-ask__citation" v-for="c in citations" :key="c.index">
          <n-tag size="tiny" round :bordered="false" type="info">[{{ c.index }}]</n-tag>
          <span class="rag-ask__citation-title">{{ c.title }}</span>
          <span class="rag-ask__citation-meta">doc#{{ c.documentId }} · node#{{ c.nodeId }}</span>
        </div>
      </div>
    </div>
    <n-empty v-else class="rag-ask__empty" description="选择知识库并提问，答案将流式返回并标注证据引用。" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, onUnmounted } from 'vue'
import { NButton, NEmpty, NInput, NSelect, NSpin, NTag, useMessage } from 'naive-ui'
import { askStream, knowledgeApi, type RagCitation } from '@/api/knowledge'

const message = useMessage()

const kbIds = ref<number[]>([])
const kbOptions = ref<{ label: string; value: number }[]>([])
const query = ref('')
const answer = ref('')
const citations = ref<RagCitation[]>([])
const confidenceState = ref('')
const asking = ref(false)
const error = ref('')
const feedbackCategory = ref<string | null>(null)
const feedbackComment = ref('')
const feedbackOptions = [
  { label: '结果不相关', value: 'NOT_RELEVANT' },
  { label: '内容已过期', value: 'OUTDATED' },
  { label: '引用错误', value: 'WRONG_CITATION' },
  { label: '答案不完整', value: 'INCOMPLETE' }
]
let abortController: AbortController | null = null

const canAsk = computed(() => query.value.trim().length > 0 && kbIds.value.length > 0)

async function loadBases() {
  try {
    const res = await knowledgeApi.listBases()
    kbOptions.value = res.data.data.map(kb => ({ label: kb.name, value: kb.id }))
  } catch {
    message.error('加载知识库列表失败')
  }
}

async function ask() {
  if (!canAsk.value || asking.value) return
  answer.value = ''
  citations.value = []
  error.value = ''
  asking.value = true
  abortController = new AbortController()
  try {
    for await (const evt of askStream(query.value.trim(), kbIds.value, abortController.signal)) {
      switch (evt.type) {
        case 'CHUNK':
          answer.value += evt.content || ''
          break
        case 'CITATION':
          citations.value = parseCitations(evt.content)
          break
        case 'RAG_STATE':
          confidenceState.value = evt.content || ''
          break
        case 'ERROR':
          error.value = evt.content || '生成失败'
          break
        case 'DONE':
          break
      }
    }
  } catch (e) {
    if ((e as Error).name !== 'AbortError') {
      error.value = (e as Error).message || '请求失败'
    }
  } finally {
    asking.value = false
    abortController = null
  }
}

function stop() {
  abortController?.abort()
  asking.value = false
}

function parseCitations(json: string | undefined): RagCitation[] {
  try {
    const arr = JSON.parse(json || '[]')
    return Array.isArray(arr) ? arr as RagCitation[] : []
  } catch { return [] }
}

async function submitFeedback() {
  if (!feedbackCategory.value || kbIds.value.length !== 1) return
  await knowledgeApi.submitRagFeedback({
    knowledgeBaseId: kbIds.value[0],
    category: feedbackCategory.value,
    comment: feedbackComment.value.trim() || undefined
  })
  feedbackCategory.value = null
  feedbackComment.value = ''
  message.success('反馈已进入待审核队列，不会直接改变线上排序')
}

onMounted(() => { void loadBases() })
onUnmounted(() => { abortController?.abort() })
</script>

<style lang="scss" scoped>
.rag-ask {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}
.rag-ask__input {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}
.rag-ask__kb-select { width: 100%; }
.rag-ask__query { width: 100%; }
.rag-ask__actions {
  display: flex; gap: var(--spacing-2);
}
.rag-ask__answer-wrap {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
  padding: var(--spacing-3);
  background: var(--color-bg-secondary, rgba(255, 255, 255, 0.04));
  border-radius: 8px;
}
.rag-ask__thinking {
  display: flex; align-items: center; gap: var(--spacing-2);
  color: var(--color-text-secondary, #aaa); font-size: 13px;
}
.rag-ask__answer {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
  color: var(--color-text-primary, #eee);
}
.rag-ask__error {
  color: var(--color-error, #e88080);
  font-size: 13px;
}
.rag-ask__feedback {
  display: grid;
  grid-template-columns: minmax(140px, 200px) 1fr auto;
  gap: var(--spacing-2);
  align-items: center;
}
@media (max-width: 768px) {
  .rag-ask__feedback { grid-template-columns: 1fr; }
}
.rag-ask__citations {
  margin-top: var(--spacing-2);
  padding-top: var(--spacing-2);
  border-top: 1px solid var(--color-border, rgba(255, 255, 255, 0.08));
}
.rag-ask__citations-title {
  font-size: 12px;
  color: var(--color-text-tertiary, #888);
  margin-bottom: var(--spacing-2);
}
.rag-ask__citation {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  padding: 2px 0;
}
.rag-ask__citation-title { color: var(--color-text-primary, #eee); }
.rag-ask__citation-meta { color: var(--color-text-tertiary, #777); font-size: 12px; }
.rag-ask__empty { padding: var(--spacing-5) 0; }
</style>
