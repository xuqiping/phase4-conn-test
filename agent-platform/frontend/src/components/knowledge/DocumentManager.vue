<template>
  <div class="doc-manager">
    <!-- 上传区 -->
    <n-upload
      v-if="canWrite"
      :custom-request="customUpload"
      :show-file-list="false"
      multiple
      accept=".md,.markdown,.txt,.pdf,.docx,.doc,.html,.htm,.xlsx,.xls,.csv,.json,.ppt,.pptx,.png,.jpg,.jpeg,.gif,.webp,.bmp"
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

    <!-- 14x-3：直接输入文本入库（免上传文件）。复用后端 indexMode=MANUAL 链路：文本作为唯一索引内容，
         构造内存 .txt 留档原件，解析侧 manualExtracted 生成单 section 节点。 -->
    <div v-if="canWrite" class="doc-manager__inline-entry">
      <n-button size="small" @click="textModalShow = true">
        <template #icon><n-icon :component="CreateOutline" /></template>
        直接输入文本入库
      </n-button>
      <span class="doc-manager__hint doc-manager__hint--sub">无需文件，粘贴/手写内容直接进知识库（≤4000 字）</span>
    </div>

    <!-- C1：关联建议入口（仅 canManage——建议页是治理视图） -->
    <div v-if="canManage" class="doc-manager__inline-entry">
      <n-button size="small" @click="suggestionModalShow = true">
        <template #icon><n-icon :component="LinkOutline" /></template>
        关联建议
      </n-button>
      <span class="doc-manager__hint doc-manager__hint--sub">共召回统计自动发现「总被一起查」的文档对，采纳后命中即带出</span>
    </div>

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
    <InkEmptyState v-if="!loading && docs.length === 0" type="data" description="暂无文档，上传一个开始解析与索引" />

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
      <!-- 14x#2：新版本上传走 createVersion（后端 canManage 门），非 canWrite -->
      <div class="doc-manager__version-upload" v-if="canManage && activeVersionDoc">
        <n-input v-model:value="versionChangeNote" placeholder="版本说明（可选）" />
        <n-upload :show-file-list="false" :custom-request="uploadNewVersion">
          <n-button type="primary">上传新版本</n-button>
        </n-upload>
      </div>
      <n-data-table :columns="versionColumns" :data="versions" :loading="versionLoading" :pagination="false" />
    </n-modal>

    <!-- 14x-3：直接输入文本入库弹窗 -->
    <n-modal v-model:show="textModalShow" preset="card" title="直接输入文本入库" style="width: 640px">
      <n-space vertical>
        <n-input v-model:value="textTitle" placeholder="标题（可选，默认取正文首行）" maxlength="100" />
        <n-input
          v-model:value="textContent"
          type="textarea"
          placeholder="粘贴或输入要进知识库的内容（≤4000 字，作为索引与检索正文）"
          :rows="10"
          maxlength="4000"
          show-count
        />
        <n-space justify="end">
          <n-button :disabled="uploading" @click="textModalShow = false">取消</n-button>
          <n-button type="primary" :loading="uploading" :disabled="!textContent.trim()" @click="submitTextDocument">
            提交入库
          </n-button>
        </n-space>
      </n-space>
    </n-modal>

    <!-- C1：单文档关联管理（成员可读边列表；建/删边 canManage） -->
    <DocumentRelationModal
      v-model:show="relationModalShow"
      :kb-id="kbId"
      :doc="activeRelationDoc"
      :can-manage="!!props.canManage"
    />

    <!-- C1：关联建议（共召回统计→采纳/忽略，仅 canManage） -->
    <DocumentRelationSuggestionModal
      v-model:show="suggestionModalShow"
      :kb-id="kbId"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  NButton, NDataTable, NIcon, NInput, NModal, NSpace, NTag, NUpload, NUploadDragger, useMessage
} from 'naive-ui'
import type { DataTableColumns, UploadCustomRequestOptions } from 'naive-ui'
import { CloudUploadOutline, CreateOutline, LinkOutline } from '@vicons/ionicons5'
import { useKnowledgeStore } from '@/stores/knowledge'
import InkEmptyState from '@/components/InkEmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { knowledgeApi } from '@/api/knowledge'
import type { KnowledgeDocument, KnowledgeDocumentMetadataUpdate, KnowledgeDocumentVersion, KnowledgeNode, SheetPreview, UploadOptions } from '@/api/knowledge'
import DocumentOptionsModal from './DocumentOptionsModal.vue'
import DocumentMetadataModal from './DocumentMetadataModal.vue'
import DocumentRelationModal from './DocumentRelationModal.vue'
import DocumentRelationSuggestionModal from './DocumentRelationSuggestionModal.vue'

