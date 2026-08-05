<!--
  项目资产库·一致性包编辑  plan §S10（10b）/ 设计 §五
  - 一个角色的「定妆档案」：主参考图 fileId / 图集 fileIds / 标准描述 / 参数基线
  - 人物/道具/场景类资产额染（drawer 按 roleKeys 决定是否渲染本组件）
  - 字段约定入 content.consistency（后端 AssetVersionService.saveConsistencyPack 局部合并：null=不改，产新版本）
  - galleryFileIds 用 n-dynamic-tags 编辑；主参考图/参数基线用文本（MVP 不引上传选择器，留 S11/S12 共线）
-->
<template>
  <div class="consistency-pack">
    <h4 class="consistency-pack__title">一致性包</h4>
    <p class="consistency-pack__hint">生成时整套注入，保角色形象不变（主参考图 + 图集 + 标准描述 + 参数基线）。</p>

    <n-form label-placement="top" size="small">
      <n-form-item label="主参考图 fileId">
        <n-input v-model:value="form.mainRefImageFileId" placeholder="图片文件 id" :disabled="!canEdit" clearable />
      </n-form-item>
      <n-form-item label="图集 fileIds">
        <n-dynamic-tags v-model:value="form.galleryFileIds" :disabled="!canEdit" />
      </n-form-item>
      <n-form-item label="标准描述">
        <n-input v-model:value="form.standardDescription" type="textarea" :rows="3" :disabled="!canEdit" placeholder="角色的标准外观/性格描述" />
      </n-form-item>
      <n-form-item label="参数基线">
        <n-input v-model:value="form.paramBaseline" type="textarea" :rows="2" :disabled="!canEdit" placeholder="生成参数 JSON 基线（可选）" />
      </n-form-item>
    </n-form>

    <n-button v-if="canEdit" size="small" type="primary" :loading="saving" @click="save">保存一致性包</n-button>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { NButton, NDynamicTags, NForm, NFormItem, NInput, useMessage } from 'naive-ui'
import { versionApi } from '@/api/assets'

/** 一致性包字段（设计 §五；对齐 ConsistencyPackRequest） */
export interface ConsistencyPack {
  mainRefImageFileId?: string | null
  galleryFileIds?: string[]
  standardDescription?: string | null
  paramBaseline?: string | null
}

const props = defineProps<{
  assetId: number | null
  canEdit?: boolean
  /** 初始值（由 drawer 从 asset.content.consistency 解析后传入） */
  initial?: ConsistencyPack | null
}>()

const emit = defineEmits<{
  (e: 'saved', assetId: number): void
}>()

const message = useMessage()
const saving = ref(false)

const form = ref<{
  mainRefImageFileId: string
  galleryFileIds: string[]
  standardDescription: string
  paramBaseline: string
}>({
  mainRefImageFileId: '',
  galleryFileIds: [],
  standardDescription: '',
  paramBaseline: ''
})

watch(
  () => props.initial,
  (init) => {
    form.value = {
      mainRefImageFileId: init?.mainRefImageFileId ?? '',
      galleryFileIds: init?.galleryFileIds ? [...init.galleryFileIds] : [],
      standardDescription: init?.standardDescription ?? '',
      paramBaseline: init?.paramBaseline ?? ''
    }
  },
  { immediate: true }
)

async function save() {
  if (!props.assetId) return
  saving.value = true
  try {
    await versionApi.saveConsistencyPack(props.assetId, {
      // 空串 → null（后端 null=不改；但保存意图=清空/覆盖，故空串传 null 表示「该字段置空」由后端局部合并语义处理）
      mainRefImageFileId: form.value.mainRefImageFileId || null,
      galleryFileIds: form.value.galleryFileIds,
      standardDescription: form.value.standardDescription || null,
      paramBaseline: form.value.paramBaseline || null
    })
    message.success('一致性包已保存（产新版本）')
    emit('saved', props.assetId)
  } catch {
    message.error('保存一致性包失败')
  } finally {
    saving.value = false
  }
}

defineExpose({ form, save })
</script>

<style lang="scss" scoped>
.consistency-pack {
  &__title {
    margin: 0 0 var(--spacing-1);
    font-size: var(--font-size-md);
    color: var(--color-text-primary);
  }

  &__hint {
    margin: 0 0 var(--spacing-3);
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
  }
}
</style>
