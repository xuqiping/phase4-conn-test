<template>
  <div class="doc-manager">
    <!-- 上传区 -->
    <n-upload
      v-if="canWrite"
      :custom-request="customUpload"
      :show-file-list="false"
      multiple
      accept=".md,.txt,.markdown,.pdf,.docx,.doc,.html,.xlsx,.xls,.png,.jpg,.jpeg,.gif,.webp,.bmp"
    >
      <n-upload-dragger>
        <div class="doc-manager__dragger">
          <n-icon size="32" :component="CloudUploadOutline" color="var(--color-text-tertiary)" />
          <p class="doc-manager__hint">点击或拖拽文件到此处上传（md / txt / pdf / docx / html / xlsx / 图片）</p>
          <p class="doc-manager__hint doc-manager__hint--sub">上传时可选「图片/文件知识库」+ 索引方式（手动给文本 / 自动抽取）</p>
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

    <!-- 上传选项（docType / 索引方式 / 手动文本 / Excel sheet） -->
    <DocumentOptionsModal
      v-model:show="optionsModalShow"
      :file-name="optionsModalFile"
      :sheet-names="optionsModalNames"
      :loading="uploading"
      @confirm="onOptionsConfirm"
      @cancel="onOptionsCancel"
    />

    <DocumentMetadataModal
      :show="metadataModalShow"
      :document="activeMetadataDoc"
      :loading="metadataSaving"
      :is-admin="authStore.isAdmin"
      @confirm="saveMetadata"
      @cancel="metadataModalShow = false"
    />

    <n-modal v-model:show="versionModalShow" preset="card" title="文档版本" style="width: 760px">
      <div class="doc-manager__version-upload" v-if="canWrite && activeVersionDoc">
        <n-input v-model:value="versionChangeNote" placeholder="版本说明（可选）" />
        <n-upload :show-file-list="false" :custom-request="uploadNewVersion">
          <n-button type="primary">上传新版本</n-button>
        </n-upload>
      </div>
      <n-data-table :columns="versionColumns" :data="versions" :loading="versionLoading" :pagination="false" />
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  NButton, NDataTable, NEmpty, NIcon, NInput, NModal, NTag, NUpload, NUploadDragger, useMessage
} from 'naive-ui'
import type { DataTableColumns, UploadCustomRequestOptions } from 'naive-ui'
import { CloudUploadOutline } from '@vicons/ionicons5'
import { useKnowledgeStore } from '@/stores/knowledge'
import { useAuthStore } from '@/stores/auth'
import { knowledgeApi } from '@/api/knowledge'
import type { KnowledgeDocument, KnowledgeDocumentMetadataUpdate, KnowledgeDocumentVersion, KnowledgeNode, SheetPreview, UploadOptions } from '@/api/knowledge'
import DocumentOptionsModal from './DocumentOptionsModal.vue'
import DocumentMetadataModal from './DocumentMetadataModal.vue'

const props = defineProps<{
  kbId: number
  canWrite: boolean
}>()

const message = useMessage()
const store = useKnowledgeStore()
const authStore = useAuthStore()

const docs = computed(() => store.documents)
const loading = computed(() => store.loadingDocs)
const uploading = ref(false)
const versionModalShow = ref(false)
const versionLoading = ref(false)
const versions = ref<KnowledgeDocumentVersion[]>([])
const activeVersionDoc = ref<KnowledgeDocument | null>(null)
const versionChangeNote = ref('')
const metadataModalShow = ref(false)
const metadataSaving = ref(false)
const activeMetadataDoc = ref<KnowledgeDocument | null>(null)

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
  const children: ReturnType<typeof h>[] = []
  // IMAGE/FILE 文档：节点列表顶部渲染原件（缩略图 / 下载）
  if (doc.docType === 'IMAGE' || doc.docType === 'FILE') {
    children.push(renderAsset(doc))
  }
  if (loadingNodes.value[doc.id]) {
    children.push(h('p', { class: 'doc-manager__node-loading' }, '加载节点中…'))
    return h('div', { class: 'doc-manager__nodes' }, children)
  }
  const nodes = docNodes.value[doc.id]
  if (!nodes || nodes.length === 0) {
    children.push(h('p', { class: 'doc-manager__node-empty' }, '暂无节点（文档未索引或未解析出结构）'))
    return h('div', { class: 'doc-manager__nodes' }, children)
  }
  // 按 parentId 建一层：根节点（parentId 为空或指向文档级 L0）直排，L2 缩进。
  return h(
    'div',
    { class: 'doc-manager__nodes' },
    children.concat(nodes.map(node => {
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
    }))
  )
}

