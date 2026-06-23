<template>
  <div class="doc-manager">
    <!-- 上传区 -->
    <n-upload
      v-if="canWrite"
      :custom-request="customUpload"
      :show-file-list="false"
      multiple
      accept=".md,.txt,.markdown,.pdf,.docx,.doc,.html"
    >
      <n-upload-dragger>
        <div class="doc-manager__dragger">
          <n-icon size="32" :component="CloudUploadOutline" color="var(--color-text-tertiary)" />
          <p class="doc-manager__hint">点击或拖拽文件到此处上传（md / txt / pdf / docx / html）</p>
          <p v-if="uploading" class="doc-manager__uploading">上传中…</p>
        </div>
      </n-upload-dragger>
    </n-upload>

    <!-- 文档表 -->
    <n-data-table
      :columns="columns"
      :data="docs"
      :loading="loading"
      :pagination="false"
      size="small"
    />
    <n-empty v-if="!loading && docs.length === 0" description="暂无文档，上传一个开始解析与索引" />
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  NButton, NDataTable, NEmpty, NIcon, NTag, NUpload, NUploadDragger, useMessage
} from 'naive-ui'
import type { DataTableColumns, UploadCustomRequestOptions } from 'naive-ui'
import { CloudUploadOutline } from '@vicons/ionicons5'
import { useKnowledgeStore } from '@/stores/knowledge'
import type { KnowledgeDocument } from '@/api/knowledge'

const props = defineProps<{
  kbId: number
  canWrite: boolean
}>()

const message = useMessage()
const store = useKnowledgeStore()

const docs = computed(() => store.documents)
const loading = computed(() => store.loadingDocs)
const uploading = ref(false)

const PROCESSING = new Set(['PENDING', 'PARSING', 'SUMMARIZING', 'EMBEDDING'])

const statusMap: Record<string, { type: 'success' | 'warning' | 'error' | 'default'; label: string }> = {
  INDEXED: { type: 'success', label: '已索引' },
  FAILED: { type: 'error', label: '失败' },
  PENDING: { type: 'warning', label: '排队中' },
  PARSING: { type: 'warning', label: '解析中' },
  SUMMARIZING: { type: 'warning', label: '摘要中' },
  EMBEDDING: { type: 'warning', label: '向量化中' }
}

const columns: DataTableColumns<KnowledgeDocument> = [
  { title: 'ID', key: 'id', width: 60 },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  { title: '类型', key: 'docType', width: 90, render: r => r.docType || '-' },
  {
    title: '状态', key: 'status', width: 100,
    render: r => h(NTag, { size: 'small', round: true, type: statusMap[r.status]?.type || 'default' },
      () => statusMap[r.status]?.label || r.status)
  },
  {
    title: '错误', key: 'parseError', ellipsis: { tooltip: true },
    render: r => r.parseError ? h('span', { style: 'color:var(--color-danger)' }, r.parseError) : '-'
  },
  {
    title: '创建', key: 'createdAt', width: 160,
    render: r => new Date(r.createdAt).toLocaleString('zh-CN')
  },
  {
    title: '操作', key: 'actions', width: 80, fixed: 'right',
    render: r => props.canWrite
      ? h(NButton, { size: 'small', quaternary: true, type: 'error', onClick: () => remove(r) }, () => '删除')
      : '-'
  }
]

let pollTimer: ReturnType<typeof setInterval> | null = null

function anyProcessing(list: KnowledgeDocument[]) {
  return list.some(d => PROCESSING.has(d.status))
}

function ensurePolling() {
  if (anyProcessing(docs.value) && !pollTimer && props.kbId) {
    pollTimer = setInterval(() => { void store.loadDocuments(props.kbId) }, 3000)
  } else if (!anyProcessing(docs.value) && pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(docs, ensurePolling, { deep: true })
watch(() => props.kbId, async (id) => {
  if (id) await store.loadDocuments(id)
})

async function customUpload({ file, onFinish, onError }: UploadCustomRequestOptions) {
  if (!file.file) return
  uploading.value = true
  try {
    await store.uploadDocument(props.kbId, file.file)
    message.success(`已上传：${file.name}`)
    onFinish()
  } catch {
    message.error(`上传失败：${file.name}`)
    onError()
  } finally {
    uploading.value = false
  }
}

async function remove(doc: KnowledgeDocument) {
  try {
    await store.deleteDocument(doc.id, props.kbId)
    message.success('已删除')
  } catch {
    message.error('删除失败')
  }
}

onMounted(() => {
  if (props.kbId) void store.loadDocuments(props.kbId)
})

onUnmounted(() => {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
})
</script>

<style lang="scss" scoped>
.doc-manager {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}
.doc-manager__dragger {
  padding: 20px;
  text-align: center;
}
.doc-manager__hint {
  margin-top: 8px;
  color: var(--color-text-secondary);
}
.doc-manager__uploading {
  margin-top: 4px;
  color: var(--color-primary);
}
</style>
