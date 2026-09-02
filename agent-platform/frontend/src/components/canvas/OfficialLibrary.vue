<template>
  <!--
    修复XI B2（2x 未解决②，spec XI-2）：导演台官方库大卡片——双栏：
    左官方项目列表（official=true 服务端过滤）、右按项目媒体类型词汇分组的资产行。
    行复用 AssetPickerRow（四态缩略/交互三分离/Lightbox z3000 全继承）；
    选择链与 AssetPicker 相反序：先选资产 emit picked{asset}，父组件（CanvasView B3）
    再建节点→resolve→写节点——失败删节点且本卡片保持打开（B3 细化4 口径），
    故本组件选后不自闭，成功与否由父组件经 update:show 控制。
  -->
  <n-modal
    :show="show"
    preset="card"
    title="官方库 · 浏览并插入画布"
    style="max-width: 980px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="olib">
      <aside class="olib__projects" aria-label="官方项目列表">
        <n-spin :show="loadingProjects">
          <div v-if="projectError" class="olib__msg olib__msg--error" role="alert">
            {{ projectError }}
            <n-button size="tiny" @click="loadProjects()">重试</n-button>
          </div>
          <div v-else-if="!projects.length && !loadingProjects" class="olib__msg">暂无官方发布项目</div>
          <template v-else>
            <button
              v-for="p in projects"
              :key="p.id"
              type="button"
              class="olib__project"
              :class="{ 'olib__project--active': p.id === projectId }"
              :aria-pressed="p.id === projectId"
              @click="selectProject(p)"
            >
              <span class="olib__project-badge" aria-hidden="true">官方</span>
              <span class="olib__project-main">
                <span class="olib__project-name">{{ p.name }}</span>
                <span class="olib__project-meta">
                  {{ p.assetCount }} 项资产 · {{ p.publisherUsername ?? '官方' }} · {{ formatDate(p.publishedAt) }}
                </span>
                <span v-if="p.description" class="olib__project-desc">{{ p.description }}</span>
              </span>
            </button>
          </template>
        </n-spin>
      </aside>

      <section class="olib__assets" aria-label="项目资产（按媒体类型分组）">
        <n-spin :show="loadingAssets">
          <div v-if="projectId == null" class="olib__msg">请从左侧选择官方项目</div>
          <div v-else-if="assetError" class="olib__msg olib__msg--error" role="alert">
            {{ assetError }}
            <n-button size="tiny" @click="loadAssets()">重试</n-button>
          </div>
          <div v-else-if="!groups.length" class="olib__msg">该项目下无资产</div>
          <div v-else class="olib__grouplist">
            <div v-for="g in groups" :key="g.key" class="olib__group">
              <div class="olib__group-head">
                {{ g.key }}<span class="olib__group-count">{{ g.assets.length }}</span>
              </div>
              <AssetPickerRow
                v-for="a in g.assets"
                :key="a.id"
                :asset="a"
                :picking="pickingId === a.id"
                @pick="(a2) => emit('picked', { asset: a2 })"
              />
            </div>
          </div>
        </n-spin>
      </section>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NModal, NSpin, useMessage } from 'naive-ui'
import { publicPoolApi, assetApi } from '@/api/assets'
import AssetPickerRow from './AssetPickerRow.vue'
import type { PageResult } from '@/api/admin'
import type { AxiosResponse } from 'axios'
import type { AssetVO, PublicProjectSummaryVO } from '@/types/asset'

const props = defineProps<{
  show: boolean
  /** 正在 resolve 的资产 id（父组件 B3 链回填行按钮 loading；本组件不发起 resolve）。 */
  pickingId?: number | null
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  /** 选定官方资产（resolve 由父组件在建节点后发起——与 AssetPicker 先有节点后 resolve 相反序）。 */
  (e: 'picked', payload: { asset: AssetVO }): void
}>()

const message = useMessage()

const projects = ref<PublicProjectSummaryVO[]>([])
const projectId = ref<number | null>(null)
const assets = ref<AssetVO[]>([])
const loadingProjects = ref(false)
const loadingAssets = ref(false)
const projectError = ref('')
const assetError = ref('')

/** 打开会话隔离（同 AssetPicker sessionId 范式）：关闭期间旧响应不得回写新会话。 */
let sessionId = 0
let assetRequestId = 0

async function loadProjects() {
  const session = sessionId
  loadingProjects.value = true
  projectError.value = ''
  try {
    const res = await publicPoolApi.list({ official: true })
    if (session !== sessionId || !props.show) return
    projects.value = (res.data.data ?? []).filter((p) => p.usable)
  } catch {
    if (session !== sessionId || !props.show) return
    projectError.value = '官方项目列表加载失败'
    message.error(projectError.value)
  } finally {
    if (session === sessionId && props.show) loadingProjects.value = false
  }
}

watch(
  () => props.show,
  (open) => {
    sessionId += 1
    assetRequestId += 1
    if (!open) return
    projects.value = []
    projectId.value = null
    assets.value = []
    assetError.value = ''
    void loadProjects()
  },
  { immediate: true }
)