/** IMAGE/FILE 原件回显：图片显缩略图，文件显下载按钮（asset URL 经 KB 读权限，跨用户可取）。 */
function renderAsset(doc: KnowledgeDocument) {
  const url = knowledgeApi.documentAssetUrl(doc.id)
  const name = doc.originalName || doc.title || String(doc.id)
  if (doc.docType === 'IMAGE') {
    return h('div', { class: 'doc-manager__asset' }, [
      h('img', {
        class: 'doc-manager__asset-img',
        src: url,
        alt: name,
        loading: 'lazy'
      })
    ])
  }
  return h('div', { class: 'doc-manager__asset' }, [
    h(NButton, { size: 'small', tag: 'a', href: url, target: '_blank', type: 'primary' },
      () => `下载原件：${name}`)
  ])
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
    title: '操作', key: 'actions', width: 210, fixed: 'right',
    render: r => h('div', { style: 'display:flex;gap:6px' }, [
      h(NButton, { size: 'small', quaternary: true, onClick: () => openVersions(r) }, () => '版本'),
      props.canWrite
        ? h(NButton, { size: 'small', quaternary: true, onClick: () => openMetadata(r) }, () => '治理')
        : null,
      props.canWrite
        ? h(NButton, { size: 'small', quaternary: true, type: 'error', onClick: () => remove(r) }, () => '删除')
        : null
    ])
  }
]

function openMetadata(doc: KnowledgeDocument) {
  activeMetadataDoc.value = doc
  metadataModalShow.value = true
}

async function saveMetadata(payload: KnowledgeDocumentMetadataUpdate) {
  const doc = activeMetadataDoc.value
  if (!doc) return
  metadataSaving.value = true
  try {
    const res = await knowledgeApi.updateDocumentMetadata(doc.id, payload)
    Object.assign(doc, res.data.data)
    metadataModalShow.value = false
    message.success('治理信息已保存')
  } catch {
    message.error('治理信息保存失败')
  } finally {
    metadataSaving.value = false
  }
}

const versionColumns: DataTableColumns<KnowledgeDocumentVersion> = [
  { title: '版本', key: 'versionNo', width: 70, render: r => `v${r.versionNo}` },
  { title: '状态', key: 'status', width: 110, render: r => h(NTag, { size: 'small' }, () => r.status) },
  { title: '说明', key: 'changeNote', ellipsis: { tooltip: true }, render: r => r.changeNote || '-' },
  { title: '创建时间', key: 'createdAt', width: 170, render: r => new Date(r.createdAt).toLocaleString('zh-CN') },
  {
    title: '操作', key: 'actions', width: 150,
    render: r => !props.canWrite || !activeVersionDoc.value
      ? '-'
      : h('div', { style: 'display:flex;gap:6px' }, [
        r.status === 'DRAFT' || r.status === 'ARCHIVED'
          ? h(NButton, { size: 'tiny', type: 'primary', onClick: () => activateVersion(r) }, () => '生效')
          : null,
        r.status !== 'REVOKED'
          ? h(NButton, { size: 'tiny', type: 'error', quaternary: true, onClick: () => revokeVersion(r) }, () => '撤销')
          : null
      ])
  }
]

async function openVersions(doc: KnowledgeDocument) {
  activeVersionDoc.value = doc
  versionModalShow.value = true
  await loadVersions()
}

async function loadVersions() {
  if (!activeVersionDoc.value) return
  versionLoading.value = true
  try {
    const res = await knowledgeApi.listDocumentVersions(activeVersionDoc.value.id)
    versions.value = res.data.data || []
  } catch {
    message.error('加载版本历史失败')
  } finally {
    versionLoading.value = false
  }
}