const props = defineProps<{
  kbId: number
  /** per-KB 写权限（上传/直传；14x#2 后归位，canRead 授权不再隐含） */
  canWrite: boolean
  /** per-KB 治理权限（元数据/隔离/删除/版本；与后端 canManage 对齐） */
  canManage?: boolean
}>()

const message = useMessage()
const store = useKnowledgeStore()
const authStore = useAuthStore()

const docs = computed(() => store.documents)
const loading = computed(() => store.loadingDocs)
/** 14x#3：当前库对本人是否保密受限（保密 && 非 owner/admin）→ 隐藏原件缩略图/下载入口 */
const assetRestricted = computed(() => {
  const kb = store.bases.find(b => b.id === props.kbId)
  return !!kb?.confidential && kb.createdBy !== authStore.userInfo?.id && !authStore.isAdmin
})
const uploading = ref(false)
const versionModalShow = ref(false)
const versionLoading = ref(false)
const versions = ref<KnowledgeDocumentVersion[]>([])
const activeVersionDoc = ref<KnowledgeDocument | null>(null)
const versionChangeNote = ref('')
const metadataModalShow = ref(false)
const metadataSaving = ref(false)
const activeMetadataDoc = ref<KnowledgeDocument | null>(null)

// C1：单文档关联弹窗 + 库级关联建议弹窗
const relationModalShow = ref(false)
const activeRelationDoc = ref<KnowledgeDocument | null>(null)
const suggestionModalShow = ref(false)

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

/** IMAGE/FILE 原件回显：图片显缩略图，文件显下载按钮（asset URL 经 KB 读权限，跨用户可取）。
 *  14x#3：保密库成员（非 owner/admin）不渲染原件入口，以提示文案替代（asset 403 兜底）。 */
function renderAsset(doc: KnowledgeDocument) {
  if (assetRestricted.value) {
    return h('p', { class: 'doc-manager__node-empty' }, '🔒 保密库：原件仅可经问答召回，如需全文请联系库管理员')
  }
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
  EMBEDDING: { type: 'warning', label: '向量化中' },
  QUARANTINED: { type: 'error', label: '已隔离' }
}

