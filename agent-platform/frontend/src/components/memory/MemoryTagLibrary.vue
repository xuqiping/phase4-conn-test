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
      <n-button size="small" type="primary" ghost @click="openCreate">新增标签</n-button>
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
    <n-modal v-model:show="editing" preset="card" title="编辑标签" :style="{ maxWidth: '480px', width: '90vw' }">
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
        <n-alert v-if="editTarget?.needsReview" type="warning" :bordered="false" size="small">
          该标签不在大类词表内（待裁决）：改名 / 补别名 / 保存即视为接受，并清「待裁决」标记。
        </n-alert>
      </n-space>
      <template #footer>
        <n-space justify="end">
          <n-button size="small" @click="editing = false">取消</n-button>
          <n-button
            v-if="editTarget?.needsReview"
            size="small"
            type="warning"
            :loading="saving"
            @click="acceptAsVocab"
          >接受为新大类</n-button>
          <n-button size="small" type="primary" :loading="saving" @click="save">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- P3a 新建 modal：选大类 topic + 自填 label + 可选别名 -->
    <n-modal v-model:show="creating" preset="card" title="新增标签" :style="{ maxWidth: '480px', width: '90vw' }">
      <n-space vertical :size="12">
        <div class="memory-tag-library__edit-row">
          <span class="memory-tag-library__edit-label">主体</span>
          <n-input v-model:value="createForm.subject" size="small" placeholder="留空默认「我」" style="max-width: 160px" />
        </div>
        <div class="memory-tag-library__edit-row">
          <span class="memory-tag-library__edit-label">大类主题</span>
          <n-select
            v-model:value="createForm.topic"
            size="small"
            filterable
            tag
            placeholder="选择或输入大类（如 旅行出行 / 财务理财）"
            :options="topicOptions"
          />
        </div>
        <div class="memory-tag-library__edit-row">
          <span class="memory-tag-library__edit-label">标签名</span>
          <n-input v-model:value="createForm.label" size="small" placeholder="对外展示名（后续符合的内容即落此标签）" />
        </div>
        <div class="memory-tag-library__edit-row">
          <span class="memory-tag-library__edit-label">别名</span>
          <n-dynamic-tags v-model:value="createForm.aliases" size="small" :max="20" />
        </div>
        <n-alert type="info" :bordered="false" size="small">
          主动建的标签直接生效（不再标「待裁决」）。若该主体+大类已有标签，新标签名会并入既有标签的别名。
        </n-alert>
      </n-space>
      <template #footer>
        <n-space justify="end">
          <n-button size="small" @click="creating = false">取消</n-button>
          <n-button size="small" type="primary" :loading="saving" @click="submitCreate">创建</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, ref, computed } from 'vue'
import {
  NAlert, NButton, NDataTable, NDynamicTags, NInput, NModal, NSelect, NSpace, NTag, useMessage
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
    // 用后端回传的 VO 刷新本行（usageCount/label/needsReview 可能变）
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

/** V77：接受为新大类（不改名，仅清 needs_review + 消解通知）。 */
async function acceptAsVocab() {
  if (!editTarget.value) return
  saving.value = true
  try {
    const res = await memoryApi.editTag(editTarget.value.id, { accept: true })
    message.success('已接受为新大类')
    const fresh = res.data?.data
    if (fresh) {
      const idx = tags.value.findIndex(x => x.id === fresh.id)
      if (idx >= 0) tags.value[idx] = fresh
    } else {
      await load()
    }
    editing.value = false
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    saving.value = false
  }
}

// ---- P3a 新建标签 ----
const creating = ref(false)
const createForm = ref<{ subject: string; topic: string | null; label: string; aliases: string[] }>({
  subject: '', topic: null, label: '', aliases: []
})

/** 大类候选 = 既有标签的 topic 去重（用户已批准的大类）；n-select tag 模式允许输入新大类。 */
const topicOptions = computed(() => {
  const set = new Set<string>()
  for (const t of tags.value) if (t.topic) set.add(t.topic)
  return [...set].map(v => ({ label: v, value: v }))
})

function openCreate() {
  createForm.value = { subject: '', topic: null, label: '', aliases: [] }
  creating.value = true
}

async function submitCreate() {
  const topic = (createForm.value.topic || '').trim()
  const label = createForm.value.label.trim()
  if (!topic) { message.warning('请选择或输入大类主题'); return }
  if (!label) { message.warning('请填写标签名'); return }
  saving.value = true
  try {
    const res = await memoryApi.createTag({
      subject: createForm.value.subject.trim() || undefined,
      topic,
      label,
      aliases: createForm.value.aliases.length ? createForm.value.aliases : undefined
    })
    message.success(res.data?.msg || '标签已创建')
    const fresh = res.data?.data
    if (fresh) {
      // 已存在则并入既有（id 已在列表）→ 刷新该行；新建则插入
      const idx = tags.value.findIndex(x => x.id === fresh.id)
      if (idx >= 0) tags.value[idx] = fresh
      else tags.value.unshift(fresh)
    } else {
      await load()
    }
    creating.value = false
  } catch (e: any) {
    message.error(e?.message || '创建失败')
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
    title: '状态',
    key: 'needsReview',
    width: 90,
    render: (t) => t.needsReview
      ? h(NTag, { size: 'small', type: 'warning', bordered: false }, { default: () => '待裁决' })
      : h('span', { style: 'opacity:0.4' }, '—')
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
