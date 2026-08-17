<template>
  <n-modal
    :show="show"
    preset="card"
    :title="`从资产库选择 · ${kindLabel}`"
    style="max-width: 640px"
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
        {{ source === 'public' ? '公共池项目按发布者开放范围可用，仅展示可用项目' : `可选 ${selectedIds.length}/${max}` }}
      </span>
    </div>

    <div class="picker__bar">
      <n-select
        v-model:value="projectId"
        :options="projectOptions"
        :placeholder="source === 'public' ? '选择可用公共项目' : '选择项目'"
        :loading="source === 'public' ? loadingPublicProjects : loadingProjects"
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
      <span class="picker__hint">仅列出{{ kindLabel }}类资产 · 可选 {{ selectedIds.length }}/{{ max }}</span>
    </div>

    <n-spin :show="loadingAssets">
      <div v-if="!assets.length && !loadingAssets" class="picker__empty">
        {{ projectId == null
          ? (source === 'public' ? '请先选择可用的公共项目' : '请先选择项目')
          : '该项目下无此类资产' }}
      </div>
      <n-checkbox-group v-else v-model:value="selectedIds" class="picker__list">
        <div
          v-for="a in assets"
          :key="a.id"
          class="picker__row"
          :class="{
            'picker__row--archived': a.status === 'ARCHIVED',
            'picker__row--excluded': excludedSet.has(a.id)
          }"
        >
          <n-checkbox
            :value="a.id"
            :disabled="isRowDisabled(a.id)"
            :focusable="false"
            class="picker__check"
          >
            <div class="picker__row-content">
              <AssetPickerMediaPreview :file-id="a.fileId" :media-type="mediaType" :name="a.name" />
              <div class="picker__row-main">
                <div class="picker__row-name">{{ a.name }}</div>
                <div class="picker__row-meta">
                  v{{ a.currentVersion }} · {{ statusLabel(a.status) }}
                  <span v-if="excludedSet.has(a.id)"> · 已添加</span>
                </div>
              </div>
            </div>
          </n-checkbox>
        </div>
      </n-checkbox-group>
    </n-spin>

    <template #footer>
      <div class="picker__footer">
        <span class="picker__footer-count">已选 {{ selectedIds.length }} 项（上限 {{ max }}）</span>
        <n-space>
          <n-button @click="emit('update:show', false)">取消</n-button>
          <n-button
            type="primary"
            :disabled="!selectedIds.length"
            :loading="resolving"
            @click="onConfirm"
          >
            确定
          </n-button>
        </n-space>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NButtonGroup, NCheckbox, NCheckboxGroup, NInput, NModal, NSelect, NSpace, NSpin, useMessage } from 'naive-ui'
import { assetApi, assetBridgeApi, projectApi, publicPoolApi } from '@/api/assets'
import type {
  AssetFilePicked, AssetMediaType, AssetProjectVO, AssetStatus, AssetVO, PublicProjectSummaryVO
} from '@/types/asset'
import AssetPickerMediaPreview from './AssetPickerMediaPreview.vue'

/** 中文 mediaType → 显示标签（与 MEDIA_TYPE 取值对齐）。 */
const KIND_LABEL: Record<string, string> = { 图片: '图片', 视频: '视频', 音频: '音频' }

const props = withDefaults(defineProps<{
  show: boolean
  /** 资产类型中文 key（复用 MEDIA_TYPE.IMAGE/VIDEO/AUDIO：图片/视频/音频）。 */
  mediaType: AssetMediaType
  /** 剩余可选数（= 模型能力上限 - 当前已选）。 */
  max: number
  /** 已添加的资产 id（去重，列表行置灰禁选）。 */
  excludeAssetIds?: number[]
}>(), { excludeAssetIds: () => [] })

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'picked', payload: AssetFilePicked[]): void
}>()

const message = useMessage()

const kindLabel = computed(() => KIND_LABEL[props.mediaType] ?? '资产')

const STATUS_LABEL: Record<AssetStatus, string> = { DRAFT: '草稿', LOCKED: '已定稿', ARCHIVED: '已归档' }
function statusLabel(s: AssetStatus) { return STATUS_LABEL[s] ?? s }

const projects = ref<AssetProjectVO[]>([])
/** 2x#4：公共池摘要（每次打开刷新——usable 审批状态会变）。 */
const publicProjects = ref<PublicProjectSummaryVO[]>([])
const source = ref<'local' | 'public'>('local')
const projectId = ref<number | null>(null)
const keyword = ref('')
const assets = ref<AssetVO[]>([])
const loadingProjects = ref(false)
const loadingPublicProjects = ref(false)
const loadingAssets = ref(false)
/** 选中资产 id（n-checkbox-group 多选）。 */
const selectedIds = ref<number[]>([])
/** 确认中（逐个 get 取 fileId，按钮 loading）。 */
const resolving = ref(false)

/** 公共池项目可用性：解析 mediaTypes jsonb 判断项目含当前类型（解析失败视为不过滤）。 */
function publicProjectMatchesType(p: PublicProjectSummaryVO): boolean {
  if (!p.mediaTypes) return true
  try {
    const arr = JSON.parse(p.mediaTypes) as unknown
    if (!Array.isArray(arr) || arr.length === 0) return true
    return arr.includes(props.mediaType)
  } catch {
    return true
  }
}