const columns: DataTableColumns<KnowledgeDocument> = [
  {
    type: 'expand',
    expandable: (row) => row.status === 'INDEXED',
    renderExpand: (row) => renderDocNodes(row)
  },
  { title: 'ID', key: 'id', width: 60 },
  {
    title: '标题', key: 'title', ellipsis: { tooltip: true },
    // C2：附件模式 📎 徽标（整件入库不切片，命中注入原件内容）；C6：连接器同步文档 🔌 徽标（来源 external_id）
    render: r => (r.indexMode === 'ATTACHMENT' || r.sourceType === 'CONNECTOR')
      ? h('span', { style: 'display:inline-flex;align-items:center;gap:4px' }, [
          r.indexMode === 'ATTACHMENT'
            && h('span', { title: '附件模式：整件入库，命中后注入原件内容', style: 'cursor:help' }, '📎'),
          r.sourceType === 'CONNECTOR'
            && h('span', { title: `连接器同步文档（源：${r.sourceUri || '未知'}），源端变更时自动更新`, style: 'cursor:help' }, '🔌'),
          r.title
        ].filter(Boolean))
      : r.title
  },
  { title: '类型', key: 'docType', width: 90, render: r => r.docType || '-' },
  {
    title: '状态', key: 'status', width: 100,
    render: r => h(NTag, { size: 'small', round: true, type: statusMap[r.status]?.type || 'default' },
      () => statusMap[r.status]?.label || r.status)
  },
  {
    title: '错误', key: 'parseError', ellipsis: { tooltip: true },
    // 安全体系 S3：隔离原因与解析错误并列展示（QUARANTINED 优先）
    render: r => {
      const err = r.quarantineReason || r.parseError
      return err ? h('span', { style: 'color:var(--color-danger)' }, err) : '-'
    }
  },
  {
    title: '创建', key: 'createdAt', width: 160,
    render: r => new Date(r.createdAt).toLocaleString('zh-CN')
  },
  {
    title: '操作', key: 'actions', width: 290, fixed: 'right',
    render: r => h('div', { style: 'display:flex;gap:6px' }, [
      h(NButton, { size: 'small', quaternary: true, onClick: () => openVersions(r) }, () => '版本'),
      // C1：关联边查看（成员可读）；建/删边在弹窗内按 canManage 显隐
      h(NButton, { size: 'small', quaternary: true, onClick: () => openRelations(r) }, () => '关联'),
      // 14x#2：治理/隔离/删除为 canManage 门（写授权不再放行治理动作）
      props.canManage
        ? h(NButton, { size: 'small', quaternary: true, onClick: () => openMetadata(r) }, () => '治理')
        : null,
      // 安全体系 S3：隔离文档优先给「解除隔离」（复核通过后重走解析），否则给删除
      props.canManage && r.status === 'QUARANTINED'
        ? h(NButton, { size: 'small', quaternary: true, type: 'warning', onClick: () => unquarantine(r) }, () => '解除隔离')
        : null,
      props.canManage
        ? h(NButton, { size: 'small', quaternary: true, type: 'error', onClick: () => remove(r) }, () => '删除')
        : null
    ])
  }
]

function openMetadata(doc: KnowledgeDocument) {
  activeMetadataDoc.value = doc
  metadataModalShow.value = true
}

/** C1：打开单文档关联弹窗（成员可看边列表，理解「🔗 关联带出」证据来源） */
function openRelations(doc: KnowledgeDocument) {
  activeRelationDoc.value = doc
  relationModalShow.value = true
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
    render: r => !props.canManage || !activeVersionDoc.value
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

// ---- 14x-3：直接输入文本入库（docType=FILE + indexMode=MANUAL，复用既有 MANUAL 解析链路）----
const textModalShow = ref(false)
const textTitle = ref('')
const textContent = ref('')

async function submitTextDocument() {
  const text = textContent.value.trim()
  if (!text) return
  uploading.value = true
  try {
    // 标题兜底：未填取正文首行；构造内存 .txt 作为留档原件（下载可见），索引内容用手填文本
    const firstLine = text.split('\n').map(s => s.trim()).find(Boolean) || '文本记录'
    const safeTitle = (textTitle.value.trim() || firstLine).slice(0, 100).replace(/[\\/:*?"<>|]/g, '_')
    const file = new File([text], `${safeTitle}.txt`, { type: 'text/plain' })
    await store.uploadDocument(props.kbId, file, {
      docType: 'FILE',
      indexMode: 'MANUAL',
      manualIndexText: text
    })
    message.success(`已入库：${safeTitle}`)
    textModalShow.value = false
    textTitle.value = ''
    textContent.value = ''
  } catch {
    message.error('文本入库失败')
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

/** 安全体系 S3：解除注入隔离（knowledge:manage）→ 置回 PENDING + 重发解析事件，列表靠轮询自刷新。 */
async function unquarantine(doc: KnowledgeDocument) {
  try {
    await knowledgeApi.unquarantineDocument(doc.id)
    message.success('已解除隔离，重新解析已触发')
    void store.loadDocuments(props.kbId)
  } catch {
    message.error('解除隔离失败（需知识库管理权限）')
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

.doc-manager__inline-entry {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
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
