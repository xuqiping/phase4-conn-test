<template>
  <n-modal v-model:show="visible" preset="card" :title="modalTitle" style="max-width:520px">
    <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
      <n-form-item label="名称" path="name">
        <n-input v-model:value="form.name" placeholder="请输入知识库名称" />
      </n-form-item>
      <n-form-item label="描述" path="description">
        <n-input v-model:value="form.description" type="textarea" placeholder="可选，知识库用途描述" :rows="2" />
      </n-form-item>
      <n-form-item label="可见性" path="visibility">
        <n-select v-model:value="form.visibility" :options="visibilityOptions" />
      </n-form-item>
      <n-form-item label="摘要策略" path="summaryStrategy">
        <n-select v-model:value="form.summaryStrategy" :options="strategyOptions" />
      </n-form-item>
      <n-form-item label="Embedding 模型" path="embeddingModel">
        <!-- 14x#1：文本框改下拉（启用 EMBEDDING 列表）；空=管理员默认；存量未启用值保留为灰选项防静默丢失 -->
        <n-select v-model:value="form.embeddingModel" :options="embeddingOptions" placeholder="留空使用管理员默认向量模型" />
      </n-form-item>
      <n-form-item label="问答模型" path="answerModel">
        <!-- 14x#1：per-KB RAG 问答模型（事实提炼+答案合成）；空=跟随全局默认 -->
        <n-select v-model:value="form.answerModel" :options="answerOptions" placeholder="跟随全局默认" />
      </n-form-item>
      <!-- 14x#1（L4）：换 embedding 且库内已有文档 → 重建索引强提示横幅，读后手动关闭 -->
      <n-alert v-if="rebuildWarning" type="warning" :show-icon="true" class="kb-form__rebuild-alert">
        {{ rebuildWarning }}
      </n-alert>
      <n-form-item label="重排方式">
        <n-select v-model:value="ranking.rankingMode" :options="rankingModeOptions" @update:value="loadRankingModels" />
      </n-form-item>
      <n-form-item v-if="ranking.rankingMode !== 'DISABLED'" label="重排模型">
        <n-select v-model:value="ranking.model" :options="rankingModelOptions" placeholder="请选择启用模型" />
      </n-form-item>
    </n-form>
    <template #action>
      <n-button @click="visible = false">取消</n-button>
      <n-button type="primary" :loading="saving" @click="handleSubmit">
        {{ isEdit ? '保存' : '创建' }}
      </n-button>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { NAlert, NModal, NForm, NFormItem, NInput, NSelect, NButton, useMessage } from 'naive-ui'
import type { FormInst, FormRules, SelectOption } from 'naive-ui'
import { knowledgeApi, type KnowledgeBase, type KnowledgeBaseRequest, type RankingConfigUpdate, type RankingMode } from '@/api/knowledge'
import { llmApi } from '@/api/llm'

const props = defineProps<{
  editData?: KnowledgeBase | null
}>()

const emit = defineEmits<{
  (e: 'saved'): void
}>()

const message = useMessage()
const visible = defineModel<boolean>('show', { default: false })
const formRef = ref<FormInst | null>(null)
const saving = ref(false)
const ranking = ref<RankingConfigUpdate>({ rankingMode: 'LLM', candidateLimit: 30, finalLimit: 10, batchSize: 10, timeoutMs: 4000, fallbackPolicy: 'FAIL_CLOSED', highAccuracyEnabled: false })
const rankingModelOptions = ref<{ label: string; value: string }[]>([])
// 14x#1：embedding/问答模型下拉选项（启用列表 + 空选项；存量未启用值补灰选项防静默丢失）
const embeddingOptions = ref<{ label: string; value: string }[]>([])
const answerOptions = ref<{ label: string; value: string }[]>([])
/** L4：换 embedding 且已有文档的服务端强提示，非空时横幅展示且弹窗不自动关闭 */
const rebuildWarning = ref('')
const rankingModeOptions = [
  { label: 'LLM 重排', value: 'LLM' }, { label: '专用 Rerank', value: 'RERANK' }, { label: '关闭重排', value: 'DISABLED' }
]

const isEdit = computed(() => !!props.editData)
const modalTitle = computed(() => (isEdit.value ? '编辑知识库' : '新建知识库'))

const visibilityOptions: SelectOption[] = [
  { label: '私有（PRIVATE）', value: 'PRIVATE' },
  { label: '团队（TEAM）', value: 'TEAM' },
  { label: '公开（PUBLIC）', value: 'PUBLIC' }
]
const strategyOptions: SelectOption[] = [
  { label: '逐节摘要（PER_SECTION）', value: 'PER_SECTION' },
  { label: '批量摘要（BATCH）', value: 'BATCH' },
  { label: '混合（HYBRID）', value: 'HYBRID' }
]

