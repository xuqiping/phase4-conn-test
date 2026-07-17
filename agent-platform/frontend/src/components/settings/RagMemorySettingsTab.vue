<template>
  <div class="rag-memory-settings">
    <n-form label-placement="left" label-width="160" class="rag-memory-settings__form">
      <n-form-item label="记忆模式（全局）">
        <n-switch v-model:value="enabled" :loading="saving" @update:value="handleSave" />
        <span class="rag-memory-settings__hint">
          开启后对话/Agent/工作流启用 RAG 证据 + 用户长期记忆（会话/Agent/工作流级可覆盖，默认关）
        </span>
      </n-form-item>
      <n-form-item label="记忆处理模式">
        <n-select
          v-model:value="processMode"
          :options="processModeOptions"
          :loading="saving"
          :disabled="!enabled"
          style="max-width: 220px"
          @update:value="handleSave"
        />
        <span class="rag-memory-settings__hint">
          全异步 = 答完即结束不卡顿，冲突走记忆面板解决；同步 = 即时冲突追问（答完略卡几秒）
        </span>
      </n-form-item>
      <n-form-item label="记忆检索模式">
        <n-select
          v-model:value="retrievalMode"
          :options="retrievalModeOptions"
          :loading="saving"
          :disabled="!enabled"
          style="max-width: 220px"
          @update:value="handleSave"
        />
        <span class="rag-memory-settings__hint">
          全量 = LLM 读全部记忆（易联想泄漏）；向量 = 仅注入语义相关 top-K；混合 = 向量+关键词(实体)并集，0命中走LLM兜底（治向量漏"女儿3岁"类实体桥接）；LLM_KEY = 锚点语义两阶段（粗筛 top-N→LLM 双维度精排，百万 key 召回优先，推荐）
        </span>
      </n-form-item>
      <n-form-item label="记忆标签语言">
        <n-select
          v-model:value="keyLanguage"
          :options="keyLanguageOptions"
          :loading="saving"
          :disabled="!enabled"
          style="max-width: 220px"
          @update:value="handleSave"
        />
        <span class="rag-memory-settings__hint">
          注入上下文里记忆 key 的展示语言：英文 = memory_key（child_name）；中文 = memory_key_zh（女儿，空回退英文）
        </span>
      </n-form-item>
      <n-form-item label="全量记忆阈值">
        <n-input-number
          v-model:value="fullContextThreshold"
          :min="0"
          :max="1000"
          :loading="saving"
          :disabled="!enabled || retrievalMode !== 'LLM_FULL_CONTEXT'"
          style="width: 220px"
          @update:value="handleSave"
        />
        <span class="rag-memory-settings__hint">
          仅「全量」模式生效。记忆条数 &gt; 此值时改两阶段：先加载全部 key→LLM 选相关→只装相关 value（省 token + 治联想泄漏）；0 = 禁用始终全量。默认 20
        </span>
      </n-form-item>
      <n-form-item label="关键词召回块阈值">
        <n-input-number
          v-model:value="keywordPerBlockThreshold"
          :min="0"
          :max="200"
          :loading="saving"
          :disabled="!enabled || retrievalMode !== 'VECTOR_KEYWORD'"
          style="width: 220px"
          @update:value="handleSave"
        />
        <span class="rag-memory-settings__hint">
          仅「混合」模式生效。同一信息块（如偏好/职业）关键词命中 &gt; 此值时，优先留命中 entities/key/key_zh（高优）的记忆（不卡阈值），低优补到此值；块内 ≤ 此值全留。0 = 禁用（不分组筛）。默认 10
        </span>
      </n-form-item>
      <n-form-item label="LLM_KEY 粗筛候选数">
        <n-input-number
          v-model:value="llmKeyCoarseTopN"
          :min="5"
          :max="200"
          :loading="saving"
          :disabled="!enabled || retrievalMode !== 'LLM_KEY'"
          style="width: 220px"
          @update:value="handleSave"
        />
        <span class="rag-memory-settings__hint">
          仅「LLM_KEY」生效。向量 + BM25 RRF 融合后保留的粗筛候选数（下一步 LLM 精排的输入）。默认 40
        </span>
      </n-form-item>
      <n-form-item label="LLM_KEY 精排开关">
        <n-switch
          v-model:value="llmKeyRerank"
          :loading="saving"
          :disabled="!enabled || retrievalMode !== 'LLM_KEY'"
          @update:value="handleSave"
        />
        <span class="rag-memory-settings__hint">
          仅「LLM_KEY」生效。开 = 粗筛 top-N → LLM 双维度（key × block）精排筛相关；关 = 跳精排直接注 top-N。默认开
        </span>
      </n-form-item>
      <n-form-item label="关键词通道上限">
        <n-input-number
          v-model:value="keywordMax"
          :min="0"
          :max="50"
          :loading="saving"
          :disabled="!enabled"
          style="width: 220px"
          @update:value="handleSave"
        />
        <span class="rag-memory-settings__hint">
          关键词（实体）通道单次最大召回块数。0 = 不限。默认 8（替后端 KEYWORD_MAX 硬编码）
        </span>
      </n-form-item>
      <n-form-item label="老记忆实体回填">
        <n-button :loading="backfilling" :disabled="!enabled" size="small" @click="handleBackfill">
          回填实体标签
        </n-button>
        <span class="rag-memory-settings__hint">
          为启用混合/向量模式前写入的老记忆补抽实体（entities 为空者）。异步执行，幂等可重跑，进度见后端日志 memoryBackfill
        </span>
      </n-form-item>
      <n-form-item label="老记忆关键词重抽">
        <n-button :loading="reextracting" size="small" @click="handleReextract">
          重抽关键词
        </n-button>
        <span class="rag-memory-settings__hint">
          无视空值过滤，按当前抽取 prompt 为全部老记忆重抽 entities 词袋（含上位词），保留中文标签、重算 anchor。改了词袋/上位词 prompt 后用此让老数据吃新规则（回填只补空值、已填行跳过）。会覆盖旧 entities（抽空的行保留旧值防回归），异步、admin，进度见后端日志 memoryReextract
        </span>
      </n-form-item>
      <n-form-item label="记忆冲突残留清理">
        <n-button :loading="cleaning" size="small" @click="handleCleanup">
          清理脏数据
        </n-button>
        <span class="rag-memory-settings__hint">
          旧「都保留」会留两条带冲突标的记忆（同 key 重复 + 抽取时隐身重复入库）。本按钮把已解决的冲突残留按 key 合并成一条干净记忆。异步、幂等可重跑，进度见后端日志 memoryCleanup
        </span>
      </n-form-item>
    </n-form>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NButton, NForm, NFormItem, NInputNumber, NSelect, NSwitch, useMessage } from 'naive-ui'
