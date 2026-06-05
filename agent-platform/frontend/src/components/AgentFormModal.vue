<template>
  <n-modal v-model:show="visible" preset="card" :title="isEdit ? '编辑 Agent' : '新建 Agent'" style="max-width:500px">
    <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
      <n-form-item label="名称" path="name">
        <n-input v-model:value="form.name" placeholder="请输入Agent名称" />
      </n-form-item>
      <n-form-item label="描述" path="description">
        <n-input v-model:value="form.description" type="textarea" placeholder="请输入描述" :rows="3" />
      </n-form-item>
      <n-form-item label="分组" path="groupId">
        <n-select v-model:value="form.groupId" :options="groupOptions" placeholder="选择分组" />
      </n-form-item>
      <n-form-item label="头像URL" path="avatar">
        <n-input v-model:value="form.avatar" placeholder="可选，头像图片地址" />
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
import type { FormInst, FormRules } from 'naive-ui'
import { agentApi, type AgentGroup } from '@/api/agent'

const props = defineProps<{
  groups: AgentGroup[]
  editData?: { id: number; name: string; description: string | null; avatar: string | null; groupId: number | null } | null
}>()

const emit = defineEmits<{
  (e: 'created'): void
  (e: 'updated'): void
}>()

const message = useMessage()
const visible = defineModel<boolean>('show', { default: false })
const formRef = ref<FormInst | null>(null)
const saving = ref(false)

const isEdit = computed(() => !!props.editData)

const form = ref({
  name: '',
  description: '',
  groupId: null as number | null,
  avatar: ''
})

const groupOptions = computed(() =>
  props.groups.map(g => ({ label: g.name, value: g.id }))
)

const rules: FormRules = {
  name: [{ required: true, message: '请输入Agent名称', trigger: 'blur' }],
  groupId: [{ required: true, type: 'number', message: '请选择分组', trigger: 'change' }]
}

watch(visible, (val) => {
  if (val && props.editData) {
    form.value = {
      name: props.editData.name,
      description: props.editData.description || '',
      groupId: props.editData.groupId,
      avatar: props.editData.avatar || ''
    }
  } else if (val) {
    form.value = { name: '', description: '', groupId: null, avatar: '' }
  }
})

async function handleSubmit() {
  try { await formRef.value?.validate() } catch { return }
  if (!form.value.groupId) return

  saving.value = true
  try {
    if (isEdit.value && props.editData) {
      await agentApi.updateAgent(props.editData.id, {
        name: form.value.name,
        description: form.value.description || undefined,
        avatar: form.value.avatar || undefined,
        groupId: form.value.groupId
      })
      message.success('更新成功')
      emit('updated')
    } else {
      await agentApi.createAgent({
        name: form.value.name,
        description: form.value.description || undefined,
        avatar: form.value.avatar || undefined,
        groupId: form.value.groupId
      })
      message.success('创建成功')
      emit('created')
    }
    visible.value = false
  } catch {
    message.error(isEdit.value ? '更新失败' : '创建失败')
  } finally {
    saving.value = false
  }
}
</script>
