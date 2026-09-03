<template>
  <n-modal
    :show="show"
    preset="card"
    title="关联建议（共召回统计）"
    style="width: 720px"
    @update:show="v => emit('update:show', v)"
  >
    <n-spin :show="loading">
      <div class="sug-modal">
        <p class="sug-modal__intro">
          系统每日扫描检索记录，找出「同一问题下总被一起召回」的文档对，建议建立关联。
          采纳前请人工确认两文档确有业务关联；忽略后不再重复提醒。
        </p>

        <n-empty
          v-if="!loading && suggestions.length === 0"
          description="暂无待处理建议（每日凌晨统计一次，或阈值内无新共现）"
          style="padding: 24px 0"
        />

        <div v-for="s in suggestions" :key="s.id" class="sug-modal__item">
          <div class="sug-modal__item-head">
            <span class="sug-modal__pair">
              <b>{{ s.docTitleA }}</b>
              <span class="sug-modal__vs">↔</span>
              <b>{{ s.docTitleB }}</b>
            </span>
            <n-tag size="small" type="info" :bordered="false" round>
              共召回 {{ s.coRecallCount }} 次
            </n-tag>
          </div>

          <!-- 采纳操作行：方向（谁命中带出谁）+ 类型四选一 -->
          <div class="sug-modal__actions">
            <n-select
              v-model:value="adoptForm[s.id].fromDocId"
              :options="directionOptions(s)"
              size="small"
              style="width: 220px"
            />
            <n-select
              v-model:value="adoptForm[s.id].relationType"
              :options="typeOptions"
              size="small"
              style="width: 160px"
            />
            <n-button size="small" type="primary" :loading="busyId === s.id" @click="adopt(s)">
              采纳并建边
            </n-button>
            <n-button size="small" quaternary :loading="busyId === s.id" @click="ignore(s)">
              忽略
            </n-button>
          </div>
          <p class="sug-modal__hint">
            {{ typeHint(s) }}（最近共现：{{ new Date(s.lastSeenAt).toLocaleDateString('zh-CN') }}）
          </p>
        </div>
      </div>
    </n-spin>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { NButton, NEmpty, NModal, NSelect, NSpin, NTag, useMessage } from 'naive-ui'
import { knowledgeApi } from '@/api/knowledge'
import type { KnowledgeRelationSuggestion, RelationType } from '@/api/knowledge'

const props = defineProps<{
  show: boolean
  kbId: number
}>()

const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()

const message = useMessage()
const loading = ref(false)
const busyId = ref<number | null>(null)
const suggestions = ref<KnowledgeRelationSuggestion[]>([])

/** C1 四类型快捷语义（与 DocumentRelationModal 口径一致；采纳默认按需引用=最保守） */
const TYPE_HINT: Record<RelationType, string> = {
  MUST_CITE: '命中前者时，后者强制进证据',
  MAY_CITE: '命中前者时，后者经重打分过阈才进证据',
  MUST_BE_CITED: '命中前者时，后者随场必现（= 前者→后者的必须引用）',
  MAY_BE_CITED: '命中前者时，后者仅进「相关文档」推荐区'
}

const typeOptions = (Object.keys(TYPE_HINT) as RelationType[]).map(t => ({
  label: t === 'MUST_CITE' ? '必须引用' : t === 'MAY_CITE' ? '按需引用' : t === 'MUST_BE_CITED' ? '随场必现' : '相关推荐',
  value: t
}))

/** 每条建议的采纳表单（方向=谁命中带出谁 → fromDocId） */
const adoptForm = ref<Record<number, { fromDocId: number | null; relationType: RelationType }>>({})

function directionOptions(s: KnowledgeRelationSuggestion) {
  return [
    { label: `命中「${s.docTitleA}」时带出「${s.docTitleB}」`, value: s.docIdA },
    { label: `命中「${s.docTitleB}」时带出「${s.docTitleA}」`, value: s.docIdB }
  ]
}

function typeHint(s: KnowledgeRelationSuggestion): string {
  const f = adoptForm.value[s.id]
  return f?.relationType ? TYPE_HINT[f.relationType] : ''
}

function initForm(list: KnowledgeRelationSuggestion[]) {
  const next: typeof adoptForm.value = {}
  for (const s of list) {
    next[s.id] = { fromDocId: s.docIdA, relationType: 'MAY_CITE' }
  }
  adoptForm.value = next
}

async function load() {
  loading.value = true
  try {
    const res = await knowledgeApi.listRelationSuggestions(props.kbId)
    suggestions.value = res.data.data || []
    initForm(suggestions.value)
  } catch {
    message.error('加载建议失败（需知识库管理权限）')
  } finally {
    loading.value = false
  }
}

async function adopt(s: KnowledgeRelationSuggestion) {
  const f = adoptForm.value[s.id]
  if (!f?.fromDocId || !f.relationType) return
  busyId.value = s.id
  try {
    await knowledgeApi.adoptRelationSuggestion(s.id, {
      fromDocId: f.fromDocId,
      relationType: f.relationType
    })
    message.success('已采纳并建立关联')
    await load()
  } catch (e) {
    message.error(extractError(e, '采纳失败'))
  } finally {
    busyId.value = null
  }
}

async function ignore(s: KnowledgeRelationSuggestion) {
  busyId.value = s.id
  try {
    await knowledgeApi.ignoreRelationSuggestion(s.id)
    message.success('已忽略')
    await load()
  } catch (e) {
    message.error(extractError(e, '忽略失败'))
  } finally {
    busyId.value = null
  }
}

function extractError(e: unknown, fallback: string): string {
  const msg = (e as { response?: { data?: { msg?: string } } })?.response?.data?.msg
  return msg || fallback
}

watch(() => props.show, v => {
  if (v) void load()
}, { immediate: true })
</script>

<style lang="scss" scoped>
.sug-modal {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}
.sug-modal__intro {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}
.sug-modal__item {
  padding: var(--spacing-2) var(--spacing-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-base);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}
.sug-modal__item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-2);
  flex-wrap: wrap;
}
.sug-modal__pair {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  color: var(--color-text-primary);
  word-break: break-word;
}
.sug-modal__vs {
  color: var(--color-text-tertiary);
}
.sug-modal__actions {
  display: flex;
  gap: var(--spacing-2);
  flex-wrap: wrap;
  align-items: center;
}
.sug-modal__hint {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}
</style>