import { systemApi, type RagMemorySettings } from '@/api/system'

const message = useMessage()
const saving = ref(false)
const backfilling = ref(false)
const cleaning = ref(false)
const reextracting = ref(false)
const enabled = ref(false)
const processMode = ref<RagMemorySettings['processMode']>('ASYNC')
const retrievalMode = ref<NonNullable<RagMemorySettings['retrievalMode']>>('LLM_FULL_CONTEXT')
const keyLanguage = ref<NonNullable<RagMemorySettings['keyLanguage']>>('EN')
const fullContextThreshold = ref<number>(20)
const keywordPerBlockThreshold = ref<number>(10)
const llmKeyCoarseTopN = ref<number>(40)
const llmKeyRerank = ref<boolean>(true)
const keywordMax = ref<number>(8)

const processModeOptions = [
  { label: '全异步（不卡顿）', value: 'ASYNC' },
  { label: '同步（即时冲突追问）', value: 'HYBRID' }
]

const retrievalModeOptions = [
  { label: '全量（LLM 读全部记忆）', value: 'LLM_FULL_CONTEXT' },
  { label: '向量检索（仅注入相关记忆）', value: 'EMBEDDING_VECTOR' },
  { label: '混合检索（向量+关键词+LLM兜底）', value: 'VECTOR_KEYWORD' },
  { label: 'LLM_KEY（锚点语义两阶段，百万 key 推荐）', value: 'LLM_KEY' }
]

const keyLanguageOptions = [
  { label: '英文 key（默认）', value: 'EN' },
  { label: '中文标签 key_zh', value: 'ZH' },
  { label: '中英双选 key_zh(key)', value: 'BOTH' }
]

