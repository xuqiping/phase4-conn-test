<!-- ============================================================
  标签库（计划12 F-1）— owner 自助：改 label / 补 aliases
  · 走新栈 memoryApi（/api/chat/memory/tags），与 legacy chatApi 物理隔离
  · 向量 4：只露 label/subject/topic/usageCount（aliases/anchor 不返，仅可追加别名）
  · 禁手动 merge/split/re-extract（误并不可逆，已生成 summary 的 tag_id 会漂移）
  ============================================================ -->
<template>
  <div class="memory-tag-library">
    <n-space :size="8" align="center" class="memory-tag-library__toolbar">
      <n-input
        v-model:value="keyword"
        placeholder="按主体 / 主题 / 标签名筛选"
        clearable
        size="small"
        style="max-width: 280px"
      />
      <n-button size="small" :loading="loading" @click="load">刷新</n-button>
      <span class="memory-tag-library__hint">共 {{ filtered.length }} / {{ tags.length }} 个标签</span>
    </n-space>

    <n-data-table
      :columns="columns"
      :data="filtered"
      :loading="loading"
      :bordered="false"
      size="small"
      :pagination="{ pageSize: 20 }"
      :row-key="(r: MemoryTagVO) => r.id"
    />

    <!-- 编辑 modal：改 label + 追加 aliases -->
    <n-modal v-model:show="editing" preset="card" title="编辑标签" style="max-width: 480px">
      <n-space vertical :size="12">
        <div class="memory-tag-library__edit-row">
          <span class="memory-tag-library__edit-label">主体 / 主题</span>
          <n-tag size="small" :bordered="false">{{ editTarget?.subject }} : {{ editTarget?.topic }}</n-tag>
        </div>
        <div class="memory-tag-library__edit-row">
          <span class="memory-tag-library__edit-label">标签名</span>
          <n-input v-model:value="form.label" size="small" placeholder="对外展示名（同义归一后规范名）" />
        </div>
        <div class="memory-tag-library__edit-row">
          <span class="memory-tag-library__edit-label">追加别名</span>
          <n-dynamic-tags v-model:value="form.addAliases" size="small" :max="20" />
        </div>
        <n-alert type="info" :bordered="false" size="small">
          改标签名会重生语义锚点（纠错预期）；别名仅追加不删除。无合并 / 拆分 / 重抽。
        </n-alert>
      </n-space>
      <template #footer>
        <n-space justify="end">
          <n-button size="small" @click="editing = false">取消</n-button>
          <n-button size="small" type="primary" :loading="saving" @click="save">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, ref, computed } from 'vue'
import {
  NAlert, NButton, NDataTable, NDynamicTags, NInput, NModal, NSpace, NTag, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { memoryApi, type MemoryTagVO } from '@/api/memory'

const message = useMessage()

const tags = ref<MemoryTagVO[]>([])
const loading = ref(false)
const keyword = ref('')

const filtered = computed<MemoryTagVO[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return tags.value
  return tags.value.filter(t =>
    `${t.subject} ${t.topic} ${t.label}`.toLowerCase().includes(kw)
  )
})

async function load() {
  loading.value = true
  try {
    const res = await memoryApi.listTags()
    tags.value = res.data?.data ?? []
  } catch (e: any) {
    message.error(e?.message || '加载标签失败')
  } finally {
    loading.value = false
  }
}

// ---- 编辑 ----
const editing = ref(false)
const saving = ref(false)
const editTarget = ref<MemoryTagVO | null>(null)
const form = ref<{ label: string; addAliases: string[] }>({ label: '', addAliases: [] })

function openEdit(t: MemoryTagVO) {
  editTarget.value = t
  form.value = { label: t.label, addAliases: [] }
  editing.value = true
}

async function save() {
  if (!editTarget.value) return
  const label = form.value.label.trim()
  const addAliases = form.value.addAliases
  if (!label && addAliases.length === 0) {
    message.warning('标签名和别名至少改一项')
    return
  }
  if (label === editTarget.value.label && addAliases.length === 0) {
    message.warning('无变更')
    return
  }
  saving.value = true
  try {
    const res = await memoryApi.editTag(editTarget.value.id, {
      label: label !== editTarget.value.label ? label : undefined,
      addAliases: addAliases.length ? addAliases : undefined
    })
    message.success('标签已更新')
    // 用后端回传的 VO 刷新本行（usageCount/label 可能变）
    const fresh = res.data?.data
    if (fresh) {
      const idx = tags.value.findIndex(x => x.id === fresh.id)
      if (idx >= 0) tags.value[idx] = fresh
    } else {
      await load()
    }
    editing.value = false
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const columns = computed<DataTableColumns<MemoryTagVO>>(() => [
  {
    title: '主体 : 主题',
    key: 'subjectTopic',
    render: (t) => h('span', { class: 'memory-tag-library__st' }, [
      h(NTag, { size: 'small', bordered: false }, { default: () => t.subject }),
      h('span', { class: 'memory-tag-library__colon' }, ':'),
      h('span', null, t.topic)
    ])
  },
  { title: '标签名', key: 'label', ellipsis: { tooltip: true } },
  {
    title: '使用次数',
    key: 'usageCount',
    width: 100,
    render: (t) => h('span', { class: 'memory-tag-library__count' }, String(t.usageCount))
  },
  {
    title: '操作',
    key: 'actions',
    width: 90,
    render: (t) => h(NButton, {
      size: 'small', quaternary: true, type: 'primary', onClick: () => openEdit(t)
    }, { default: () => '编辑' })
  }
])

onMounted(load)
defineExpose({ refresh: load })
</script>

<style lang="scss" scoped>
.memory-tag-library {
  &__toolbar {
    margin-bottom: 12px;
  }
  &__hint {
    font-size: 12px;
    opacity: 0.65;
  }
  &__st {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }
  &__colon {
    opacity: 0.5;
  }
  &__count {
    font-variant-numeric: tabular-nums;
    opacity: 0.85;
  }
  &__edit-row {
    display: flex;
    align-items: center;
    gap: 12px;
  }
  &__edit-label {
    width: 72px;
    flex-shrink: 0;
    font-size: 13px;
    opacity: 0.75;
  }
}
</style>