const form = ref<KnowledgeBaseRequest>({
  name: '',
  description: '',
  visibility: 'PRIVATE',
  summaryStrategy: 'PER_SECTION',
  embeddingModel: '',
  rerankModel: '',
  answerModel: ''
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }]
}

watch(visible, async (val) => {
  if (!val) return
  rebuildWarning.value = ''
  if (props.editData) {
    form.value = {
      name: props.editData.name,
      description: props.editData.description || '',
      visibility: props.editData.visibility || 'PRIVATE',
      summaryStrategy: props.editData.summaryStrategy || 'PER_SECTION',
      embeddingModel: props.editData.embeddingModel || '',
      rerankModel: props.editData.rerankModel || '',
      answerModel: props.editData.answerModel || ''
    }
    try {
      const cfg = (await knowledgeApi.getRankingConfig(props.editData.id)).data.data
      ranking.value = { rankingMode: cfg.mode, model: cfg.model, candidateLimit: cfg.candidateLimit, finalLimit: cfg.finalLimit, batchSize: cfg.batchSize, timeoutMs: cfg.timeoutMs, fallbackPolicy: cfg.fallbackPolicy, highAccuracyEnabled: cfg.highAccuracyEnabled }
    } catch { /* 无 KB 覆盖时使用管理员默认的表单值 */ }
  } else {
    form.value = {
      name: '',
      description: '',
      visibility: 'PRIVATE',
      summaryStrategy: 'PER_SECTION',
      embeddingModel: '',
      rerankModel: '',
      answerModel: ''
    }
  }
  await Promise.all([loadRankingModels(ranking.value.rankingMode), loadModelOptions()])
}, { immediate: true })

/** 14x#1：embedding（EMBEDDING 类）/问答（CHAT 类）启用模型下拉装配；存量未启用值补灰选项。 */
async function loadModelOptions() {
  const [embeddings, chats] = await Promise.all([
    llmApi.listActiveModels('EMBEDDING').then(r => r.data.data || []),
    llmApi.listActiveModels('CHAT').then(r => r.data.data || [])
  ])
  const withEmpty = (models: string[], emptyLabel: string) =>
    [{ label: emptyLabel, value: '' }, ...models.map(m => ({ label: m, value: m }))]
  embeddingOptions.value = withEmpty(embeddings, '留空使用管理员默认向量模型')
  answerOptions.value = withEmpty(chats, '跟随全局默认')
  // 存量值不在启用列表（模型已下线）：补灰选项，避免 select 显示裸 value 或被静默清空
  const stale = (models: string[], opts: { label: string; value: string }[], current?: string | null) => {
    if (current && current !== '' && !models.includes(current)) {
      opts.push({ label: `${current}（当前，未在启用列表）`, value: current })
    }
  }
  stale(embeddings, embeddingOptions.value, form.value.embeddingModel)
  stale(chats, answerOptions.value, form.value.answerModel)
}

async function loadRankingModels(mode: RankingMode) {
  ranking.value.rankingMode = mode
  if (mode === 'DISABLED') { ranking.value.model = null; rankingModelOptions.value = []; return }
  const models = (await llmApi.listActiveModels(mode === 'LLM' ? 'CHAT' : 'RERANK')).data.data
  rankingModelOptions.value = models.map(model => ({ label: model, value: model }))
  if (ranking.value.model && !models.includes(ranking.value.model)) ranking.value.model = null
}

async function handleSubmit() {
  try { await formRef.value?.validate() } catch { return }
  saving.value = true
  try {
    const payload: KnowledgeBaseRequest = {
      name: form.value.name,
      description: form.value.description || undefined,
      visibility: form.value.visibility,
      summaryStrategy: form.value.summaryStrategy,
      embeddingModel: form.value.embeddingModel || undefined,
      rerankModel: form.value.rerankModel || undefined,
      answerModel: form.value.answerModel || undefined
    }
    let kbId: number
    if (isEdit.value && props.editData) {
      const vo = (await knowledgeApi.updateBase(props.editData.id, payload)).data.data
      kbId = props.editData.id
      // L4：服务端判定需重建索引 → 横幅展示且不自动关弹窗，读后手动关闭
      if (vo?.warning) {
        rebuildWarning.value = vo.warning
        message.warning('向量模型已变更，请阅读提示并重建索引')
      } else {
        message.success('更新成功')
      }
    } else {
      const created = await knowledgeApi.createBase(payload)
      kbId = created.data.data.id
      message.success('创建成功')
    }
    await knowledgeApi.updateRankingConfig(kbId, ranking.value)
    emit('saved')
    if (!rebuildWarning.value) visible.value = false
  } catch {
    message.error(isEdit.value ? '更新失败' : '创建失败')
  } finally {
    saving.value = false
  }
}
</script>
