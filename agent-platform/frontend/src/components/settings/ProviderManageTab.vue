<template>
  <div class="provider-manage">
    <div class="provider-manage__actions">
      <n-button type="primary" @click="openCreate">
        <template #icon><n-icon :component="AddOutline" /></template>
        添加供应商
      </n-button>
      <n-button @click="handleReload">刷新配置</n-button>
      <!-- 10x-2：全局供应商导出/导入（仅 admin 可达，Tab 已 isAdmin 门控） -->
      <n-button :loading="exporting" aria-label="导出供应商" @click="handleExport">
        <template #icon><n-icon :component="DownloadOutline" /></template>
        导出
      </n-button>
      <n-button :loading="importing" aria-label="导入供应商" @click="triggerImport">
        <template #icon><n-icon :component="CloudUploadOutline" /></template>
        导入
      </n-button>
      <!-- 隐藏 file input，按钮触发点击 -->
      <input
        ref="importInputRef"
        type="file"
        accept=".json,application/json"
        style="display: none"
        @change="onImportFileChange"
      />
    </div>

    <n-data-table :columns="columns" :data="providers" :loading="loading" :scroll-x="1000" :bordered="false" />

    <n-modal v-model:show="showModal" preset="card" :title="editingId ? '编辑供应商' : '添加供应商'" :style="{ maxWidth: '520px', width: '90vw' }">
      <n-form label-placement="left" label-width="100">
        <n-form-item label="名称">
          <n-input v-model:value="form.name" placeholder="如 openai, deepseek, claude" />
        </n-form-item>
        <n-form-item label="显示名">
          <n-input v-model:value="form.displayName" placeholder="OpenAI" />
        </n-form-item>
        <n-form-item v-if="showProtocol" label="协议">
          <n-select v-model:value="form.protocol" :options="protocolOptions" />
        </n-form-item>
        <n-form-item label="API端点">
          <n-input v-model:value="form.apiEndpoint" :placeholder="endpointPlaceholder" />
        </n-form-item>
        <n-form-item label="API Key">
          <n-input v-model:value="form.apiKey" type="password" show-password-on="click" placeholder="sk-..." />
        </n-form-item>
        <n-form-item label="模型列表">
          <n-input v-model:value="form.models" type="textarea" :autosize="{ minRows: 2 }" placeholder='["gpt-4o", "gpt-4o-mini"]' />
        </n-form-item>
        <n-form-item label="类型">
          <n-select v-model:value="form.category" :options="categoryOptions" />
        </n-form-item>
        <n-form-item label="排序">
          <n-input-number v-model:value="form.sortOrder" :min="0" />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button @click="showModal = false">取消</n-button>
        <n-button :loading="testing" @click="handleTestInModal">测试连通</n-button>
        <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, h } from 'vue'
import { NButton, NIcon, NDataTable, NModal, NForm, NFormItem, NInput, NInputNumber, NSelect, NTag, useMessage } from 'naive-ui'
import { AddOutline } from '@vicons/ionicons5'
import { CloudUploadOutline, DownloadOutline } from '@vicons/ionicons5'
import { llmApi } from '@/api/llm'
import type { LlmProvider, LlmProviderCreateRequest, ProviderCategory, LlmProviderExportItem } from '@/api/llm'
import { useDialog } from 'naive-ui'

const message = useMessage()
const dialog = useDialog()
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const exporting = ref(false)
const importing = ref(false)
const providers = ref<LlmProvider[]>([])
const showModal = ref(false)
const editingId = ref<number | null>(null)
const testingId = ref<number | null>(null)
const importInputRef = ref<HTMLInputElement | null>(null)

const form = ref<LlmProviderCreateRequest>({
  name: '',
  displayName: '',
  protocol: 'OPENAI_COMPATIBLE',
  apiEndpoint: '',
  apiKey: '',
  models: '',
  sortOrder: 0,
  category: 'CHAT'
})

/** 协议仅对 CHAT/EMBEDDING 有意义（VIDEO/IMAGE 是任务型协议，走媒体包）；EMBEDDING 禁选 ANTHROPIC（Claude 无 embed 接口）。 */
const showProtocol = computed(() => form.value.category === 'CHAT' || form.value.category === 'EMBEDDING')
const protocolOptions = computed(() => [
  { label: 'OpenAI 兼容', value: 'OPENAI_COMPATIBLE' },
  { label: 'Anthropic / Claude', value: 'ANTHROPIC', disabled: form.value.category === 'EMBEDDING' }
])
watch(() => form.value.category, (cat) => {
  if (cat === 'EMBEDDING' && form.value.protocol === 'ANTHROPIC') {
    form.value.protocol = 'OPENAI_COMPATIBLE'
  }
})

/** endpoint placeholder 按 category 给完整 URL 示例（FR-001 全 URL 直发，运行时零拼接）。 */
const endpointPlaceholder = computed(() => {
  switch (form.value.category) {
    case 'EMBEDDING': return 'https://api.openai.com/v1/embeddings'
    case 'VIDEO': return 'https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks'
    case 'IMAGE': return '生图任务端点完整 URL（生图 provider 尚未接入，先占位）'
    default: return 'https://api.openai.com/v1/chat/completions'
  }
})

