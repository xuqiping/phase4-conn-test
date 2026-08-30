<template>
  <n-modal
    :show="show"
    preset="card"
    title="存入资产库 · 图片"
    style="max-width: 520px"
    @update:show="onUpdateShow"
  >
    <n-form label-placement="top" :show-require-mark="false">
      <n-form-item label="目标项目">
        <n-select
          v-model:value="form.projectId"
          :options="projectOptions"
          placeholder="选择可写项目"
          :loading="loadingProjects"
        />
      </n-form-item>
      <n-form-item label="名称">
        <n-input v-model:value="form.name" placeholder="资产名称" :maxlength="100" show-count />
      </n-form-item>
      <n-form-item label="描述">
        <n-input v-model:value="form.description" type="textarea" :rows="2" :maxlength="500" />
      </n-form-item>
    </n-form>

    <template #action>
      <n-button @click="close">取消</n-button>
      <n-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">
        入库
      </n-button>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { NButton, NForm, NFormItem, NInput, NModal, NSelect, useMessage } from 'naive-ui'
import { projectApi, assetBridgeApi } from '@/api/assets'
import type { AssetProjectVO, MediaImportVO } from '@/types/asset'

/**
 * 生图结果入库弹窗（生成→库）。
 * 与画布 SaveToAssetDialog 的区别：生图入库无画布节点/叙事角色/重复检测三态——
 * 同一张图可多次入库为独立资产（用户可能想存进不同项目）。created 恒 true，无 dup 分支。
 * 后端：POST /assets/from-media（assetBridgeApi.importFromMedia）。
 */
const props = defineProps<{
  show: boolean
  /** 生图任务 id（来自 ImageGenView 当前任务）。 */
  taskId: number | null
  /** 入库的目标图下标（0-based，对应 imageUrls/imageFileIds 顺序）。 */
  imageIdx: number | null
  /** 默认资产名（取生成时的提示词截断或「图片产出」）。 */
  defaultName?: string
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  /** 入库成功：父可关弹窗/刷新。 */
  (e: 'imported', payload: { assetId: number; name: string }): void
}>()

const message = useMessage()

interface FormState {
  projectId: number | null
  name: string
  description: string
}
const form = reactive<FormState>({ projectId: null, name: '', description: '' })
const projects = ref<AssetProjectVO[]>([])
const loadingProjects = ref(false)
const submitting = ref(false)

/** 仅列出当前用户可写项目（owner/editor；viewer 不可入库，与画布桥一致）。 */
const projectOptions = computed(() =>
  projects.value
    .filter(p => p.role === 'OWNER' || p.role === 'EDITOR')
    .map(p => ({ label: p.name, value: p.id }))
)

const canSubmit = computed(
  () => props.taskId != null && props.imageIdx != null && form.projectId != null && !submitting.value
)

/** 弹窗打开：重置表单 + 拉项目列表 + 名称默认。immediate 覆盖首挂 show=true。 */
watch(
  () => props.show,
  async (open) => {
    if (!open) return
    form.projectId = null
    form.description = ''
    form.name = (props.defaultName ?? '').trim()
    if (!projects.value.length) {
      loadingProjects.value = true
      try {
        const res = await projectApi.list()
        projects.value = res.data.data ?? []
      } catch {
        message.error('项目列表加载失败')
      } finally {
        loadingProjects.value = false
      }
    }
  },
  { immediate: true }
)

function onUpdateShow(v: boolean) {
  emit('update:show', v)
}

function close() {
  emit('update:show', false)
}

async function submit() {
  if (props.taskId == null || props.imageIdx == null || form.projectId == null) return
  submitting.value = true
  try {
    const res = await assetBridgeApi.importFromMedia({
      taskId: props.taskId,
      imageIdx: props.imageIdx,
      projectId: form.projectId,
      name: form.name.trim() || undefined,
      description: form.description.trim() || undefined
    })
    const vo: MediaImportVO = res.data.data
    message.success(vo.message || '已入库')
    emit('imported', {
      assetId: vo.assetId ?? 0,
      name: vo.name || form.name.trim() || '图片资产'
    })
    close()
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } }; message?: string }
    message.error(err?.response?.data?.message ?? err?.message ?? '入库失败')
  } finally {
    submitting.value = false
  }
}
</script>
