<template>
  <div class="doc-manager">
    <!-- 上传区 -->
    <n-upload
      v-if="canWrite"
      :custom-request="customUpload"
      :show-file-list="false"
      multiple
      accept=".md,.txt,.markdown,.pdf,.docx,.doc,.html,.xlsx,.xls"
    >
      <n-upload-dragger>
        <div class="doc-manager__dragger">
          <n-icon size="32" :component="CloudUploadOutline" color="var(--color-text-tertiary)" />
          <p class="doc-manager__hint">点击或拖拽文件到此处上传（md / txt / pdf / docx / html / xlsx）</p>
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
      :row-key="rowKey"
      :expanded-row-keys="expandedRowKeys"
      size="small"
      @update:expanded-row-keys="onExpandedChange"
    />
    <n-empty v-if="!loading && docs.length === 0" description="暂无文档，上传一个开始解析与索引" />

    <!-- Excel sheet 选择（picker）-->
    <SheetPickerModal
      v-model:show="sheetPickerShow"
      :file-name="sheetPickerFile"
      :sheet-names="sheetPickerNames"
      :loading="uploading"
      @confirm="onSheetConfirm"
      @cancel="onSheetCancel"
    />
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
import { knowledgeApi } from '@/api/knowledge'
import type { KnowledgeDocument, KnowledgeNode, SheetPreview } from '@/api/knowledge'
import SheetPickerModal from './SheetPickerModal.vue'

const props = defineProps<{
  kbId: number
  canWrite: boolean
}>()

const message = useMessage()
const store = useKnowledgeStore()

const docs = computed(() => store.documents)
const loading = computed(() => store.loadingDocs)
const uploading = ref(false)

// 展开行：查看文档拆分节点（L0 摘要 + L2 原文）。按 docId 缓存，懒加载。
const expandedRowKeys = ref<number[]>([])
const docNodes = ref<Record<number, KnowledgeNode[]>>({})
const loadingNodes = ref<Record<number, boolean>>({})

function rowKey(row: KnowledgeDocument) {
  return row.id
}

async function loadDocNodes(docId: number) {
  if (docNodes.value[docId] || loadingNodes.value[docId]) return
  loadingNodes.value = { ...loadingNodes.value, [docId]: true }
  try {
    const res = await knowledgeApi.listDocumentNodes(docId)
    docNodes.value = { ...docNodes.value, [docId]: res.data.data || [] }
  } catch {
    docNodes.value = { ...docNodes.value, [docId]: [] }
    message.error('加载文档节点失败')
  } finally {
    loadingNodes.value = { ...loadingNodes.value, [docId]: false }
  }
}

function onExpandedChange(keys: Array<string | number>) {
  const next = keys.map(k => Number(k))
  const added = next.find(id => !expandedRowKeys.value.includes(id))
  if (added !== undefined) void loadDocNodes(added)
  expandedRowKeys.value = next
}

function renderDocNodes(doc: KnowledgeDocument) {
  if (loadingNodes.value[doc.id]) {
    return h('p', { class: 'doc-manager__node-loading' }, '加载节点中…')
  }
  const nodes = docNodes.value[doc.id]
  if (!nodes || nodes.length === 0) {
    return h('p', { class: 'doc-manager__node-empty' }, '暂无节点（文档未索引或未解析出结构）')
  }
  // 按 parentId 建一层：根节点（parentId 为空或指向文档级 L0）直排，L2 缩进。
  return h(
    'div',
    { class: 'doc-manager__nodes' },
    nodes.map(node => {
      const isL0 = node.level === 'L0'
      return h('div', { class: ['doc-manager__node', isL0 ? '' : 'doc-manager__node--child'] }, [
        h('div', { class: 'doc-manager__node-head' }, [
          h(
            NTag,
            { size: 'small', type: isL0 ? 'info' : 'success', bordered: false, round: true },
            () => (isL0 ? '摘要 L0' : `原文 ${node.nodeType || 'L2'}`)
          ),
          h('span', { class: 'doc-manager__node-title' }, node.title || `节点 ${node.id}`),
          node.tokenCount ? h('span', { class: 'doc-manager__node-tokens' }, `${node.tokenCount} tok`) : null
        ]),
        node.content
          ? h('pre', { class: 'doc-manager__node-content' }, node.content)
          : h('span', { class: 'doc-manager__node-empty' }, '（无内容）')
      ])
    })
  )
}

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
  {
    type: 'expand',
    expandable: (row) => row.status === 'INDEXED',
    renderExpand: (row) => renderDocNodes(row)
  },
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

// Excel sheet picker 状态
const sheetPickerShow = ref(false)
const sheetPickerFile = ref('')
const sheetPickerNames = ref<string[]>([])
let pendingPreview: SheetPreview | null = null

function isExcel(name: string) {
  const n = name.toLowerCase()
  return n.endsWith('.xlsx') || n.endsWith('.xls')
}

async function customUpload({ file, onFinish, onError }: UploadCustomRequestOptions) {
  if (!file.file) return
  uploading.value = true
  try {
    if (isExcel(file.name)) {
      // Excel：阶段1 preview → 弹 picker → 阶段2 复用 tempFileRef 上传（零重传）
      const preview = await store.previewSheets(props.kbId, file.file)
      if (!preview || preview.sheetNames.length === 0) {
        message.warning('该 Excel 未读到任何 sheet（空文件或格式不支持）')
        onError()
        return
      }
      pendingPreview = preview
      sheetPickerFile.value = preview.fileName
      sheetPickerNames.value = preview.sheetNames
      sheetPickerShow.value = true
      onFinish()   // n-upload 请求结束（实际上传在 confirm）
    } else {
      await store.uploadDocument(props.kbId, file.file)
      message.success(`已上传：${file.name}`)
      onFinish()
    }
  } catch {
    message.error(`上传失败：${file.name}`)
    onError()
  } finally {
    uploading.value = false
  }
}

async function onSheetConfirm(sheets: string[]) {
  if (!pendingPreview) return
  uploading.value = true
  try {
    await store.uploadDocumentSheets(props.kbId, pendingPreview.tempFileRef, sheets)
    message.success(`已上传：${pendingPreview.fileName}`)
    sheetPickerShow.value = false
    pendingPreview = null
  } catch {
    message.error('Excel 上传失败')
  } finally {
    uploading.value = false
  }
}

function onSheetCancel() {
  sheetPickerShow.value = false
  pendingPreview = null
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

.doc-manager__nodes {
  padding: var(--spacing-2) var(--spacing-3);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}

.doc-manager__node {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-1);
}

.doc-manager__node--child {
  margin-left: var(--spacing-4);
  padding-left: var(--spacing-3);
  border-left: 2px solid var(--color-border);
}

.doc-manager__node-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  flex-wrap: wrap;
}

.doc-manager__node-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  word-break: break-word;
}

.doc-manager__node-tokens {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.doc-manager__node-content {
  margin: 0;
  padding: var(--spacing-2) var(--spacing-3);
  max-height: 280px;
  overflow-y: auto;
  font-family: var(--font-family-code);
  font-size: var(--font-size-xs);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-text-secondary);
  background: var(--color-elevated);
  border-radius: var(--radius-base);
}

.doc-manager__node-loading,
.doc-manager__node-empty {
  padding: var(--spacing-2) var(--spacing-3);
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}
</style>