/** CHAT/EMBEDDING 软校验：URL 以 base 形态（/v1、/api/v3…）结尾时警告不拦截（大概率漏填 API 路径）。 */
function warnIfBaseUrl() {
  const url = (form.value.apiEndpoint ?? '').trim()
  if ((form.value.category === 'CHAT' || form.value.category === 'EMBEDDING') && /\/(api\/)?v\d+\/?$/i.test(url)) {
    message.warning('端点疑似 base URL（缺 API 路径）：全 URL 直发后运行时将原样请求该地址，建议补全如 /chat/completions')
  }
}

const categoryOptions = [
  { label: '对话 (CHAT)', value: 'CHAT' },
  { label: '向量 (EMBEDDING)', value: 'EMBEDDING' },
  { label: '视频 (VIDEO)', value: 'VIDEO' },
  { label: '生图 (IMAGE·预留)', value: 'IMAGE' }
]

/** category badge 配色：对话=蓝，向量=绿，视频=红，生图=橙（预留）。 */
const CATEGORY_TAG: Record<string, { label: string; type: 'success' | 'warning' | 'info' | 'error' }> = {
  CHAT: { label: '对话', type: 'info' },
  EMBEDDING: { label: '向量', type: 'success' },
  VIDEO: { label: '视频', type: 'error' },
  IMAGE: { label: '生图', type: 'warning' }
}

const columns = [
  { title: 'ID', key: 'id', width: 60 },
  { title: '名称', key: 'name', width: 100 },
  { title: '显示名', key: 'displayName', width: 120 },
  {
    title: '协议', key: 'protocol', width: 130,
    render: (row: LlmProvider) => row.protocol === 'ANTHROPIC' ? 'Anthropic' : 'OpenAI兼容'
  },
  { title: '端点', key: 'apiEndpoint', ellipsis: true },
  { title: '状态', key: 'status', width: 80 },
  {
    title: '类型', key: 'category', width: 110,
    render: (row: LlmProvider) => {
      const tag = CATEGORY_TAG[row.category ?? 'CHAT'] ?? CATEGORY_TAG['CHAT']
      return h(NTag, { size: 'small', type: tag.type, bordered: false }, { default: () => tag.label })
    }
  },
  {
    title: '维度', key: 'dim', width: 80,
    render: (row: LlmProvider) => row.dim ?? '—'
  },
  { title: '排序', key: 'sortOrder', width: 60 },
  {
    title: '操作', key: 'actions', width: 200,
    render: (row: LlmProvider) => [
      h(NButton, { size: 'small', quaternary: true, loading: testingId.value === row.id, onClick: () => handleTest(row.id) }, { default: () => '测试' }),
      h(NButton, { size: 'small', quaternary: true, onClick: () => openEdit(row) }, { default: () => '编辑' }),
      h(NButton, { size: 'small', quaternary: true, type: 'error', onClick: () => handleDelete(row.id) }, { default: () => '删除' })
    ]
  }
]

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res = await llmApi.listProviders()
    providers.value = res.data.data
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.value = { name: '', displayName: '', protocol: 'OPENAI_COMPATIBLE', apiEndpoint: '', apiKey: '', models: '', sortOrder: 0, category: 'CHAT' }
  showModal.value = true
}

function openEdit(row: LlmProvider) {
  editingId.value = row.id
  form.value = {
    name: row.name,
    displayName: row.displayName ?? '',
    protocol: row.protocol ?? 'OPENAI_COMPATIBLE',
    apiEndpoint: row.apiEndpoint ?? '',
    apiKey: '',
    models: row.models ?? '',
    sortOrder: row.sortOrder,
    category: (row.category ?? 'CHAT') as ProviderCategory
  }
  showModal.value = true
}

async function handleSave() {
  saving.value = true
  try {
    if (editingId.value) {
      await llmApi.updateProvider(editingId.value, form.value)
      message.success('更新成功')
    } else {
      await llmApi.createProvider(form.value)
      message.success('创建成功')
    }
    warnIfBaseUrl()
    showModal.value = false
    await load()
  } catch {
    // error handled by interceptor
  } finally {
    saving.value = false
  }
}

async function handleDelete(id: number) {
  await llmApi.deleteProvider(id)
  message.success('删除成功')
  await load()
}

/** 测试类型四分：EMBEDDING→embed 取维度；VIDEO→任务端点零成本探测；IMAGE→预留不发请求；CHAT→chat 短对话。 */
type TestKind = 'chat' | 'embed' | 'video' | 'image'

function testKindOf(category: string | undefined): TestKind {
  if (category === 'EMBEDDING') return 'embed'
  if (category === 'VIDEO') return 'video'
  if (category === 'IMAGE') return 'image'
  return 'chat'
}

