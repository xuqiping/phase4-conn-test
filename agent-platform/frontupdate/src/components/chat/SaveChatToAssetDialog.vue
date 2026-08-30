<template>
  <n-modal
    :show="show"
    preset="card"
    title="存入资产库 · 对话内容"
    style="max-width: 520px"
    @update:show="onUpdateShow"
  >
    <n-form label-placement="top" :show-require-mark="false">
      <n-form-item label="目标项目">
        <n-select
          v-model:value="form.projectId"
          :options="projectOptions"
          placeholder="选择可写项目（需含文本类媒体类型）"
          :loading="loadingProjects"
        />
      </n-form-item>
      <n-form-item label="媒体类型">
        <n-select v-model:value="form.mediaType" :options="mediaTypeOptions" placeholder="选择文本类类型" />
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
import { projectApi, assetApi } from '@/api/assets'
import { MEDIA_TYPE } from '@/types/asset'
import type { AssetProjectVO } from '@/types/asset'

/**
 * 9x#12：对话结果一键入资产库（聊天→库）。
 * 与生图 SaveImageToAssetDialog 的区别：聊天正文是 DB 文本（无 taskId/fileId），
 * 走文本资产创建 POST /assets/projects/{id}/assets（assetApi.create），
 * content 按类型包 JSON（契约同 AssetProjectView.submitCreate / AssetCanvasBridgeService.extractTextContent：
 * 剧本→{synopsis}，其余文本类→{body}）。
 */
const props = defineProps<{
  show: boolean
  /** 要入库的正文（助手回复 content）。 */
  content: string
  /** 默认资产名（取正文首行截断）。 */
  defaultName?: string
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'imported', payload: { name: string }): void
}>()

const message = useMessage()

interface FormState {
  projectId: number | null
  mediaType: string | null
  name: string
  description: string
}
const form = reactive<FormState>({ projectId: null, mediaType: null, name: '', description: '' })
const projects = ref<AssetProjectVO[]>([])
const loadingProjects = ref(false)
const submitting = ref(false)

/** 项目的 TEXT 类受控词汇（入库只能是文本类）。 */
function textTypesOf(p: AssetProjectVO): string[] {
  return (p.mediaTypes ?? []).filter(t => t.category === 'TEXT').map(t => t.key)
}

/** 仅可写（owner/editor）且词汇表含文本类的项目。 */
const writableProjects = computed(() =>
  projects.value.filter(p => (p.role === 'OWNER' || p.role === 'EDITOR') && textTypesOf(p).length > 0)
)

const projectOptions = computed(() =>
  writableProjects.value.map(p => ({ label: p.name, value: p.id }))
)

const mediaTypeOptions = computed(() => {
  const p = writableProjects.value.find(x => x.id === form.projectId)
  return p ? textTypesOf(p).map(k => ({ label: k, value: k })) : []
})

const canSubmit = computed(
  () => props.content.trim().length > 0 && form.projectId != null && !!form.mediaType && form.name.trim().length > 0 && !submitting.value
)

/** 弹窗打开：重置表单 + 拉项目列表 + 名称默认。immediate 覆盖首挂 show=true。 */
watch(
  () => props.show,
  async (open) => {
    if (!open) return
    form.projectId = null
    form.mediaType = null
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

/** 选定项目后自动带出其首个文本类类型。 */
watch(
  () => form.projectId,
  (pid) => {
    const p = writableProjects.value.find(x => x.id === pid)
    const keys = p ? textTypesOf(p) : []
    form.mediaType = keys.includes(MEDIA_TYPE.PROMPT) ? MEDIA_TYPE.PROMPT : (keys[0] ?? null)
  }
)

function onUpdateShow(v: boolean) {
  emit('update:show', v)
}

function close() {
  emit('update:show', false)
}

async function submit() {
  if (!canSubmit.value || form.projectId == null || !form.mediaType) return
  submitting.value = true
  try {
    const body = props.content.trim()
    const contentJson = form.mediaType === MEDIA_TYPE.SCRIPT
      ? JSON.stringify({ synopsis: body })
      : JSON.stringify({ body })
    await assetApi.create(form.projectId, {
      mediaType: form.mediaType,
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      content: contentJson
    })
    message.success('已入库')
    emit('imported', { name: form.name.trim() })
    close()
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } }; message?: string }
    message.error(err?.response?.data?.message ?? err?.message ?? '入库失败')
  } finally {
    submitting.value = false
  }
}
</script>
