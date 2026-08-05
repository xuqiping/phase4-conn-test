<template>
  <n-modal
    :show="show"
    preset="card"
    :title="`存入资产库 · ${kindLabel}`"
    style="max-width: 520px"
    @update:show="onUpdateShow"
  >
    <div v-if="!hasOutput" class="save-asset__warn">
      该节点暂无可入库的产出（请先生成/上传/填写内容）。
    </div>
    <n-form v-else label-placement="top" :show-require-mark="false">
      <n-form-item label="目标项目">
        <n-select
          v-model:value="form.projectId"
          :options="projectOptions"
          placeholder="选择可写项目"
          :loading="loadingProjects"
          @update:value="onProjectChange"
        />
      </n-form-item>
      <n-form-item label="名称">
        <n-input v-model:value="form.name" placeholder="资产名称" :maxlength="100" show-count />
      </n-form-item>
      <n-form-item v-if="roleOptions.length" label="叙事角色">
        <n-select
          v-model:value="form.roleKeys"
          multiple
          :options="roleOptions"
          placeholder="可多选，留空=通用"
        />
      </n-form-item>
      <n-form-item label="描述">
        <n-input v-model:value="form.description" type="textarea" :rows="2" :maxlength="500" />
      </n-form-item>
    </n-form>

    <!-- 重复入库三态：首次检测到重复 → 提示选择「存为新版本 / 另存为新资产」 -->
    <div v-if="duplicate" class="save-asset__dup">
      该节点产出已入库（资产 #{{ duplicate.assetId }}
      <template v-if="duplicate.version"> v{{ duplicate.version }}</template>
      ）。请选择：
    </div>

    <template #action>
      <n-button @click="close">取消</n-button>
      <template v-if="duplicate">
        <n-button :loading="submitting" tertiary @click="submit('NEW_ASSET')">另存为新资产</n-button>
        <n-button type="primary" :loading="submitting" @click="submit('NEW_VERSION')">存为新版本</n-button>
      </template>
      <n-button
        v-else
        type="primary"
        :loading="submitting"
        :disabled="!canSubmit"
        @click="submit(undefined)"
      >
        入库
      </n-button>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { NButton, NForm, NFormItem, NInput, NModal, NSelect, useMessage } from 'naive-ui'
import { projectApi, assetBridgeApi } from '@/api/assets'
import type { AssetProjectVO, CanvasImportMode, CanvasImportVO } from '@/types/asset'
import type { CanvasNode } from '@/types/canvas'

const props = defineProps<{
  show: boolean
  /** 待入库节点（null=弹窗未有效打开）。 */
  node: CanvasNode | null
  /** 当前画布 id（入库请求 canvasId）。 */
  canvasId: number | null
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  /** 入库成功：父据 badge 写 node.data.assetId/Name/Version + 落 PRODUCED 绑定。 */
  (e: 'imported', payload: { node: CanvasNode; assetId: number; name: string; version: number }): void
}>()

const message = useMessage()

/** 节点类型 → 资产内容类型中文名（与后端 mapMediaType 对齐）。 */
const KIND_LABEL: Record<string, string> = {
  text: '提示词',
  script: '剧本',
  image: '图片',
  video: '视频',
  audio: '音频'
}
const kindLabel = computed(() => (props.node?.type ? KIND_LABEL[props.node.type] ?? '产出' : '产出'))

interface FormState {
  projectId: number | null
  name: string
  description: string
  roleKeys: string[]
}
const form = reactive<FormState>({ projectId: null, name: '', description: '', roleKeys: [] })
const projects = ref<AssetProjectVO[]>([])
const loadingProjects = ref(false)
const submitting = ref(false)
/** 重复入库检测结果（null=未检测到 / 首次提交前清空）。 */
const duplicate = ref<{ assetId: number; version?: number } | null>(null)

/** 仅列出当前用户可写项目（owner/editor；viewer 不可入库，设计 §7.2 + plan 安全清单）。 */
const projectOptions = computed(() =>
  projects.value
    .filter(p => p.role === 'OWNER' || p.role === 'EDITOR')
    .map(p => ({ label: p.name, value: p.id }))
)

const selectedProject = computed(() => projects.value.find(p => p.id === form.projectId))
const roleOptions = computed(() =>
  (selectedProject.value?.narrativeRoles ?? []).map(r => ({ label: r, value: r }))
)

/**
 * 节点是否有可入库产出（前端预检；后端兜底同样拦截）。
 * text: outputText 或 prompt；script: synopsis；image/video/audio: fileId。
 */
const hasOutput = computed(() => {
  const n = props.node
  if (!n) return false
  const d = n.data as Record<string, unknown>
  switch (n.type) {
    case 'text': return Boolean(d.outputText || d.prompt)
    case 'script': return Boolean(d.synopsis)
    case 'image':
    case 'video':
    case 'audio': return Boolean(d.fileId)
    default: return false
  }
})

const canSubmit = computed(() => hasOutput.value && form.projectId != null && !submitting.value)

/** 弹窗打开：重置表单 + 拉项目列表 + 名称默认取节点 label。immediate 覆盖首挂 show=true。 */
watch(
  () => props.show,
  async (open) => {
    if (!open) return
    duplicate.value = null
    form.projectId = null
    form.description = ''
    form.roleKeys = []
    form.name = ((props.node?.data.label as string | undefined) ?? '') || ''
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

function onProjectChange() {
  // 切项目时清空已选角色（受控词汇按项目变）
  form.roleKeys = []
}

function onUpdateShow(v: boolean) {
  emit('update:show', v)
}

function close() {
  emit('update:show', false)
}

defineExpose({ hasOutput, projectOptions, roleOptions, form, duplicate, submit, onProjectChange })

/** 提交入库。mode: undefined=首次（自动检测重复）/ NEW_VERSION / NEW_ASSET。 */
async function submit(mode: CanvasImportMode | undefined) {
  if (!props.node || props.canvasId == null || form.projectId == null) return
  submitting.value = true
  try {
    const res = await assetBridgeApi.importFromCanvas({
      canvasId: props.canvasId,
      nodeId: props.node.id,
      projectId: form.projectId,
      name: form.name.trim() || undefined,
      description: form.description.trim() || undefined,
      roleKeys: form.roleKeys.length ? form.roleKeys : undefined,
      mode
    })
    const vo: CanvasImportVO = res.data.data
    if (vo.created && vo.assetId != null) {
      message.success(vo.message || '已入库')
      emit('imported', {
        node: props.node,
        assetId: vo.assetId,
        name: vo.name || form.name.trim() || '资产',
        version: vo.version ?? 1
      })
      close()
      return
    }
    // created=false：重复检测命中 → 置 duplicate 让用户选模式
    duplicate.value = {
      assetId: vo.duplicateAssetId ?? 0,
      version: vo.duplicateVersion ?? undefined
    }
    message.warning(vo.message || '检测到重复入库')
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '入库失败'
    message.error(msg)
  } finally {
    submitting.value = false
  }
}
</script>

<style lang="scss" scoped>
.save-asset__warn {
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  padding: var(--spacing-3);
  background: rgba(239, 68, 68, 0.08);
  border-radius: var(--radius-base);
}

.save-asset__dup {
  margin-top: var(--spacing-2);
  font-size: var(--font-size-sm);
  color: #facc15;
  padding: var(--spacing-2) var(--spacing-3);
  background: rgba(250, 204, 21, 0.1);
  border-radius: var(--radius-base);
}
</style>