/** 按行分流测试。IMAGE 尚未接入生图 provider，直接提示不发请求。 */
async function runTest(id: number, kind: TestKind) {
  if (kind === 'image') {
    message.info('生图 provider 尚未接入，配置已保存（测试将在接入后开放）')
    return
  }
  const res = kind === 'embed'
    ? await llmApi.testProviderEmbedding(id)
    : kind === 'video'
      ? await llmApi.testProviderVideo(id)
      : await llmApi.testProviderConnection(id)
  const r = res.data.data
  if (r.success) {
    // embed/video: 后端 message 已含完整信息；chat: 拼 model + 耗时
    message.success(kind === 'chat'
      ? `连接成功 · ${r.model} · ${r.durationMs}ms`
      : `${r.message}${r.durationMs != null ? ` · ${r.durationMs}ms` : ''}`)
  } else {
    message.error(r.message)
  }
}

async function handleTest(id: number) {
  const row = providers.value.find(p => p.id === id)
  const kind = testKindOf(row?.category)
  testingId.value = id
  try {
    await runTest(id, kind)
  } catch {
    // error handled by interceptor
  } finally {
    testingId.value = null
  }
}

async function handleTestInModal() {
  if (!form.value.apiEndpoint) {
    message.warning('请先填写API端点')
    return
  }
  const kind = testKindOf(form.value.category)
  // IMAGE 预留：无需保存即可提示（不发请求）
  if (kind === 'image') {
    message.info('生图 provider 尚未接入，配置已保存（测试将在接入后开放）')
    return
  }
  testing.value = true
  try {
    // If editing existing provider, test by id（按 category 分流：EMBEDDING→embed / VIDEO→任务端点探测 / 其余→chat）
    if (editingId.value) {
      await runTest(editingId.value, kind)
    } else {
      message.info('请先保存供应商后再测试连通')
    }
  } catch {
    // error handled by interceptor
  } finally {
    testing.value = false
  }
}

async function handleReload() {
  await llmApi.reloadProviders()
  message.success('配置已刷新')
}

// ===== 10x-2：导出/导入全局供应商 =====

/** 导出：含明文 API Key，二次确认后下载 JSON 文件。 */
function handleExport() {
  dialog.warning({
    title: '导出供应商',
    content: '导出文件将包含明文 API Key，请妥善保管。确认导出？',
    positiveText: '确认导出',
    negativeText: '取消',
    onPositiveClick: doExport
  })
}

async function doExport() {
  exporting.value = true
  try {
    const res = await llmApi.exportProviders()
    // responseType: 'blob' → res 是 Blob；触发浏览器下载
    const blob = res as unknown as Blob
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `llm-providers-${new Date().toISOString().slice(0, 10)}.json`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    message.success('导出成功')
  } catch {
    // error handled by interceptor
  } finally {
    exporting.value = false
  }
}

/** 导入入口：触发隐藏 file input 选文件。 */
function triggerImport() {
  importInputRef.value?.click()
}

/** 选定文件后：解析 JSON → 预览新增/更新/非法数 → 确认后提交。 */
async function onImportFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  // 重置 input.value 允许重复选同一文件
  input.value = ''
  if (!file) return

  // 读文件内容
  let items: LlmProviderExportItem[]
  try {
    const text = await file.text()
    const parsed = JSON.parse(text)
    if (!Array.isArray(parsed)) {
      message.error('文件格式错误：应为 JSON 数组')
      return
    }
    items = parsed
  } catch {
    message.error('文件解析失败：不是合法 JSON')
    return
  }

  // 预检：粗分新增候选 / 非法（仅前端提示，真实 upsert 以服务端为准）
  const seen = new Set(providers.value.map((p) => p.name))
  const willCreate = items.filter((it) => it.name && !seen.has(it.name)).length
  const willUpdate = items.filter((it) => it.name && seen.has(it.name)).length
  const invalid = items.filter((it) => !it.name || !it.apiEndpoint).length

  dialog.warning({
    title: '确认导入',
    content: `共 ${items.length} 条：预计新增 ${willCreate} / 更新 ${willUpdate}${invalid ? ` / 疑似非法 ${invalid}` : ''}。同名供应商的非空字段将被覆盖（API Key 为空则保留原值）。确认导入？`,
    positiveText: '确认导入',
    negativeText: '取消',
    onPositiveClick: () => doImport(items)
  })
}

async function doImport(items: LlmProviderExportItem[]) {
  importing.value = true
  try {
    const res = await llmApi.importProviders(items)
    const r = res.data.data
    message.success(`导入完成：新增 ${r.created} / 更新 ${r.updated} / 失败 ${r.failed}`)
    if (r.failed > 0 && r.errors?.length) {
      // 失败详情走 console 供排查，不弹大段文本
      console.warn('供应商导入失败明细', r.errors)
    }
    await load()
  } catch {
    // error handled by interceptor
  } finally {
    importing.value = false
  }
}
</script>

<style lang="scss" scoped>
.provider-manage__actions {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

@media (max-width: 768px) {
  .provider-manage__actions {
    flex-wrap: wrap;
  }
}
</style>