function selectProject(p: PublicProjectSummaryVO) {
  if (projectId.value === p.id) return
  projectId.value = p.id
  void loadAssets()
}

/** 全量拉取（不按类型过滤——官方库即浏览全部，分组仅展示层）。
 * P4 交叉 review 低②：读 total 超 100 续页拉全（每页 100 至多再拉 50 页封顶防失控），
 * 原单页 size:100 对超百资产项目静默截断。 */
async function loadAssets() {
  if (projectId.value == null) return
  const request = ++assetRequestId
  const session = sessionId
  const requestProjectId = projectId.value
  loadingAssets.value = true
  assetError.value = ''
  assets.value = []
  try {
    const res = await assetApi.list(requestProjectId, { page: 1, size: 100 })
    if (request !== assetRequestId || session !== sessionId || !props.show
      || requestProjectId !== projectId.value) return
    const page = (res as AxiosResponse<{ code: number; data: PageResult<AssetVO> }>).data.data
    let records = page?.records ?? []
    const total = page?.total ?? records.length
    const pages = Math.min(Math.ceil(total / 100), 51) // 含首页，封顶 5100 条
    for (let p = 2; p <= pages; p++) {
      const more = await assetApi.list(requestProjectId, { page: p, size: 100 })
      if (request !== assetRequestId || session !== sessionId || !props.show
        || requestProjectId !== projectId.value) return
      const mp = (more as AxiosResponse<{ code: number; data: PageResult<AssetVO> }>).data.data
      records = records.concat(mp?.records ?? [])
    }
    assets.value = records
  } catch {
    if (request !== assetRequestId || session !== sessionId || !props.show
      || requestProjectId !== projectId.value) return
    assets.value = []
    assetError.value = '资产列表加载失败'
    message.error(assetError.value)
  } finally {
    if (request === assetRequestId && session === sessionId && props.show) loadingAssets.value = false
  }
}

/** 项目媒体类型词汇（mediaTypes jsonb 字符串 [{key,category}]）；容错（plan 细化2）：解析失败/空 → 单组「全部」。 */
const vocabKeys = computed<string[]>(() => {
  const p = projects.value.find((x) => x.id === projectId.value)
  if (!p?.mediaTypes) return []
  try {
    const parsed = JSON.parse(p.mediaTypes) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed
      .map((it) => (typeof it === 'string' ? it
        : it && typeof it === 'object' && typeof (it as { key?: unknown }).key === 'string'
          ? (it as { key: string }).key
          : null))
      .filter((k): k is string => !!k)
  } catch {
    return []
  }
})

/** 分组（spec XI-2③）：词汇序分组 + 词汇外 mediaType 归尾组「其他」；空组隐藏；无词汇=单组「全部资产」。 */
const groups = computed(() => {
  if (!assets.value.length) return []
  if (!vocabKeys.value.length) {
    return [{ key: '全部资产', assets: assets.value }]
  }
  const result = vocabKeys.value
    .map((key) => ({ key, assets: assets.value.filter((a) => a.mediaType === key) }))
    .filter((g) => g.assets.length)
  const known = new Set(vocabKeys.value)
  const rest = assets.value.filter((a) => !known.has(a.mediaType))
  if (rest.length) result.push({ key: '其他', assets: rest })
  return result
})

function formatDate(iso: string): string {
  return iso ? iso.slice(0, 10) : ''
}

defineExpose({ projects, projectId, assets, groups, loadProjects, loadAssets, selectProject })
</script>

<style lang="scss" scoped>
.olib {
  display: flex;
  gap: var(--spacing-3);
  min-height: 420px;
}

.olib__projects {
  flex: 0 0 280px;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
  max-height: 560px;
  overflow-y: auto;
  padding-right: var(--spacing-1);
}

.olib__project {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-2);
  padding: var(--spacing-2) var(--spacing-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-base);
  background: var(--color-surface);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--duration-instant) var(--ease-in-out);

  &:hover {
    border-color: var(--color-primary);
  }

  &--active {
    border-color: var(--color-primary);
    background: color-mix(in srgb, var(--color-primary) 10%, transparent);
  }
}

.olib__project-badge {
  flex: 0 0 auto;
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  color: var(--color-bg);
  font-size: 11px;
  line-height: 1.5;
}

.olib__project-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.olib__project-name {
  color: var(--color-text-primary);
  font-size: var(--font-size-sm);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.olib__project-meta {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-xs);
}

.olib__project-desc {
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.olib__assets {
  flex: 1;
  min-width: 0;
  max-height: 560px;
  overflow-y: auto;
}

.olib__grouplist {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}

.olib__group {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-1);
}

.olib__group-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  font-weight: 600;
}

.olib__group-count {
  padding: 0 6px;
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--color-primary) 14%, transparent);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
  font-weight: 400;
}

.olib__msg {
  padding: var(--spacing-4);
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);

  &--error {
    color: var(--color-error);
  }
}
</style>
