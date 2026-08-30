<template>
  <!-- 4x-2：视频任务结果入库资产库弹窗（仿 SaveImageToAssetDialog，mediaKind=VIDEO 无 imageIdx） -->
  <n-modal
    :show="show"
    preset="card"
    title="存入资产库 · 视频"
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
 * 视频任务结果入库弹窗（4x-2，生成→库）。
 * 与 SaveImageToAssetDialog 的区别：mediaKind=VIDEO，按任务 resultFileId 整条入库，无 imageIdx。
 * 后端：POST /assets/from-media（mediaKind=VIDEO）。
 */
const props = defineProps<{
  show: boolean
  /** 视频任务 id。 */
  taskId: number | null
  /** 默认资产名（取生成提示词截断或「视频产出」）。 */
  defaultName?: string
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  /** 入库成功：父可引导跳转资产库。 */
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

/** 仅列出当前用户可写项目（owner/editor；viewer 不可入库，与图片/画布桥一致）。 */
const projectOptions = computed(() =>
  projects.value
    .filter(p => p.role === 'OWNER' || p.role === 'EDITOR')
    .map(p => ({ label: p.name, value: p.id }))
)

const canSubmit = computed(
  () => props.taskId != null && form.projectId != null && !submitting.value
)

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
  if (props.taskId == null || form.projectId == null) return
  submitting.value = true
  try {
    const res = await assetBridgeApi.importFromMedia({
      taskId: props.taskId,
      mediaKind: 'VIDEO',
      projectId: form.projectId,
      name: form.name.trim() || undefined,
      description: form.description.trim() || undefined
    })
    const vo: MediaImportVO = res.data.data
    message.success(vo.message || '已入库')
    emit('imported', {
      assetId: vo.assetId ?? 0,
      name: vo.name || form.name.trim() || '视频资产'
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