const projectOptions = computed(() => {
  if (source.value === 'public') {
    // 仅展示 usable 项目（需审批未获批不出现，与资产库页公共池口径一致，spec §3.1）
    return publicProjects.value
      .filter(p => p.usable && publicProjectMatchesType(p))
      .map(p => ({
        label: `${p.name} · ${p.publishedByAdmin ? '官方发布' : p.publisherUsername ?? '发布者'} · 资产 ${p.assetCount}`,
        value: p.id
      }))
  }
  return projects.value.map(p => ({ label: p.name, value: p.id }))
})
const excludedSet = computed(() => new Set(props.excludeAssetIds))

/** 行禁用：已添加（去重）或 超过剩余槽位（非已选时）。 */
function isRowDisabled(assetId: number): boolean {
  if (excludedSet.value.has(assetId)) return true
  if (!selectedIds.value.includes(assetId) && selectedIds.value.length >= props.max) return true
  return false
}

/** 弹窗打开：拉两种来源项目 + 重置选择；immediate 覆盖首挂 show=true。 */
watch(
  () => props.show,
  async (open) => {
    if (!open) return
    source.value = 'local'
    projectId.value = null
    keyword.value = ''
    assets.value = []
    selectedIds.value = []
    if (!projects.value.length) {
      loadingProjects.value = true
      try {
        const { data } = await projectApi.list()
        projects.value = data.data ?? []
      } catch {
        message.error('项目列表加载失败')
      } finally {
        loadingProjects.value = false
      }
    }
    // 公共池每次打开刷新（usable 审批状态会变）
    loadingPublicProjects.value = true
    try {
      const { data } = await publicPoolApi.list()
      publicProjects.value = data.data ?? []
    } catch {
      message.error('公共池项目列表加载失败')
    } finally {
      loadingPublicProjects.value = false
    }
  },
  { immediate: true }
)

/** 切换来源：清选择/关键词/资产列表（L1 反向：切回本地列表正确切换）。 */
function switchSource(next: 'local' | 'public') {
  if (source.value === next) return
  source.value = next
  projectId.value = null
  keyword.value = ''
  assets.value = []
  selectedIds.value = []
}

function onProjectChange() {
  keyword.value = ''
  loadAssets()
}

let kwTimer: ReturnType<typeof setTimeout> | null = null
function onKeywordChange() {
  if (kwTimer) clearTimeout(kwTimer)
  kwTimer = setTimeout(() => loadAssets(), 300)
}

/** 拉资产列表：按 mediaType 过滤 + 关键词（默认隐藏归档）。 */
async function loadAssets() {
  if (projectId.value == null) return
  loadingAssets.value = true
  try {
    const { data } = await assetApi.list(projectId.value, {
      type: props.mediaType,
      q: keyword.value.trim() || undefined,
      page: 1,
      size: 100
    })
    assets.value = data.data?.records ?? []
  } catch {
    message.error('资产列表加载失败')
  } finally {
    loadingAssets.value = false
  }
}

/** 确认：逐个 resolve 取 fileId+url+name（list 不返 fileId）→ emit picked → 关闭。
 *  resolve 空 canvasId=仅解析不记账（设计 §7.2 viewer 可读）。 */
async function onConfirm() {
  if (!selectedIds.value.length) return
  resolving.value = true
  try {
    const results = await Promise.all(
      selectedIds.value.map(async (id) => {
        const { data } = await assetBridgeApi.resolve(id)
        const d = data.data
        return {
          fileId: d.fileId,
          name: d.name ?? '未命名',
          url: d.url,
          assetId: id
        } as { fileId?: string; name: string; url?: string; assetId: number }
      })
    )
    const valid = results.filter((r): r is AssetFilePicked => !!r.fileId)
    if (valid.length !== results.length) {
      message.warning(`${results.length - valid.length} 个资产无文件，已忽略`)
    }
    emit('picked', valid)
    emit('update:show', false)
  } catch {
    message.error('资产解析失败')
  } finally {
    resolving.value = false
  }
}
</script>

<style lang="scss" scoped>
.picker__source {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-2);
  margin-bottom: var(--spacing-3);
}

.picker__source-status {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

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
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-base);
  transition: border-color var(--duration-instant) var(--ease-in-out);

  &:hover {
    border-color: var(--color-primary);
  }

  &--archived,
  &--excluded {
    opacity: 0.55;
  }
}

// checkbox label 内嵌主信息（覆盖 n-checkbox 默认单行截断）
.picker__check {
  width: 100%;
  padding: var(--spacing-2) var(--spacing-3);
  align-items: flex-start;

  :deep(.n-checkbox__label) {
    flex: 1;
    min-width: 0;
    margin-left: var(--spacing-2);
  }
}

.picker__row-main {
  flex: 1;
  min-width: 0;
}

.picker__row-content {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
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

.picker__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-3);
}

.picker__footer-count {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
