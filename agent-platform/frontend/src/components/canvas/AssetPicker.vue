<template>
  <n-modal
    :show="show"
    preset="card"
    :title="`从资产库选择 · ${kindLabel}`"
    style="max-width: 680px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="picker__source" aria-label="资产来源">
      <n-button-group>
        <n-button
          size="small"
          :type="source === 'local' ? 'primary' : 'default'"
          :secondary="source !== 'local'"
          :aria-pressed="source === 'local'"
          @click="switchSource('local')"
        >
          我的/共享项目
        </n-button>
        <n-button
          size="small"
          :type="source === 'public' ? 'primary' : 'default'"
          :secondary="source !== 'public'"
          :aria-pressed="source === 'public'"
          @click="switchSource('public')"
        >
          公共池
        </n-button>
      </n-button-group>
      <span class="picker__source-status">
        本地 {{ loadingProjects ? '加载中' : `${projects.length} 项` }} ·
        公共 {{ loadingPublicProjects ? '加载中' : `${publicProjects.length} 项` }}
      </span>
    </div>

    <div v-if="activeProjectError" class="picker__error" role="alert">
      {{ activeProjectError }}
    </div>

    <div class="picker__bar">
      <n-select
        :value="projectId"
        :options="projectOptions"
        :placeholder="source === 'public' ? '选择可用公共项目' : '选择项目'"
        :loading="activeProjectsLoading"
        style="width: 320px"
        @update:value="onProjectChange"
      />
      <n-input
        v-model:value="keyword"
        placeholder="搜索资产名"
        :maxlength="50"
        clearable
        style="width: 200px"
        @update:value="onKeywordChange"
      />
      <span class="picker__hint">仅列出{{ kindLabel }}类资产</span>
    </div>

    <n-spin :show="loadingAssets">
      <div v-if="!assets.length && !loadingAssets" class="picker__empty" :class="{ 'picker__empty--error': assetError }">
        {{ emptyText }}
      </div>
      <div v-else class="picker__list">
        <!-- 修复X B2：行改版交 AssetPickerRow（四态缩略+交互三分离）；选定链 onPick 仍在本组件 -->
        <AssetPickerRow
          v-for="a in assets"
          :key="a.id"
          :asset="a"
          :picking="pickingId === a.id"
          @pick="onPick"
        />
      </div>
    </n-spin>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NButtonGroup, NInput, NModal, NSelect, NSpin, useMessage } from 'naive-ui'
import { projectApi, publicPoolApi, assetApi, assetBridgeApi } from '@/api/assets'
import AssetPickerRow from './AssetPickerRow.vue'
import type { PageResult } from '@/api/admin'
import type { AxiosResponse } from 'axios'
import type {
  AssetMediaType, AssetProjectVO, AssetVO, PublicProjectSummaryVO, ResolveVO
} from '@/types/asset'
import type { CanvasNode } from '@/types/canvas'

type PickerSource = 'local' | 'public'

const props = defineProps<{
  show: boolean
  /** 目标节点（决定可挑的资产类型）。 */
  node: CanvasNode | null
  /** 当前画布 id（resolve 时落 REFERENCE 绑定用，L6 双向追溯）。 */
  canvasId?: number
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  /** 选定资产解析完成：父组件按 resolve 写 node.data，并锁定 resolve.version 快照。 */
  (e: 'picked', payload: { node: CanvasNode; resolve: ResolveVO }): void
}>()

const message = useMessage()

/** 节点类型 → 资产内容类型（与后端 mapNodeType 对齐）。 */
const NODE_TO_MEDIA: Record<string, AssetMediaType> = {
  text: '提示词',
  script: '剧本',
  storyboard: '分镜',
  image: '图片',
  video: '视频',
  audio: '音频'
}
const KIND_LABEL: Record<string, string> = {
  text: '提示词', script: '剧本', storyboard: '分镜', image: '图片', video: '视频', audio: '音频'
}
const mediaType = computed<AssetMediaType | undefined>(() =>
  props.node?.type ? NODE_TO_MEDIA[props.node.type] : undefined
)
const kindLabel = computed(() => (props.node?.type ? KIND_LABEL[props.node.type] ?? '资产' : '资产'))

