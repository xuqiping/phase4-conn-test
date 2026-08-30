<template>
  <n-modal :show="show" preset="card" title="文档治理信息" style="width: 680px" @update:show="onShow">
    <n-form label-placement="left" label-width="110">
      <n-grid :cols="2" :x-gap="16">
        <n-form-item-gi label="责任人 ID">
          <n-input-number v-model:value="form.ownerId" clearable :min="1" style="width:100%" />
        </n-form-item-gi>
        <n-form-item-gi label="来源类型">
          <n-select v-model:value="form.sourceType" clearable :options="sourceOptions" />
        </n-form-item-gi>
        <n-form-item-gi label="权威等级">
          <n-select v-model:value="form.authorityLevel" :options="authorityOptions" />
        </n-form-item-gi>
        <n-form-item-gi label="密级">
          <n-select v-model:value="form.confidentialityLevel" :options="confidentialityOptions" :disabled="!isAdmin" />
        </n-form-item-gi>
      </n-grid>
      <n-form-item label="来源地址">
        <n-input v-model:value="form.sourceUri" maxlength="2000" placeholder="URL、系统编号或人工来源标识；不要填写密钥" />
      </n-form-item>
      <n-form-item label="来源更新时间">
        <n-date-picker v-model:value="form.sourceUpdatedAt" type="datetime" clearable style="width:100%" />
      </n-form-item>
      <n-form-item label="生效区间">
        <n-date-picker v-model:value="form.effectiveRange" type="datetimerange" clearable style="width:100%" />
      </n-form-item>
      <n-form-item label="标签">
        <n-dynamic-tags v-model:value="form.tags" :max="20" />
      </n-form-item>
      <n-alert v-if="!isAdmin" type="info" :bordered="false">只有管理员可以调整密级；其他治理字段仍可由知识库管理员维护。</n-alert>
    </n-form>
    <template #footer>
      <n-space justify="end">
        <n-button @click="emit('cancel')">取消</n-button>
        <n-button type="primary" :loading="loading" @click="submit">保存</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import { NAlert, NButton, NDatePicker, NDynamicTags, NForm, NFormItem, NFormItemGi, NGrid, NInput, NInputNumber, NModal, NSelect, NSpace } from 'naive-ui'
import type { KnowledgeDocument, KnowledgeDocumentMetadataUpdate } from '@/api/knowledge'

const props = defineProps<{ show: boolean; document: KnowledgeDocument | null; loading: boolean; isAdmin: boolean }>()
const emit = defineEmits<{ (e: 'confirm', value: KnowledgeDocumentMetadataUpdate): void; (e: 'cancel'): void }>()

const form = reactive({
  ownerId: null as number | null,
  sourceType: null as string | null,
  sourceUri: '',
  sourceUpdatedAt: null as number | null,
  authorityLevel: 'REFERENCE' as KnowledgeDocument['authorityLevel'],
  confidentialityLevel: 'INTERNAL' as KnowledgeDocument['confidentialityLevel'],
  effectiveRange: null as [number, number] | null,
  tags: [] as string[]
})

const sourceOptions = ['UPLOAD', 'URL', 'API', 'SYNC', 'MANUAL'].map(value => ({ label: value, value }))
const authorityOptions = [
  { label: '官方发布', value: 'OFFICIAL' }, { label: '已审批', value: 'APPROVED' },
  { label: '参考资料', value: 'REFERENCE' }, { label: '未验证', value: 'UNVERIFIED' }
]
const confidentialityOptions = [
  { label: '公开', value: 'PUBLIC' }, { label: '内部', value: 'INTERNAL' },
  { label: '机密', value: 'CONFIDENTIAL' }, { label: '严格受限', value: 'RESTRICTED' }
]

watch(() => [props.show, props.document] as const, () => {
  const doc = props.document
  if (!props.show || !doc) return
  form.ownerId = doc.ownerId
  form.sourceType = doc.sourceType
  form.sourceUri = doc.sourceUri || ''
  form.sourceUpdatedAt = doc.sourceUpdatedAt ? Date.parse(doc.sourceUpdatedAt) : null
  form.authorityLevel = doc.authorityLevel || 'REFERENCE'
  form.confidentialityLevel = doc.confidentialityLevel || 'INTERNAL'
  form.effectiveRange = doc.effectiveAt && doc.expiredAt
    ? [Date.parse(doc.effectiveAt), Date.parse(doc.expiredAt)] : null
  form.tags = [...(doc.tags || [])]
}, { immediate: true })

function submit() {
  emit('confirm', {
    ownerId: form.ownerId,
    sourceType: form.sourceType,
    sourceUri: form.sourceUri.trim() || null,
    sourceUpdatedAt: form.sourceUpdatedAt ? new Date(form.sourceUpdatedAt).toISOString() : null,
    authorityLevel: form.authorityLevel,
    confidentialityLevel: form.confidentialityLevel,
    tags: form.tags.map(t => t.trim()).filter(Boolean),
    effectiveAt: form.effectiveRange ? new Date(form.effectiveRange[0]).toISOString() : null,
    expiredAt: form.effectiveRange ? new Date(form.effectiveRange[1]).toISOString() : null
  })
}

function onShow(value: boolean) { if (!value && !props.loading) emit('cancel') }
</script>
