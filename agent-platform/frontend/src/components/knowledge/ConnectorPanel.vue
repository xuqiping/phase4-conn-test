<template>
  <div class="connector-panel">
    <n-space vertical size="medium">
      <n-alert type="info" :show-icon="false">
        连接器把外部源（URL 站点 / S3 兼容网盘 / WebDAV）的文档定时同步进本知识库：etag 未变不动、
        变更自动建新版本、源端删除默认隔离（可开「源删同步删」）。凭证加密存储，保存后不再回显。
      </n-alert>

      <n-space justify="space-between">
        <n-button type="primary" @click="openCreate">
          <template #icon><n-icon :component="AddOutline" /></template>
          新建连接器
        </n-button>
        <n-button :loading="loading" @click="load">刷新</n-button>
      </n-space>

      <n-data-table
        :columns="columns"
        :data="connectors"
        :loading="loading"
        :pagination="false"
        :row-key="(r: KnowledgeConnector) => r.id"
        size="small"
      />
    </n-space>

    <!-- 新建弹窗：类型切换表单 + cron 预设 -->
    <n-modal v-model:show="showModal" preset="card" title="新建连接器" style="width: 560px; max-width: 94vw">
      <n-space vertical size="small">
        <n-select v-model:value="form.type" :options="typeOptions" placeholder="类型" />
        <n-input v-model:value="form.name" placeholder="连接器名称（如：团队 Wiki）" />

        <!-- URL 站点：种子地址起，同域深度 ≤2 爬取 -->
        <template v-if="form.type === 'URL_SITE'">
          <n-input v-model:value="config.seedUrl" placeholder="种子地址 https://example.com/docs/" />
        </template>

        <!-- S3 兼容：MinIO/OSS/COS（endpoint+bucket+AK/SK） -->
        <template v-else-if="form.type === 'S3'">
          <n-input v-model:value="config.endpoint" placeholder="Endpoint https://s3.example.com" />
          <n-input v-model:value="config.bucket" placeholder="Bucket（如 docs）" />
          <n-input v-model:value="config.prefix" placeholder="Prefix 前缀过滤（可选，如 wiki/）" />
          <n-input v-model:value="config.accessKey" placeholder="Access Key" />
          <n-input v-model:value="config.secretKey" type="password" show-password-on="click" placeholder="Secret Key" />
          <n-input v-model:value="config.region" placeholder="Region（可选，默认 us-east-1）" />
        </template>

        <!-- WebDAV：地址+账号密码（密码可空=匿名公共目录） -->
        <template v-else-if="form.type === 'WEBDAV'">
          <n-input v-model:value="config.baseUrl" placeholder="WebDAV 地址 https://dav.example.com/docs/" />
          <n-input v-model:value="config.username" placeholder="账号" />
          <n-input v-model:value="config.password" type="password" show-password-on="click" placeholder="密码（匿名目录可留空）" />
        </template>

        <n-select v-model:value="cronPreset" :options="cronOptions" placeholder="同步周期" />
        <n-input
          v-if="cronPreset === 'custom'"
          v-model:value="customCron"
          placeholder="Spring 六段 cron：秒 分 时 日 月 周（如 0 30 3 * * MON-FRI）"
        />

        <n-space align="center">
          <n-switch v-model:value="form.syncOnSourceDelete" size="small" />
          <span class="hint">源删同步删（默认关=源端删除仅隔离不召回；开启=源端删除走治理删除链移除本地文档）</span>
        </n-space>

        <n-space justify="end">
          <n-button @click="showModal = false">取消</n-button>
          <n-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">创建</n-button>
        </n-space>
      </n-space>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { h, computed, onMounted, ref } from 'vue'