// 状态/字标渲染归 AssetPickerRow（修复X B2 行改版）

const source = ref<PickerSource>('local')
const projects = ref<AssetProjectVO[]>([])
const publicProjects = ref<PublicProjectSummaryVO[]>([])
const projectId = ref<number | null>(null)
const keyword = ref('')
const assets = ref<AssetVO[]>([])
const loadingProjects = ref(false)
const loadingPublicProjects = ref(false)
const loadingAssets = ref(false)
const localError = ref('')
const publicError = ref('')
const assetError = ref('')
/** 正在 resolve 的资产 id（按钮 loading + 防重入）。 */
const pickingId = ref<number | null>(null)

function publicAvailabilityLabel(p: PublicProjectSummaryVO) {
  if (p.usable) return p.publicAccessMode === 'OPEN' ? '直接使用' : '需审批 · 已获批'
  if (p.publicAccessMode !== 'APPROVAL_REQUIRED') return '直接使用 · 当前不可用'
  if (p.myRequestStatus === 'PENDING') return '需审批 · 等待审批'
  if (p.myRequestStatus === 'REJECTED') return '需审批 · 被拒绝'
  if (p.myRequestStatus === 'REVOKED') return '需审批 · 已撤销'
  return '需审批 · 尚未获批'
}

/** 本地含 owner/editor/viewer 全部可读项目；公共摘要只用于展示与可用性判断。 */
const projectOptions = computed(() => {
  if (source.value === 'local') {
    return projects.value.map((p) => ({ label: p.name, value: p.id }))
  }
  return publicProjects.value.map((p) => ({
    label: [p.name, p.publishedByAdmin ? '官方发布' : null, publicAvailabilityLabel(p)]
      .filter(Boolean)
      .join(' · '),
    value: p.id,
    disabled: !p.usable
  }))
})

const activeProjectsLoading = computed(() =>
  source.value === 'local' ? loadingProjects.value : loadingPublicProjects.value
)
const activeProjectError = computed(() => source.value === 'local' ? localError.value : publicError.value)
const emptyText = computed(() => {
  if (assetError.value) return assetError.value
  if (projectId.value == null) {
    if (source.value === 'public' && publicProjects.value.length > 0 && !publicProjects.value.some((p) => p.usable)) {
      return '公共项目当前不可用（等待审批、被拒绝或已撤销）'
    }
    return source.value === 'public'
      ? '请先选择可用的公共项目（不可用项目已禁用）'
      : '请先选择项目'
  }
  return `该项目下无${kindLabel.value}资产`
})

let sessionId = 0
let assetRequestId = 0
let kwTimer: ReturnType<typeof setTimeout> | null = null

function resetSelection() {
  projectId.value = null
  keyword.value = ''
  assets.value = []
  assetError.value = ''
  loadingAssets.value = false
  assetRequestId += 1
  if (kwTimer) {
    clearTimeout(kwTimer)
    kwTimer = null
  }
}

async function loadLocalProjects(session: number) {
  loadingProjects.value = true
  localError.value = ''
  try {
    const res = await projectApi.list()
    if (session !== sessionId || !props.show) return
    projects.value = res.data.data ?? []
  } catch {
    if (session !== sessionId || !props.show) return
    localError.value = '本地项目列表加载失败'
    message.error(localError.value)
  } finally {
    if (session === sessionId && props.show) loadingProjects.value = false
  }
}

async function loadPublicProjects(session: number) {
  loadingPublicProjects.value = true
  publicError.value = ''
  try {
    const res = await publicPoolApi.list()
    if (session !== sessionId || !props.show) return
    publicProjects.value = res.data.data ?? []
  } catch {
    if (session !== sessionId || !props.show) return
    publicError.value = '公共池项目列表加载失败'
    message.error(publicError.value)
  } finally {
    if (session === sessionId && props.show) loadingPublicProjects.value = false
  }
}

