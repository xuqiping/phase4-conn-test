<template>
  <div class="provider-manage">
    <div class="provider-manage__actions">
      <n-button type="primary" @click="openCreate">
        <template #icon><n-icon :component="AddOutline" /></template>
        添加供应商
      </n-button>
      <n-button @click="handleReload">刷新配置</n-button>
    </div>

    <n-data-table :columns="columns" :data="providers" :loading="loading" :bordered="false" />

    <n-modal v-model:show="showModal" preset="card" :title="editingId ? '编辑供应商' : '添加供应商'" style="width: 520px">
      <n-form label-placement="left" label-width="100">
        <n-form-item label="名称">
          <n-input v-model:value="form.name" placeholder="如 openai, deepseek, claude" />
        </n-form-item>
        <n-form-item label="显示名">
          <n-input v-model:value="form.displayName" placeholder="OpenAI" />
        </n-form-item>
        <n-form-item label="协议">
          <n-select v-model:value="form.protocol" :options="protocolOptions" />
        </n-form-item>
        <n-form-item label="API端点">
          <n-input v-model:value="form.apiEndpoint" placeholder="https://api.openai.com/v1" />
        </n-form-item>
        <n-form-item label="API Key">
          <n-input v-model:value="form.apiKey" type="password" show-password-on="click" placeholder="sk-..." />
        </n-form-item>
        <n-form-item label="模型列表">
          <n-input v-model:value="form.models" type="textarea" :autosize="{ minRows: 2 }" placeholder='["gpt-4o", "gpt-4o-mini"]' />
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
import { ref, onMounted, h } from 'vue'
import { NButton, NIcon, NDataTable, NModal, NForm, NFormItem, NInput, NInputNumber, NSelect, useMessage } from 'naive-ui'
import { AddOutline } from '@vicons/ionicons5'
import { llmApi } from '@/api/llm'
import type { LlmProvider, LlmProviderCreateRequest } from '@/api/llm'

const message = useMessage()
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const providers = ref<LlmProvider[]>([])
const showModal = ref(false)
const editingId = ref<number | null>(null)
const testingId = ref<number | null>(null)

const form = ref<LlmProviderCreateRequest>({
  name: '',
  displayName: '',
  protocol: 'OPENAI_COMPATIBLE',
  apiEndpoint: '',
  apiKey: '',
  models: '',
  sortOrder: 0
})

const protocolOptions = [
  { label: 'OpenAI 兼容', value: 'OPENAI_COMPATIBLE' },
  { label: 'Anthropic / Claude', value: 'ANTHROPIC' }
]

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
  form.value = { name: '', displayName: '', protocol: 'OPENAI_COMPATIBLE', apiEndpoint: '', apiKey: '', models: '', sortOrder: 0 }
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
    sortOrder: row.sortOrder
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

async function handleTest(id: number) {
  testingId.value = id
  try {
    const res = await llmApi.testProviderConnection(id)
    const r = res.data.data
    if (r.success) {
      message.success(`连接成功 · ${r.model} · ${r.durationMs}ms`)
    } else {
      message.error(r.message)
    }
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
  testing.value = true
  try {
    // If editing existing provider, test by id
    if (editingId.value) {
      const res = await llmApi.testProviderConnection(editingId.value)
      const r = res.data.data
      if (r.success) {
        message.success(`连接成功 · ${r.model} · ${r.durationMs}ms`)
      } else {
        message.error(r.message)
      }
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
</script>

<style lang="scss" scoped>
.provider-manage__actions {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
</style>