onMounted(load)

async function load() {
  const res = await systemApi.getRagMemorySettings()
  enabled.value = !!res.data.data.enabled
  processMode.value = res.data.data.processMode === 'HYBRID' ? 'HYBRID' : 'ASYNC'
  const rm = res.data.data.retrievalMode
  retrievalMode.value =
    rm === 'EMBEDDING_VECTOR' || rm === 'VECTOR_KEYWORD' || rm === 'LLM_KEY' ? rm : 'LLM_FULL_CONTEXT'
  keyLanguage.value = res.data.data.keyLanguage === 'ZH' || res.data.data.keyLanguage === 'BOTH'
    ? res.data.data.keyLanguage
    : 'EN'
  fullContextThreshold.value =
    typeof res.data.data.fullContextThreshold === 'number' ? res.data.data.fullContextThreshold : 20
  keywordPerBlockThreshold.value =
    typeof res.data.data.keywordPerBlockThreshold === 'number' ? res.data.data.keywordPerBlockThreshold : 10
  llmKeyCoarseTopN.value =
    typeof res.data.data.llmKeyCoarseTopN === 'number' ? res.data.data.llmKeyCoarseTopN : 40
  llmKeyRerank.value = res.data.data.llmKeyRerank !== false
  keywordMax.value =
    typeof res.data.data.keywordMax === 'number' ? res.data.data.keywordMax : 8
}

async function handleSave() {
  // 不 guard enabled：关开关也要入库（否则 OFF 永不持久化，刷新回弹 ON）。
  // processMode/retrievalMode/keyLanguage/fullContextThreshold 在 !enabled 时 :disabled 无法改，发的是 load() 读回的已存值，不会冲刷。
  saving.value = true
  try {
    await systemApi.updateRagMemorySettings({
      enabled: enabled.value,
      processMode: processMode.value,
      retrievalMode: retrievalMode.value,
      keyLanguage: keyLanguage.value,
      fullContextThreshold: fullContextThreshold.value,
      keywordPerBlockThreshold: keywordPerBlockThreshold.value,
      llmKeyCoarseTopN: llmKeyCoarseTopN.value,
      llmKeyRerank: llmKeyRerank.value,
      keywordMax: keywordMax.value
    })
    message.success(
      `记忆处理模式：${processMode.value === 'HYBRID' ? '同步（即时追问）' : '全异步（不卡顿）'}｜检索模式：${
        retrievalMode.value === 'EMBEDDING_VECTOR'
          ? '向量检索'
          : retrievalMode.value === 'VECTOR_KEYWORD'
            ? '混合检索（向量+关键词+LLM兜底）'
            : retrievalMode.value === 'LLM_KEY'
              ? 'LLM_KEY（锚点语义两阶段）'
              : '全量'
      }`
    )
  } catch {
    await load()
  } finally {
    saving.value = false
  }
}

async function handleBackfill() {
  backfilling.value = true
  try {
    await systemApi.backfillMemoryEntities()
    message.success('已启动老记忆实体回填（异步），进度见后端日志 memoryBackfill')
  } catch {
    // request 拦截器已弹错
  } finally {
    backfilling.value = false
  }
}

async function handleReextract() {
  reextracting.value = true
  try {
    await systemApi.reextractMemoryEntities()
    message.success('已启动老记忆关键词重抽（异步），进度见后端日志 memoryReextract')
  } catch {
    // request 拦截器已弹错
  } finally {
    reextracting.value = false
  }
}

async function handleCleanup() {
  cleaning.value = true
  try {
    await systemApi.cleanupMemoryResidue()
    message.success('已启动记忆冲突残留清理（异步），进度见后端日志 memoryCleanup')
  } catch {
    // request 拦截器已弹错
  } finally {
    cleaning.value = false
  }
}
</script>

<style lang="scss" scoped>
.rag-memory-settings__form {
  max-width: 640px;
}

.rag-memory-settings__hint {
  margin-left: 12px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

@media (max-width: 768px) {
  .rag-memory-settings__form {
    max-width: 100%;
  }
  .rag-memory-settings__hint {
    display: block;
    margin-left: 0;
    margin-top: 4px;
  }
}
</style>
