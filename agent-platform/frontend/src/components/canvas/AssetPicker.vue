<template>
  <n-modal
    :show="show"
    preset="card"
    :title="`从资产库选择 · ${kindLabel}`"
    style="max-width: 640px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="picker__bar">
      <n-select
        v-model:value="projectId"
        :options="projectOptions"
        placeholder="选择项目"
        :loading="loadingProjects"
        style="width: 240px"
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
      <div v-if="!assets.length && !loadingAssets" class="picker__empty">
        {{ projectId == null ? '请先选择项目' : '该项目下无此类资产' }}
      </div>
      <div v-else class="picker__list">
        <div
          v-for="a in assets"
          :key="a.id"
          class="picker__row"
          :class="{ 'picker__row--archived': a.status === 'ARCHIVED' }"
          @click="onPick(a)"
        >
          <div class="picker__row-main">
            <div class="picker__row-name">{{ a.name }}</div>
            <div class="picker__row-meta">
              v{{ a.currentVersion }} · {{ statusLabel(a.status) }}
              <span v-if="a.roleKeys?.length"> · {{ a.roleKeys.join('/') }}</span>
            </div>
          </div>
          <n-button size="small" type="primary" tertiary :loading="pickingId === a.id">选择</n-button>
        </div>
      </div>
    </n-spin>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NInput, NModal, NSelect, NSpin, useMessage } from 'naive-ui'
import { projectApi, assetApi, assetBridgeApi } from '@/api/assets'
import type { PageResult } from '@/api/admin'
import type { AxiosResponse } from 'axios'
import type {
  AssetMediaType, AssetProjectVO, AssetStatus, AssetVO, ResolveVO
} from '@/types/asset'
import type { CanvasNode } from '@/types/canvas'

const props = defineProps<{
  show: boolean
  /** 目标节点（决定可挑的资产类型）。 */
  node: CanvasNode | null
  /** 当前画布 id（resolve 时落 REFERENCE 绑定用，L6 双向追溯）。 */
  canvasId?: number
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  /** 选定资产解析完成：父据 resolve 写 node.data（fileId/outputText/synopsis + 徽标）。 */
  (e: 'picked', payload: { node: CanvasNode; resolve: ResolveVO }): void
}>()

const message = useMessage()

/** 节点类型 → 资产内容类型（与后端 mapMediaType 对齐，决定列表过滤）。 */
const NODE_TO_MEDIA: Record<string, AssetMediaType> = {
  text: 'PROMPT',
  script: 'SCRIPT',
  image: 'IMAGE',
  video: 'VIDEO',
  audio: 'AUDIO'
}
const KIND_LABEL: Record<string, string> = {
  text: '提示词', script: '剧本', image: '图片', video: '视频', audio: '音频'
}
const mediaType = computed<AssetMediaType | undefined>(() =>
  props.node?.type ? NODE_TO_MEDIA[props.node.type] : undefined
)
const kindLabel = computed(() => (props.node?.type ? KIND_LABEL[props.node.type] ?? '资产' : '资产'))

const STATUS_LABEL: Record<AssetStatus, string> = { DRAFT: '草稿', LOCKED: '已定稿', ARCHIVED: '已归档' }
function statusLabel(s: AssetStatus) { return STATUS_LABEL[s] ?? s }

const projects = ref<AssetProjectVO[]>([])
const projectId = ref<number | null>(null)
const keyword = ref('')
const assets = ref<AssetVO[]>([])
const loadingProjects = ref(false)
const loadingAssets = ref(false)
/** 正在 resolve 的资产 id（按钮 loading + 防重入）。 */
const pickingId = ref<number | null>(null)

/** 项目下拉：viewer 也可读引用（设计 §7.2），故全列。 */
const projectOptions = computed(() =>
  projects.value.map(p => ({ label: p.name, value: p.id }))
)

/** 弹窗打开：拉项目列表（资产列表待选项目后拉）。immediate 覆盖首挂 show=true。 */
watch(
  () => props.show,
  async (open) => {
    if (!open) return
    projectId.value = null
    keyword.value = ''
    assets.value = []
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
  keyword.value = ''
  loadAssets()
}

let kwTimer: ReturnType<typeof setTimeout> | null = null
function onKeywordChange() {
  if (kwTimer) clearTimeout(kwTimer)
  kwTimer = setTimeout(() => loadAssets(), 300)
}

/** 拉资产列表：按节点对应 mediaType 过滤 + 关键词（默认隐藏归档，L3）。 */
async function loadAssets() {
  if (projectId.value == null || !mediaType.value) return
  loadingAssets.value = true
  try {
    const res = await assetApi.list(projectId.value, {
      type: mediaType.value,
      q: keyword.value.trim() || undefined,
      page: 1,
      size: 100
    })
    const page = (res as AxiosResponse<{ code: number; data: PageResult<AssetVO> }>).data.data
    assets.value = page?.records ?? []
  } catch {
    message.error('资产列表加载失败')
  } finally {
    loadingAssets.value = false
  }
}

/** 选定资产 → resolve 当前版本快照 → 抛给父写回节点。 */
async function onPick(a: AssetVO) {
  if (pickingId.value !== null) return
  if (!props.node) return
  pickingId.value = a.id
  try {
    const res = await assetBridgeApi.resolve(a.id, {
      canvasId: props.canvasId,
      nodeId: props.node?.id
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

defineExpose({ mediaType, projectId, assets, loadAssets, onPick })
</script>

<style lang="scss" scoped>
.picker__bar {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  margin-bottom: var(--spacing-3);
  flex-wrap: wrap;
}

.picker__hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.picker__empty {
  padding: var(--spacing-4);
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}

.picker__list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-1);
  max-height: 420px;
  overflow-y: auto;
}

.picker__row {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: var(--spacing-2) var(--spacing-3);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-base);
  cursor: pointer;
  transition: border-color var(--duration-instant) var(--ease-in-out);

  &:hover {
    border-color: var(--color-primary);
    background: var(--color-primary-light);
  }

  &--archived {
    opacity: 0.55;
  }
}

.picker__row-main {
  flex: 1;
  min-width: 0;
}

.picker__row-name {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.picker__row-meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin-top: 2px;
}
</style>