/** 每次打开均并行刷新两种来源；sessionId 阻止旧弹窗响应回写新会话。 */
watch(
  () => props.show,
  (open) => {
    const session = ++sessionId
    assetRequestId += 1
    if (!open) {
      if (kwTimer) clearTimeout(kwTimer)
      kwTimer = null
      return
    }
    source.value = 'local'
    resetSelection()
    void loadLocalProjects(session)
    void loadPublicProjects(session)
  },
  { immediate: true }
)

function switchSource(next: PickerSource) {
  if (source.value === next) return
  source.value = next
  resetSelection()
}

async function onProjectChange(value: number | null) {
  assetRequestId += 1
  assets.value = []
  assetError.value = ''
  keyword.value = ''

  if (value == null) {
    projectId.value = null
    return
  }
  if (source.value === 'public') {
    const selected = publicProjects.value.find((p) => p.id === value)
    if (!selected?.usable) {
      projectId.value = null
      return
    }
  }
  projectId.value = value
  await loadAssets()
}

function onKeywordChange() {
  if (kwTimer) clearTimeout(kwTimer)
  kwTimer = setTimeout(() => { void loadAssets() }, 300)
}

/** 按节点 mediaType 与关键词加载；requestId/source/session 三重隔离旧响应。 */
async function loadAssets() {
  if (projectId.value == null || !mediaType.value) return
  const request = ++assetRequestId
  const session = sessionId
  const requestSource = source.value
  const requestProjectId = projectId.value
  loadingAssets.value = true
  assetError.value = ''
  assets.value = []
  try {
    const res = await assetApi.list(requestProjectId, {
      type: mediaType.value,
      q: keyword.value.trim() || undefined,
      page: 1,
      size: 100
    })
    if (
      request !== assetRequestId || session !== sessionId || !props.show ||
      requestSource !== source.value || requestProjectId !== projectId.value
    ) return
    const page = (res as AxiosResponse<{ code: number; data: PageResult<AssetVO> }>).data.data
    assets.value = page?.records ?? []
  } catch {
    if (
      request !== assetRequestId || session !== sessionId || !props.show ||
      requestSource !== source.value || requestProjectId !== projectId.value
    ) return
    assets.value = []
    assetError.value = '资产列表加载失败'
    message.error(assetError.value)
  } finally {
    if (request === assetRequestId && session === sessionId && props.show) loadingAssets.value = false
  }
}

/** 解析当前版本快照；不传 version，且原样向父组件透传后端 resolve.version。 */
async function onPick(a: AssetVO) {
  if (pickingId.value !== null) return
  if (!props.node) return
  pickingId.value = a.id
  try {
    const res = await assetBridgeApi.resolve(a.id, {
      canvasId: props.canvasId,
      nodeId: props.node.id
    })
    const resolve: ResolveVO = res.data.data
    emit('picked', { node: props.node, resolve })
    emit('update:show', false)
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '引用解析失败'
    message.error(msg)
  } finally {
    pickingId.value = null
  }
}

defineExpose({
  mediaType, source, projects, publicProjects, projectOptions, projectId, keyword, assets,
  localError, publicError, assetError, switchSource, onProjectChange, loadAssets, onPick
})
</script>

<style lang="scss" scoped>
.picker__source {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-2);
  margin-bottom: var(--spacing-3);
}

.picker__source-status,
.picker__hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.picker__error {
  margin-bottom: var(--spacing-2);
  padding: var(--spacing-2) var(--spacing-3);
  border: 1px solid var(--color-error);
  border-radius: var(--radius-base);
  color: var(--color-error);
  background: color-mix(in srgb, var(--color-error) 8%, transparent);
  font-size: var(--font-size-sm);
}

.picker__bar {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  margin-bottom: var(--spacing-3);
  flex-wrap: wrap;
}

.picker__empty {
  padding: var(--spacing-4);
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);

  &--error {
    color: var(--color-error);
  }
}

.picker__list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-1);
  max-height: 420px;
  overflow-y: auto;
}
/* 行样式归 AssetPickerRow（修复X B2） */
</style>
