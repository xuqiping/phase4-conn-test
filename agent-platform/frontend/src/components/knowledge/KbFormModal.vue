<template>
  <n-modal v-model:show="visible" preset="card" :title="modalTitle" style="max-width:520px">
    <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
      <n-form-item label="名称" path="name">
        <n-input v-model:value="form.name" placeholder="请输入知识库名称" />
      </n-form-item>
      <n-form-item label="描述" path="description">
        <n-input v-model:value="form.description" type="textarea" placeholder="可选，知识库用途描述" :rows="2" />
      </n-form-item>
      <n-form-item label="可见性" path="visibility">
        <n-select v-model:value="form.visibility" :options="visibilityOptions" />
      </n-form-item>
      <n-form-item label="摘要策略" path="summaryStrategy">
        <n-select v-model:value="form.summaryStrategy" :options="strategyOptions" />
      </n-form-item>
      <n-form-item label="Embedding 模型" path="embeddingModel">
        <n-input v-model:value="form.embeddingModel" placeholder="留空使用管理员默认向量模型" />
      </n-form-item>
      <n-form-item label="Rerank 模型（可选）" path="rerankModel">
        <n-input v-model:value="form.rerankModel" placeholder="Phase2（如 bge-reranker-v2-m3）" />
      </n-form-item>
    </n-form>
    <template #action>
      <n-button @click="visible = false">取消</n-button>
      <n-button type="primary" :loading="saving" @click="handleSubmit">
        {{ isEdit ? '保存' : '创建' }}
      </n-button>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { NModal, NForm, NFormItem, NInput, NSelect, NButton, useMessage } from 'naive-ui'
import type { FormInst, FormRules, SelectOption } from 'naive-ui'
import { knowledgeApi, type KnowledgeBase, type KnowledgeBaseRequest } from '@/api/knowledge'

const props = defineProps<{
  editData?: KnowledgeBase | null
}>()

const emit = defineEmits<{
  (e: 'saved'): void
}>()

const message = useMessage()
const visible = defineModel<boolean>('show', { default: false })
const formRef = ref<FormInst | null>(null)
const saving = ref(false)

const isEdit = computed(() => !!props.editData)
const modalTitle = computed(() => (isEdit.value ? '编辑知识库' : '新建知识库'))

const visibilityOptions: SelectOption[] = [
  { label: '私有（PRIVATE）', value: 'PRIVATE' },
  { label: '团队（TEAM）', value: 'TEAM' },
  { label: '公开（PUBLIC）', value: 'PUBLIC' }
]
const strategyOptions: SelectOption[] = [
  { label: '逐节摘要（PER_SECTION）', value: 'PER_SECTION' },
  { label: '批量摘要（BATCH）', value: 'BATCH' },
  { label: '混合（HYBRID）', value: 'HYBRID' }
]

const form = ref<KnowledgeBaseRequest>({
  name: '',
  description: '',
  visibility: 'PRIVATE',
  summaryStrategy: 'PER_SECTION',
  embeddingModel: '',
  rerankModel: ''
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }]
}

watch(visible, (val) => {
  if (!val) return
  if (props.editData) {
    form.value = {
      name: props.editData.name,
      description: props.editData.description || '',
      visibility: props.editData.visibility || 'PRIVATE',
      summaryStrategy: props.editData.summaryStrategy || 'PER_SECTION',
      embeddingModel: props.editData.embeddingModel || '',
      rerankModel: props.editData.rerankModel || ''
    }
  } else {
    form.value = {
      name: '',
      description: '',
      visibility: 'PRIVATE',
      summaryStrategy: 'PER_SECTION',
      embeddingModel: '',
      rerankModel: ''
    }
  }
}, { immediate: true })

async function handleSubmit() {
  try { await formRef.value?.validate() } catch { return }
  saving.value = true
  try {
    const payload: KnowledgeBaseRequest = {
      name: form.value.name,
      description: form.value.description || undefined,
      visibility: form.value.visibility,
      summaryStrategy: form.value.summaryStrategy,
      embeddingModel: form.value.embeddingModel || undefined,
      rerankModel: form.value.rerankModel || undefined
    }
    if (isEdit.value && props.editData) {
      await knowledgeApi.updateBase(props.editData.id, payload)
      message.success('更新成功')
    } else {
      await knowledgeApi.createBase(payload)
      message.success('创建成功')
    }
    emit('saved')
    visible.value = false
  } catch {
    message.error(isEdit.value ? '更新失败' : '创建失败')
  } finally {
    saving.value = false
  }
}
</script>
