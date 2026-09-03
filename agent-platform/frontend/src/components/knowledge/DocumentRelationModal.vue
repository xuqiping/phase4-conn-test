<template>
  <n-modal
    :show="show"
    preset="card"
    :title="`文档关联：${doc?.title ?? ''}`"
    style="width: 720px"
    @update:show="v => emit('update:show', v)"
  >
    <n-spin :show="loading">
      <div class="rel-modal">
        <!-- 出入边列表（成员可读：理解「🔗 关联带出」证据来源） -->
        <n-data-table
          v-if="relations.length"
          :columns="columns"
          :data="relations"
          :pagination="false"
          size="small"
        />
        <p v-else class="rel-modal__empty">暂无关联边。命中本文档时可按边带出对端文档（见检索证据「🔗 关联带出」徽标）。</p>

        <!-- 添加边（仅 canManage；plan L1：成员可见不可改） -->
        <template v-if="canManage">
          <h4 class="rel-modal__form-title">添加关联</h4>
          <div class="rel-modal__form">
            <n-select
              v-model:value="addForm.relatedDocId"
              :options="docOptions"
              filterable
              placeholder="选择同库文档（对方）"
              style="width: 260px"
            />
            <n-select
              v-model:value="addForm.relationType"
              :options="typeOptions"
              placeholder="关系类型"
              style="width: 220px"
            />
            <n-input v-model:value="addForm.note" placeholder="备注（可选，≤200 字）" maxlength="200" style="width: 180px" />
            <n-button type="primary" :loading="saving" :disabled="!canAdd" @click="submit">建立</n-button>
          </div>
          <p class="rel-modal__hint">{{ typeHint }}</p>
        </template>
      </div>
    </n-spin>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import { NButton, NModal, NDataTable, NInput, NSelect, NSpin, NTag, useMessage } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { knowledgeApi } from '@/api/knowledge'
import type {
  KnowledgeDocument, KnowledgeRelation, RelationType
} from '@/api/knowledge'

const props = defineProps<{
  show: boolean
  kbId: number
  doc: KnowledgeDocument | null
  /** 仅 canManage 可建/删边；成员只读（对齐后端 knowledge:write 门） */
  canManage?: boolean
}>()

const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()

const message = useMessage()
const loading = ref(false)
const saving = ref(false)
const relations = ref<KnowledgeRelation[]>([])
const docs = ref<KnowledgeDocument[]>([])

/** C1 四类型（规格 §3.1）：语义 + 检索侧触发行为说明 */
const TYPE_META: Record<RelationType, { label: string; tag: 'success' | 'info' | 'warning' | 'default'; hint: string }> = {
  MUST_CITE: {
    label: '必须引用',
    tag: 'success',
    hint: '命中本档时，对方强制进证据（不被预算挤掉）'
  },
  MAY_CITE: {
    label: '按需引用',
    tag: 'info',
    hint: '命中本档时，对方经重打分过阈才进证据'
  },
  MUST_BE_CITED: {
    label: '随场必现',
    tag: 'warning',
    hint: '对方命中时，本档强制进证据（= 对方→本档的必须引用）'
  },
  MAY_BE_CITED: {
    label: '相关推荐',
    tag: 'default',
    hint: '对方命中时，本档仅出现在「相关文档」推荐区，不进证据'
  }
}

const typeOptions = (Object.keys(TYPE_META) as RelationType[]).map(t => ({
  label: TYPE_META[t].label,
  value: t
}))

const addForm = ref<{ relatedDocId: number | null; relationType: RelationType | null; note: string }>({
  relatedDocId: null,
  relationType: null,
  note: ''
})

const canAdd = computed(() => !!addForm.value.relatedDocId && !!addForm.value.relationType)
const typeHint = computed(() =>
  addForm.value.relationType ? TYPE_META[addForm.value.relationType].hint : '选择类型后展示该边的检索行为说明')

/** 同库文档选择器：排除自身、非 INDEXED（未索引文档无法命中也无从带出） */
const docOptions = computed(() =>
  docs.value
    .filter(d => d.id !== props.doc?.id && d.status === 'INDEXED')
    .map(d => ({ label: `${d.title}（#${d.id}）`, value: d.id }))
)

const columns = computed<DataTableColumns<KnowledgeRelation>>(() => [
  {
    title: '方向', key: 'direction', width: 70,
    render: r => h(NTag, { size: 'small', bordered: false }, () => (r.direction === 'OUT' ? '出边' : '入边'))
  },
  {
    title: '类型', key: 'relationType', width: 100,
    render: r => h(
      NTag,
      { size: 'small', round: true, type: TYPE_META[r.relationType]?.tag || 'default' },
      () => TYPE_META[r.relationType]?.label || r.relationType
    )
  },
  { title: '对方文档', key: 'otherDocTitle', ellipsis: { tooltip: true } },
  { title: '备注', key: 'note', ellipsis: { tooltip: true }, render: r => r.note || '-' },
  {
    title: '建立时间', key: 'createdAt', width: 160,
    render: r => new Date(r.createdAt).toLocaleString('zh-CN')
  },
  {
    title: '操作', key: 'actions', width: 80,
    render: r => !props.canManage
      ? '-'
      : h(NButton, { size: 'tiny', quaternary: true, type: 'error', onClick: () => removeEdge(r) }, () => '删除')
  }
])

async function load() {
  if (!props.doc) return
  loading.value = true
  try {
    const [relRes, docRes] = await Promise.all([
      knowledgeApi.listRelations(props.kbId, props.doc.id),
      knowledgeApi.listDocuments(props.kbId)
    ])
    relations.value = relRes.data.data || []
    docs.value = docRes.data.data || []
  } catch {
    message.error('加载关联失败')
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!props.doc || !canAdd.value) return
  saving.value = true
  try {
    await knowledgeApi.createRelation({
      kbId: props.kbId,
      docId: props.doc.id,
      relatedDocId: addForm.value.relatedDocId!,
      relationType: addForm.value.relationType!,
      note: addForm.value.note.trim() || undefined
    })
    message.success('关联已建立')
    addForm.value = { relatedDocId: null, relationType: null, note: '' }
    await load()
  } catch (e) {
    message.error(extractError(e, '建立关联失败'))
  } finally {
    saving.value = false
  }
}

async function removeEdge(edge: KnowledgeRelation) {
  try {
    await knowledgeApi.deleteRelation(edge.id)
    message.success('关联已删除')
    await load()
  } catch {
    message.error('删除失败')
  }
}

/** 后端 R<T>.msg 透传（如「等价关联已存在…」提示用户勿重复建边） */
function extractError(e: unknown, fallback: string): string {
  const msg = (e as { response?: { data?: { msg?: string } } })?.response?.data?.msg
  return msg || fallback
}

watch(() => props.show, v => {
  if (v) void load()
  else addForm.value = { relatedDocId: null, relationType: null, note: '' }
}, { immediate: true })
</script>

<style lang="scss" scoped>
.rel-modal {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}
.rel-modal__empty {
  margin: 0;
  padding: var(--spacing-3);
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}
.rel-modal__form-title {
  margin: var(--spacing-2) 0 0;
  font-size: 14px;
  color: var(--color-text-primary);
}
.rel-modal__form {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-2);
  align-items: center;
}
.rel-modal__hint {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

@media (max-width: 768px) {
  .rel-modal__form {
    :deep(.n-input),
    :deep(.n-base-selection) {
      width: 100% !important;
    }
  }
}
</style>