async function uploadNewVersion({ file, onFinish, onError }: UploadCustomRequestOptions) {
  const doc = activeVersionDoc.value
  if (!doc || !file.file || doc.currentVersionId == null) { onError(); return }
  try {
    await knowledgeApi.createDocumentVersion(doc.id, file.file, doc.currentVersionId, versionChangeNote.value)
    versionChangeNote.value = ''
    await loadVersions()
    message.success('新版本已保存为草稿')
    onFinish()
  } catch {
    message.error('新版本上传失败，请刷新后重试')
    onError()
  }
}

async function activateVersion(version: KnowledgeDocumentVersion) {
  const doc = activeVersionDoc.value
  if (!doc) return
  try {
    await knowledgeApi.activateDocumentVersion(doc.id, version.id, doc.currentVersionId)
    doc.currentVersionId = version.id
    await loadVersions()
    message.success(`v${version.versionNo} 已生效`)
  } catch {
    message.error('版本生效失败，请刷新后重试')
  }
}

async function revokeVersion(version: KnowledgeDocumentVersion) {
  const doc = activeVersionDoc.value
  if (!doc) return
  try {
    await knowledgeApi.revokeDocumentVersion(doc.id, version.id)
    if (doc.currentVersionId === version.id) doc.currentVersionId = null
    await loadVersions()
    message.success(`v${version.versionNo} 已撤销`)
  } catch {
    message.error('版本撤销失败')
  }
}

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

// 上传选项 modal 状态（统一入口：所有文件先开 modal 选 docType/索引方式；Excel 额外预读 sheet）
const optionsModalShow = ref(false)
const optionsModalFile = ref('')
const optionsModalNames = ref<string[]>([])
let pendingPreview: SheetPreview | null = null
let pendingFile: File | null = null

function isExcel(name: string) {
  const n = name.toLowerCase()
  return n.endsWith('.xlsx') || n.endsWith('.xls')
}

async function customUpload({ file, onFinish, onError }: UploadCustomRequestOptions) {
  if (!file.file) return
  try {
    pendingFile = file.file
    if (isExcel(file.name)) {
      // Excel：阶段1 预读 sheet 名（供 modal 勾选）
      const preview = await store.previewSheets(props.kbId, file.file)
      pendingPreview = preview
      optionsModalFile.value = (preview && preview.fileName) || file.name
      optionsModalNames.value = (preview && preview.sheetNames) || []
    } else {
      pendingPreview = null
      optionsModalFile.value = file.name
      optionsModalNames.value = []
    }
    optionsModalShow.value = true
    onFinish()   // n-upload 请求结束（实际上传在 confirm）
  } catch {
    message.error(`上传失败：${file.name}`)
    onError()
  }
}

async function onOptionsConfirm(payload: UploadOptions & { selectedSheets: string[] }) {
  uploading.value = true
  try {
    if (pendingPreview) {
      // Excel：复用 tempFileRef + 选定 sheet（空=导全部）+ 选项
      await store.uploadDocumentSheets(props.kbId, pendingPreview.tempFileRef,
        payload.selectedSheets, payload)
    } else if (pendingFile) {
      await store.uploadDocument(props.kbId, pendingFile, payload)
    } else {
      return
    }
    message.success(`已上传：${optionsModalFile.value}`)
    optionsModalShow.value = false
    pendingPreview = null
    pendingFile = null
  } catch {
    message.error('上传失败')
  } finally {
    uploading.value = false
  }
}

function onOptionsCancel() {
  optionsModalShow.value = false
  pendingPreview = null
  pendingFile = null
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
.doc-manager__version-upload {
  display: flex;
  gap: var(--spacing-2);
  margin-bottom: var(--spacing-3);
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

.doc-manager__hint--sub {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.doc-manager__asset {
  padding: var(--spacing-2) var(--spacing-3);
  margin-bottom: var(--spacing-2);
}

.doc-manager__asset-img {
  max-width: 280px;
  max-height: 280px;
  border-radius: var(--radius-base);
  border: 1px solid var(--color-border);
  object-fit: contain;
}
</style>
