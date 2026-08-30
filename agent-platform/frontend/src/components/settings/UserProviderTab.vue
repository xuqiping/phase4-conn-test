<template>
  <div class="user-provider">
    <div class="user-provider__actions">
      <n-button type="primary" @click="openCreate">
        <template #icon><n-icon :component="AddOutline" /></template>
        添加我的API Key
      </n-button>
    </div>

    <div v-if="providers.length" class="user-provider__list">
      <div v-for="p in providers" :key="p.id" class="user-provider__card">
        <div class="user-provider__card-header">
          <span class="user-provider__card-name">{{ p.providerName }}</span>
          <n-tag v-if="p.hasApiKey" type="success" size="small">已配置Key</n-tag>
          <n-tag v-else type="warning" size="small">未配置Key</n-tag>
        </div>
        <div class="user-provider__card-meta">
          <span v-if="p.apiEndpoint">{{ p.apiEndpoint }}</span>
          <span v-else class="user-provider__inherit">继承全局端点</span>
        </div>
        <div class="user-provider__card-actions">
          <n-button size="small" quaternary :loading="testingId === p.id" @click="handleTest(p.id)">测试连通</n-button>
          <n-button size="small" quaternary @click="openEdit(p)">编辑</n-button>
          <n-button size="small" quaternary type="error" @click="handleDelete(p.id)">删除</n-button>
        </div>
      </div>
    </div>
    <InkEmptyState v-else type="data" description="暂未配置个人API Key，将使用全局供应商" />

    <n-modal v-model:show="showModal" preset="card" :title="editingId ? '编辑API Key' : '添加API Key'" :style="{ maxWidth: '480px', width: '90vw' }">
      <n-form label-placement="left" label-width="100">
        <n-form-item label="供应商">
          <n-select v-model:value="form.providerName" :options="globalProviderOptions" placeholder="选择供应商" :disabled="!!editingId" />
        </n-form-item>
        <n-form-item label="API端点">
          <n-input v-model:value="form.apiEndpoint" placeholder="留空则继承全局配置" />
        </n-form-item>
        <n-form-item label="API Key">
          <n-input v-model:value="form.apiKey" type="password" show-password-on="click" placeholder="sk-..." />
        </n-form-item>
        <n-form-item label="模型列表">
          <n-input v-model:value="form.models" type="textarea" :autosize="{ minRows: 2 }" placeholder='留空则继承全局模型' />
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
import { ref, onMounted, computed } from 'vue'
import { NButton, NIcon, NModal, NForm, NFormItem, NInput, NSelect, NTag, useMessage } from 'naive-ui'
import { AddOutline } from '@vicons/ionicons5'
import { llmApi } from '@/api/llm'
import InkEmptyState from '@/components/InkEmptyState.vue'
import type { UserLlmProvider, UserLlmProviderRequest, LlmProvider } from '@/api/llm'

const message = useMessage()
const saving = ref(false)
const testing = ref(false)
const testingId = ref<number | null>(null)
const providers = ref<UserLlmProvider[]>([])
const globalProviders = ref<LlmProvider[]>([])
const showModal = ref(false)
const editingId = ref<number | null>(null)

const form = ref<UserLlmProviderRequest>({
  providerName: '',
  apiEndpoint: '',
  apiKey: '',
  models: ''
})

const globalProviderOptions = computed(() =>
  globalProviders.value.map(p => ({ label: p.displayName || p.name, value: p.name }))
)

onMounted(load)

async function load() {
  const [userRes, globalRes] = await Promise.all([
    llmApi.listUserProviders(),
    llmApi.listProviders()
  ])
  providers.value = userRes.data.data
  globalProviders.value = globalRes.data.data
}

function openCreate() {
  editingId.value = null
  form.value = { providerName: '', apiEndpoint: '', apiKey: '', models: '' }
  showModal.value = true
}

function openEdit(p: UserLlmProvider) {
  editingId.value = p.id
  form.value = {
    providerName: p.providerName,
    apiEndpoint: p.apiEndpoint ?? '',
    apiKey: '',
    models: p.models ?? ''
  }
  showModal.value = true
}

async function handleTest(id: number) {
  testingId.value = id
  try {
    const res = await llmApi.testUserProviderConnection(id)
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
  if (editingId.value) {
    testing.value = true
    try {
      const res = await llmApi.testUserProviderConnection(editingId.value)
      const r = res.data.data
      if (r.success) {
        message.success(`连接成功 · ${r.model} · ${r.durationMs}ms`)
      } else {
        message.error(r.message)
      }
    } catch {
      // error handled by interceptor
    } finally {
      testing.value = false
    }
  } else {
    message.info('请先保存后再测试连通')
  }
}

async function handleSave() {
  saving.value = true
  try {
    await llmApi.createUserProvider(form.value)
    message.success('保存成功')
    showModal.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function handleDelete(id: number) {
  await llmApi.deleteUserProvider(id)
  message.success('删除成功')
  await load()
}
</script>

<style lang="scss" scoped>
.user-provider__actions {
  margin-bottom: 16px;
}

.user-provider__list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.user-provider__card {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-base);
  padding: 12px 16px;
}

.user-provider__card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.user-provider__card-name {
  font-weight: 600;
  color: var(--color-text-primary);
  text-transform: capitalize;
}

.user-provider__card-meta {
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin-bottom: 8px;
}

.user-provider__inherit {
  font-style: italic;
}

.user-provider__card-actions {
  display: flex;
  gap: 8px;
}
</style>
