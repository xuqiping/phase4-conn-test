<template>
  <div class="rag-recall-settings">
    <n-form label-placement="left" label-width="160" class="rag-recall-settings__form">
      <n-form-item label="Query 扩展（全局）">
        <n-switch v-model:value="enabled" :loading="saving" @update:value="handleSave" />
        <span class="rag-recall-settings__hint">
          开启后检索前先改写/扩展 query 提升召回。4 条检索路径（检索调试 / RAG 问答 / 智能对话 / Agent·工作流）同读此开关，调试与真实一致
        </span>
      </n-form-item>
      <n-form-item label="切块触发阈值">
        <n-input-number
          v-model:value="threshold"
          :min="1"
          :max="5000"
          :loading="saving"
          :disabled="!enabled"
          style="max-width: 220px"
          @update:value="handleSave"
        />
        <span class="rag-recall-settings__hint">
          字数。输入 &gt; 此值 → 切块多路召回（多主题各有命中，不丢内容、不调改写 LLM）；≤ 此值 → 改写+HyDE。默认 200
        </span>
      </n-form-item>
      <n-divider>默认重排配置</n-divider>
      <n-form-item label="重排方式">
        <n-select v-model:value="ranking.rankingMode" :options="modeOptions" @update:value="loadRankingModels" />
      </n-form-item>
      <n-form-item v-if="ranking.rankingMode !== 'DISABLED'" label="重排模型">
        <n-select v-model:value="ranking.model" :options="rankingModelOptions" placeholder="请选择可用模型" />
      </n-form-item>
      <n-form-item label="候选数 / 最终证据数">
        <n-input-number v-model:value="ranking.candidateLimit" :min="1" :max="200" />
        <span class="rag-recall-settings__hint">候选</span>
        <n-input-number v-model:value="ranking.finalLimit" :min="1" :max="200" />
        <span class="rag-recall-settings__hint">最终证据</span>
      </n-form-item>
      <n-button type="primary" :loading="rankingSaving" @click="saveRanking">保存默认重排配置</n-button>
    </n-form>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NButton, NDivider, NForm, NFormItem, NInputNumber, NSelect, NSwitch, useMessage } from 'naive-ui'
import { systemApi } from '@/api/system'
import { knowledgeApi, type RankingConfigUpdate, type RankingMode } from '@/api/knowledge'
import { llmApi } from '@/api/llm'

const message = useMessage()
const saving = ref(false)
const enabled = ref(true)
const threshold = ref<number>(200)
const rankingSaving = ref(false)
const rankingModelOptions = ref<{ label: string; value: string }[]>([])
const ranking = ref<RankingConfigUpdate>({ rankingMode: 'LLM', candidateLimit: 30, finalLimit: 10, batchSize: 10, timeoutMs: 4000, fallbackPolicy: 'FAIL_CLOSED', highAccuracyEnabled: false })
const modeOptions = [
  { label: 'LLM 重排（当前可用）', value: 'LLM' },
  { label: '专用 Rerank（预留）', value: 'RERANK' },
  { label: '关闭重排', value: 'DISABLED' }
]

onMounted(load)

async function load() {
  const res = await systemApi.getRagRecallSettings()
  enabled.value = !!res.data.data.enabled
  threshold.value = typeof res.data.data.threshold === 'number' ? res.data.data.threshold : 200
  try {
    const cfg = (await knowledgeApi.getDefaultRankingConfig()).data.data
    ranking.value = { rankingMode: cfg.mode, model: cfg.model, candidateLimit: cfg.candidateLimit, finalLimit: cfg.finalLimit, batchSize: cfg.batchSize, timeoutMs: cfg.timeoutMs, fallbackPolicy: cfg.fallbackPolicy, highAccuracyEnabled: cfg.highAccuracyEnabled }
    await loadRankingModels(cfg.mode)
  } catch { /* 尚未配置时保留表单默认值 */ }
}

async function loadRankingModels(mode: RankingMode) {
  ranking.value.rankingMode = mode
  if (mode === 'DISABLED') { ranking.value.model = null; rankingModelOptions.value = []; return }
  const category = mode === 'LLM' ? 'CHAT' : 'RERANK'
  const models = (await llmApi.listActiveModels(category)).data.data
  rankingModelOptions.value = models.map(model => ({ label: model, value: model }))
  if (ranking.value.model && !models.includes(ranking.value.model)) ranking.value.model = null
}

async function saveRanking() {
  rankingSaving.value = true
  try {
    await knowledgeApi.updateDefaultRankingConfig(ranking.value)
    message.success('默认重排配置已更新')
  } catch { message.error('保存默认重排配置失败') }
  finally { rankingSaving.value = false }
}

async function handleSave() {
  // 不 guard enabled：关开关也要入库（否则刷新回弹）
  saving.value = true
  try {
    await systemApi.updateRagRecallSettings({
      enabled: enabled.value,
      threshold: threshold.value
    })
    message.success(`Query 扩展：${enabled.value ? '开（阈值 ' + threshold.value + ' 字）' : '关（单 query 直接检索）'}`)
  } catch {
    await load()
  } finally {
    saving.value = false
  }
}
</script>

<style lang="scss" scoped>
.rag-recall-settings__form {
  max-width: 640px;
}

.rag-recall-settings__hint {
  margin-left: 12px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

@media (max-width: 768px) {
  .rag-recall-settings__form {
    max-width: 100%;
  }
  .rag-recall-settings__hint {
    display: block;
    margin-left: 0;
    margin-top: 4px;
  }
}
</style>