import {
  NAlert, NButton, NDataTable, NIcon, NInput, NModal, NSelect, NSpace, NSwitch, NTag,
  useDialog, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { AddOutline } from '@vicons/ionicons5'
import { knowledgeApi, type KnowledgeConnector, type ConnectorType } from '@/api/knowledge'

const props = defineProps<{ kbId: number }>()

const message = useMessage()
const dialog = useDialog()
const loading = ref(false)
const submitting = ref(false)
const connectors = ref<KnowledgeConnector[]>([])
const showModal = ref(false)

const typeLabel: Record<ConnectorType, string> = { URL_SITE: 'URL 站点', S3: 'S3 网盘', WEBDAV: 'WebDAV' }
const typeOptions = (Object.keys(typeLabel) as ConnectorType[]).map(t => ({ label: typeLabel[t], value: t }))
const cronOptions = [
  { label: '每小时（整点后 17 分）', value: '0 17 * * * *' },
  { label: '每天 04:00', value: '0 0 4 * * *' },
  { label: '每周一 04:00', value: '0 0 4 * * MON' },
  { label: '自定义…', value: 'custom' }
]

const form = ref<{ type: ConnectorType; name: string; syncOnSourceDelete: boolean }>({
  type: 'URL_SITE', name: '', syncOnSourceDelete: false
})
const config = ref<Record<string, string>>({})
const cronPreset = ref('0 0 4 * * *')
const customCron = ref('')

const statusMeta: Record<string, { label: string; type: 'success' | 'default' | 'error' }> = {
  ENABLED: { label: '运行中', type: 'success' },
  DISABLED: { label: '已停用', type: 'default' },
  ERROR: { label: '错误', type: 'error' }
}

const columns: DataTableColumns<KnowledgeConnector> = [
  { title: '类型', key: 'type', width: 100, render: r => h(NTag, { size: 'small', bordered: false }, () => typeLabel[r.type] || r.type) },
  { title: '名称', key: 'name', ellipsis: { tooltip: true } },
  {
    // C6：ERROR 红标 + 连续错误轮数；摘要全文走 tooltip
    title: '状态', key: 'status', width: 120,
    render: r => h('span', { style: 'display:inline-flex;align-items:center;gap:6px' }, [
      h(NTag, { size: 'small', round: true, type: statusMeta[r.status]?.type || 'default' },
        () => statusMeta[r.status]?.label || r.status),
      r.status === 'ERROR' && r.syncErrorStreak > 0
        ? h('span', { title: `连续 ${r.syncErrorStreak} 轮同步失败，已停调度；修好配置后可「立即同步」重试`, style: 'color:var(--color-danger);cursor:help' }, `×${r.syncErrorStreak}`)
        : null
    ].filter(Boolean))
  },
  { title: '周期', key: 'scheduleCron', width: 130, ellipsis: { tooltip: true } },
  {
    title: '最近同步', key: 'lastSyncAt', width: 160,
    render: r => h('span', { title: r.lastSyncSummary || '尚未同步' },
      r.lastSyncAt ? new Date(r.lastSyncAt).toLocaleString('zh-CN') : '尚未同步')
  },
  {
    title: '结果摘要', key: 'lastSyncSummary', ellipsis: { tooltip: true },
    render: r => r.lastSyncSummary || '-'
  },
  {
    title: '操作', key: 'actions', width: 240, fixed: 'right',
    render: r => h('div', { style: 'display:flex;gap:6px' }, [
      r.status === 'DISABLED'
        ? h(NButton, { size: 'small', onClick: () => toggle(r, 'enable') }, () => '启用')
        : h(NButton, { size: 'small', disabled: r.status === 'ERROR', onClick: () => toggle(r, 'disable') }, () => '停用'),
      h(NButton, { size: 'small', type: 'primary', onClick: () => syncNow(r) }, () => '立即同步'),
      h(NButton, { size: 'small', quaternary: true, type: 'error', onClick: () => confirmDelete(r) }, () => '删除')
    ])
  }
]

/** 类型必填项齐才可提交（与后端结构校验同口径；WEBDAV 密码可空=匿名） */
const canSubmit = computed(() => {
  if (!form.value.name.trim()) return false
  const c = config.value
  if (form.value.type === 'URL_SITE') return /^https?:\/\//.test(c.seedUrl || '')
  if (form.value.type === 'S3') return !!(c.endpoint && /^https?:\/\//.test(c.endpoint) && c.bucket && c.accessKey && c.secretKey)
  return !!(c.baseUrl && /^https?:\/\//.test(c.baseUrl) && c.username)
})

async function load() {
  loading.value = true
  try {
    connectors.value = (await knowledgeApi.listConnectors(props.kbId)).data.data
  } catch {
    message.error('连接器列表加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { type: 'URL_SITE', name: '', syncOnSourceDelete: false }
  config.value = {}
  cronPreset.value = '0 0 4 * * *'
  customCron.value = ''
  showModal.value = true
}

async function submit() {
  submitting.value = true
  try {
    await knowledgeApi.createConnector(props.kbId, {
      name: form.value.name.trim(),
      type: form.value.type,
      config: { ...config.value },
      scheduleCron: cronPreset.value === 'custom' ? customCron.value.trim() : cronPreset.value,
      syncOnSourceDelete: form.value.syncOnSourceDelete
    })
    message.success('连接器已创建，将按周期自动同步（也可立即同步）')
    showModal.value = false
    await load()
  } catch {
    message.error('创建失败（请检查必填项与地址格式）')
  } finally {
    submitting.value = false
  }
}

async function toggle(row: KnowledgeConnector, action: 'enable' | 'disable') {
  try {
    await (action === 'enable' ? knowledgeApi.enableConnector(row.id) : knowledgeApi.disableConnector(row.id))
    message.success(action === 'enable' ? '已启用' : '已停用（配置与同步账本保留）')
    await load()
  } catch {
    message.error('操作失败')
  }
}

async function syncNow(row: KnowledgeConnector) {
  try {
    await knowledgeApi.syncConnectorNow(row.id)
    message.success('已触发同步（异步执行），稍后点「刷新」查看结果')
    // 202 异步：轮询一次延迟刷新拿结果
    setTimeout(() => void load(), 5000)
  } catch {
    message.error('触发失败')
  }
}

function confirmDelete(row: KnowledgeConnector) {
  dialog.warning({
    title: '确认删除连接器',
    content: `删除「${row.name}」？已同步的文档会保留（转为手工管理），仅停止后续自动同步。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await knowledgeApi.deleteConnector(row.id)
        message.success('已删除')
        await load()
      } catch {
        message.error('删除失败')
      }
    }
  })
}

onMounted(() => void load())
</script>

<style lang="scss" scoped>
.connector-panel {
  .hint {
    font-size: 12px;
    opacity: 0.75;
  }
}
</style>
