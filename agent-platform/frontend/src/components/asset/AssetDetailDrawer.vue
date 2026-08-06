<!--
  项目资产库·资产详情抽屉  plan §S10（10a：预览+元数据+状态机+usages+下载）
  - 预览按 mediaType：PROMPT/SCRIPT 文本；IMAGE/VIDEO/AUDIO fetchCanvasPreview(fileId)→objectURL（卸载/换资产 revoke）
  - 状态机动作（canEdit，设计 §六 / L2 L3）：DRAFT→[定稿][归档]；LOCKED→[解锁][归档]；ARCHIVED→[取消归档]
  - 定稿=引用锁定当前版本快照语义（L2）；归档=默认列表隐藏（L3）—— 状态由后端强制，前端按 status 渲染按钮
  - usages 使用记录（assetBridgeApi.usages，双向追溯，viewer 可读）
  - 下载（文件类）：/files/{fileId} blob → <a download>
  - VersionTimeline + ConsistencyPack 编辑区在 10b 接入
-->
<template>
  <n-drawer :show="show" :width="drawerWidth" placement="right" @update:show="emit('update:show', $event)">
    <n-drawer-content :title="asset?.name || '资产详情'" closable>
      <div v-if="loading" class="asset-detail__loading"><n-spin size="large" /></div>

      <div v-else-if="asset" class="asset-detail">
        <!-- 状态 + 类型 标签 -->
        <n-space class="asset-detail__tags">
          <n-tag size="small" bordered :type="STATUS_TYPE[asset.status]">{{ STATUS_LABEL[asset.status] }}</n-tag>
          <n-tag size="small" bordered>{{ asset.mediaType }}</n-tag>
          <n-tag size="small" bordered type="info">v{{ asset.currentVersion }}</n-tag>
        </n-space>

        <!-- 预览 -->
        <div class="asset-detail__preview">
          <template v-if="effectiveCategory === 'IMAGE'">
            <img v-if="previewUrl" :src="previewUrl" class="asset-detail__media" alt="预览" />
          </template>
          <template v-else-if="effectiveCategory === 'VIDEO'">
            <video v-if="previewUrl" :src="previewUrl" controls class="asset-detail__media" />
          </template>
          <template v-else-if="effectiveCategory === 'AUDIO'">
            <audio v-if="previewUrl" :src="previewUrl" controls />
          </template>
          <!-- C3 剧本：正文编辑 + AI 分场 + 分场列表 -->
          <template v-else-if="asset.mediaType === MEDIA_TYPE.SCRIPT">
            <div class="asset-detail__script">
              <n-input
                v-model:value="synopsis"
                type="textarea"
                :rows="6"
                :maxlength="8000"
                :readonly="!canEdit"
                placeholder="剧本正文（≤8000 字）；EDITOR 可编辑，保存后点「AI 分场」"
              />
              <div v-if="canEdit" class="asset-detail__script-actions">
                <n-button
                  size="small"
                  :loading="savingSynopsis"
                  :disabled="!synopsisDirty"
                  @click="saveSynopsis"
                >
                  保存正文
                </n-button>
                <n-button size="small" type="primary" :loading="breaking" @click="runBreakdown">
                  AI 分场
                </n-button>
              </div>
              <ScriptScenes v-if="scenes.length" :scenes="scenes" />
              <n-empty
                v-else-if="!breaking"
                size="small"
                description="未分场，点「AI 分场」拆分（3-10 场）"
              />
            </div>
          </template>
          <!-- S15 非剧本 TEXT（提示词/自定义 TEXT）：正文编辑器（统一 TEXT 编辑入口） -->
          <template v-else-if="effectiveCategory === 'TEXT'">
            <div class="asset-detail__text-edit">
              <n-input
                v-model:value="textBody"
                type="textarea"
                :rows="6"
                :maxlength="8000"
                :readonly="!canEdit"
                placeholder="资产正文（≤8000 字）；EDITOR 可编辑，点「保存正文」产新版本"
              />
              <div v-if="canEdit" class="asset-detail__script-actions">
                <n-button
                  size="small"
                  :loading="savingTextBody"
                  :disabled="!textBodyDirty"
                  @click="saveTextBody"
                >
                  保存正文
                </n-button>
              </div>
            </div>
          </template>
          <template v-else>
            <pre class="asset-detail__text">{{ textPreview }}</pre>
          </template>
          <n-empty v-if="needsFile && !previewUrl" description="无文件预览" />
        </div>

        <!-- 元数据 -->
        <n-descriptions label-placement="left" :column="1" size="small" bordered class="asset-detail__meta">
          <n-descriptions-item label="描述">{{ asset.description || '—' }}</n-descriptions-item>
          <n-descriptions-item label="叙事角色">
            <n-space v-if="asset.roleKeys?.length" size="small">
              <n-tag v-for="k in asset.roleKeys" :key="k" size="small">{{ k }}</n-tag>
            </n-space>
            <span v-else>—</span>
          </n-descriptions-item>
          <n-descriptions-item label="标签">{{ asset.tags?.length ? asset.tags.join('，') : '—' }}</n-descriptions-item>
          <n-descriptions-item label="创建时间">{{ asset.createdAt }}</n-descriptions-item>
        </n-descriptions>

        <!-- 状态机动作（canEdit，L2/L3） -->
        <div v-if="canEdit" class="asset-detail__actions">
          <n-button v-if="asset.status === 'DRAFT'" size="small" type="primary" @click="doAction('lock')">定稿</n-button>
          <n-button v-if="asset.status === 'LOCKED'" size="small" @click="doAction('unlock')">解锁回退草稿</n-button>
          <n-button v-if="asset.status !== 'ARCHIVED'" size="small" type="warning" @click="doAction('archive')">归档</n-button>
          <n-button v-if="asset.status === 'ARCHIVED'" size="small" @click="doAction('unarchive')">取消归档</n-button>
          <n-button v-if="needsFile" size="small" quaternary @click="download">下载</n-button>
          <!-- S15 删除（软删；画布引用快照不受影响，L11） -->
          <n-popconfirm @positive-click="deleteAsset">
            <template #trigger>
              <n-button size="small" type="error" ghost :loading="deleting">删除</n-button>
            </template>
            确认删除？画布引用快照不受影响。
          </n-popconfirm>
        </div>

        <!-- 使用记录（双向追溯，viewer 可读） -->
        <div class="asset-detail__usages">
          <h4 class="asset-detail__section-title">使用记录（{{ usages.length }}）</h4>
          <n-empty v-if="usages.length === 0" description="暂无使用记录" size="small" />
          <ul v-else class="asset-detail__usage-list">
            <li v-for="u in usages" :key="u.id">
              <n-tag size="tiny" bordered :type="u.bindType === 'PRODUCED' ? 'success' : 'info'">
                {{ u.bindType === 'PRODUCED' ? '产自' : '引用于' }}
              </n-tag>
              画布 #{{ u.canvasId ?? '—' }} · 节点 {{ u.nodeId ?? '—' }}
              <span class="asset-detail__usage-time">{{ u.createdAt }}</span>
            </li>
          </ul>
        </div>

        <!-- 一致性包（人物/道具/场景类额染，设计 §五） -->
        <ConsistencyPack
          v-if="showConsistency"
          :asset-id="asset.id"
          :can-edit="canEdit"
          :initial="consistencyInitial"
          @saved="onConsistencySaved"
        />

        <!-- 版本时间线（只读回滚查看） -->
        <VersionTimeline :asset-id="asset.id" :asset-current-version="asset.currentVersion" />
      </div>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  NButton,
  NDescriptions,
  NDescriptionsItem,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NInput,
  NPopconfirm,
  NSpace,
  NSpin,
  NTag,
  useMessage
} from 'naive-ui'
import { assetApi, assetBridgeApi, versionApi, scriptApi } from '@/api/assets'
import { fetchCanvasPreview } from '@/api/canvas'
import request from '@/api/request'
import ConsistencyPack, { type ConsistencyPack as ConsistencyPackData } from '@/components/asset/ConsistencyPack.vue'
import VersionTimeline from '@/components/asset/VersionTimeline.vue'
import ScriptScenes from '@/components/asset/ScriptScenes.vue'
import type { AssetStatus, AssetUsageVO, AssetVO, SceneVO } from '@/types/asset'
import { MEDIA_TYPE } from '@/types/asset'

/**
 * 读文本类正文：SCRIPT→content.synopsis；其他 TEXT→content.body；兜底首个字符串值/裸文本。
 * 用于抽屉编辑器初始化（S15 统一文本编辑入口）。
 */
function readTextBody(content: string | null | undefined, mediaType: string): string {
  if (!content) return ''
  try {
    const obj = JSON.parse(content) as Record<string, unknown>
    const key = mediaType === MEDIA_TYPE.SCRIPT ? 'synopsis' : 'body'
    if (typeof obj[key] === 'string') return obj[key] as string
    for (const k of Object.keys(obj)) {
      if (typeof obj[k] === 'string') return obj[k] as string
    }
    return ''
  } catch {
    return content
  }
}

/**
 * 写文本类正文回 content 串：按类型写对应键，保留其他键（scenes/template/consistency 不丢）。
 * 非合法 JSON 的旧 content 当作空对象重写。
 */
function writeTextBody(content: string | null | undefined, mediaType: string, body: string): string {
  let obj: Record<string, unknown> = {}
  if (content) {
    try {
      obj = JSON.parse(content) as Record<string, unknown>
    } catch {
      obj = {}
    }
  }
  const key = mediaType === MEDIA_TYPE.SCRIPT ? 'synopsis' : 'body'
  obj[key] = body
  return JSON.stringify(obj)
}

const props = defineProps<{
  show: boolean
  assetId: number | null
  /** 写权限（viewer=false 隐藏状态机动作，设计 §7.2）；S11 按项目角色传 */
  canEdit?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  /** 状态变更 → 父重载（矩阵计数/列表态可能变，L2/L3） */
  (e: 'changed', assetId: number): void
}>()

const message = useMessage()
const asset = ref<AssetVO | null>(null)
const usages = ref<AssetUsageVO[]>([])
const loading = ref(false)
const previewUrl = ref<string | null>(null)

/** C3 剧本：正文草稿 + content 对象 + 分场列表 + 保存/分场态。 */
const synopsis = ref('')
const contentObj = ref<Record<string, unknown>>({})
const scenes = ref<SceneVO[]>([])
const originalSynopsis = ref('')
const savingSynopsis = ref(false)
const breaking = ref(false)
const synopsisDirty = computed(() => synopsis.value !== originalSynopsis.value)

/** S15 非剧本 TEXT（提示词/自定义 TEXT）正文草稿 + 保存态。 */
const textBody = ref('')
const originalTextBody = ref('')
const savingTextBody = ref(false)
const textBodyDirty = computed(() => textBody.value !== originalTextBody.value)
const deleting = ref(false)

const drawerWidth = computed(() => (window.innerWidth < 768 ? '100%' : 520))

const STATUS_LABEL: Record<AssetStatus, string> = { DRAFT: '草稿', LOCKED: '已定稿', ARCHIVED: '已归档' }
const STATUS_TYPE: Record<AssetStatus, 'default' | 'success' | 'warning'> = {
  DRAFT: 'default',
  LOCKED: 'success',
  ARCHIVED: 'warning'
}
const FILE_CATEGORIES = ['IMAGE', 'VIDEO', 'AUDIO']

/** 处理类别（优先 asset.mediaCategory，V60 后端必返；旧数据兜底按默认 key 推断）。 */
const effectiveCategory = computed(() => {
  const c = asset.value?.mediaCategory
  return c && FILE_CATEGORIES.includes(c) ? c : 'TEXT'
})

/** 是否文件类（决定预览拉 objectURL；按 category 而非 type，兼容自定义 key）。 */
const isFileAsset = computed(() => !!asset.value && FILE_CATEGORIES.includes(effectiveCategory.value))

const needsFile = computed(() => props.show && !!asset.value && isFileAsset.value)

/** 一致性包初始值（从 asset.content.consistency 解析；人物/道具/场景类额染） */
const consistencyInitial = computed<ConsistencyPackData | null>(() => {
  if (!asset.value?.content) return null
  try {
    const parsed = JSON.parse(asset.value.content)
    return (parsed?.consistency ?? null) as ConsistencyPackData | null
  } catch {
    return null
  }
})

/** 仅人物/道具/场景类资产渲染一致性包（设计 §五） */
const showConsistency = computed(() => {
  const roles = asset.value?.roleKeys ?? []
  return ['人物', '道具', '场景'].some((r) => roles.includes(r))
})

/** 文本类预览：content 是 JSON 串，直接展示原文（剧本 scenes 深格式化在 10b） */
const textPreview = computed(() => {
  if (!asset.value?.content) return '（无正文）'
  try {
    return JSON.stringify(JSON.parse(asset.value.content), null, 2)
  } catch {
    return asset.value.content
  }
})

watch(
  () => [props.show, props.assetId] as const,
  async ([show, id]) => {
    if (show && id) {
      await loadAll(id)
    } else if (!show) {
      // 关抽屉释放 objectURL
      revokePreview()
      asset.value = null
      usages.value = []
    }
  },
  { immediate: true }
)

async function loadAll(id: number) {
  loading.value = true
  try {
    const [assetRes, usagesRes] = await Promise.all([assetApi.get(id), assetBridgeApi.usages(id)])
    asset.value = assetRes.data.data
    usages.value = usagesRes.data.data || []
    // C3 剧本：解析 content → 正文草稿 + 分场列表
    if (asset.value?.mediaType === MEDIA_TYPE.SCRIPT) {
      parseScriptContent(asset.value.content)
    } else if (asset.value && effectiveCategory.value === 'TEXT') {
      // S15 非剧本 TEXT（提示词/自定义 TEXT）：解析正文草稿
      const body = readTextBody(asset.value.content, asset.value.mediaType)
      textBody.value = body
      originalTextBody.value = body
    }
    // 文件类拉预览 objectURL
    if (asset.value?.fileId && isFileAsset.value) {
      try {
        previewUrl.value = await fetchCanvasPreview(asset.value.fileId)
      } catch {
        // 预览失败不阻塞详情
        previewUrl.value = null
      }
    }
  } catch {
    message.error('加载资产详情失败')
  } finally {
    loading.value = false
  }
}

function revokePreview() {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = null
  }
}

/** C3 解析剧本 content（{synopsis, scenes?}）→ 同步正文草稿 + 分场列表。旧态非 JSON 当 synopsis。 */
function parseScriptContent(raw: string | null | undefined) {
  let obj: Record<string, unknown> = {}
  if (raw) {
    try {
      obj = JSON.parse(raw) as Record<string, unknown>
    } catch {
      obj = { synopsis: raw }
    }
  }
  contentObj.value = obj
  const syn = typeof obj.synopsis === 'string' ? obj.synopsis : ''
  synopsis.value = syn
  originalSynopsis.value = syn
  scenes.value = Array.isArray(obj.scenes) ? (obj.scenes as SceneVO[]) : []
}

/** C3 保存剧本正文 → versionApi.create 写新版本（同步 asset.content，breakdown 即可读最新）。 */
async function saveSynopsis() {
  if (!asset.value) return
  savingSynopsis.value = true
  try {
    const next = { ...contentObj.value, synopsis: synopsis.value.trim() }
    await versionApi.create(asset.value.id, {
      content: JSON.stringify(next),
      changeNote: '编辑剧本正文'
    })
    message.success('正文已保存')
    await loadAll(asset.value.id)
    emit('changed', asset.value.id)
  } catch {
    message.error('保存正文失败')
  } finally {
    savingSynopsis.value = false
  }
}

/** C3 AI 分场 → scriptApi.breakdown（读 asset.content.synopsis，产 scenes 新版本）。 */
async function runBreakdown() {
  if (!asset.value) return
  if (synopsisDirty.value) {
    message.warning('正文有未保存改动，请先点「保存正文」')
    return
  }
  breaking.value = true
  try {
    await scriptApi.breakdown(asset.value.id)
    message.success('分场完成')
    await loadAll(asset.value.id)
    emit('changed', asset.value.id)
  } catch {
    message.error('分场失败（剧本过长 / LLM 异常）')
  } finally {
    breaking.value = false
  }
}

/** S15 保存提示词/自定义 TEXT 正文 → versionApi.create 写 {body} 新版本（保留其他键）。 */
async function saveTextBody() {
  if (!asset.value) return
  savingTextBody.value = true
  try {
    const next = writeTextBody(asset.value.content, asset.value.mediaType, textBody.value.trim())
    await versionApi.create(asset.value.id, { content: next, changeNote: '编辑正文' })
    message.success('正文已保存')
    await loadAll(asset.value.id)
    emit('changed', asset.value.id)
  } catch {
    message.error('保存正文失败')
  } finally {
    savingTextBody.value = false
  }
}

/** S15 删除资产（软删；画布引用快照不受影响，bindings 留存历史）→ 关抽屉 + 通知父重载。 */
async function deleteAsset() {
  if (!asset.value) return
  deleting.value = true
  try {
    await assetApi.remove(asset.value.id)
    message.success('已删除')
    const deletedId = asset.value.id
    emit('changed', deletedId)
    emit('update:show', false)
  } catch {
    message.error('删除失败')
  } finally {
    deleting.value = false
  }
}

/** 状态机动作分发（L2 定稿/解锁；L3 归档/取消归档） */
async function doAction(action: 'lock' | 'unlock' | 'archive' | 'unarchive') {
  if (!asset.value) return
  try {
    const res = await versionApi[action](asset.value.id)
    // 状态机接口返 meta-only（content/fileId=null，懒加载语义）；保留抽屉已加载的正文+文件不丢失
    const next = res.data.data
    if (next) {
      if (next.content == null && asset.value.content != null) next.content = asset.value.content
      if (next.fileId == null && asset.value.fileId != null) next.fileId = asset.value.fileId
    }
    asset.value = next
    // C3 状态机动作后 content 可能被 meta-only 响应覆盖，重解析剧本正文
    if (asset.value?.mediaType === MEDIA_TYPE.SCRIPT) {
      parseScriptContent(asset.value.content)
    }
    message.success('操作成功')
    emit('changed', asset.value.id)
  } catch {
    message.error('操作失败')
  }
}

/** 下载文件类资产：/files/{fileId} blob → <a download> */
async function download() {
  if (!asset.value?.fileId) return
  try {
    const res = await request.get<Blob>(`/files/${asset.value.fileId}`, { responseType: 'blob' })
    const url = URL.createObjectURL(res.data)
    const a = document.createElement('a')
    a.href = url
    a.download = `${asset.value.name || asset.value.fileId}`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  } catch {
    message.error('下载失败')
  }
}

defineExpose({ asset, usages, loading, synopsis, scenes, textBody, doAction, download, loadAll, saveSynopsis, saveTextBody, deleteAsset, runBreakdown, parseScriptContent })

/** 一致性包保存后 → 重载资产（content 含新一致性包，产了新版本）+ 通知父 */
async function onConsistencySaved(assetId: number) {
  await loadAll(assetId)
  emit('changed', assetId)
}
</script>

<style lang="scss" scoped>
.asset-detail {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-4);

  &__loading {
    display: flex;
    justify-content: center;
    min-height: 200px;
    align-items: center;
  }

  &__preview {
    min-height: 120px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--color-bg-secondary);
    border-radius: var(--radius-md, 8px);
    padding: var(--spacing-2);
    word-break: break-all;
  }

  &__media {
    max-width: 100%;
    max-height: 320px;
    border-radius: var(--radius-md, 8px);
  }

  &__text {
    margin: 0;
    width: 100%;
    white-space: pre-wrap;
    font-family: var(--font-family-mono, monospace);
    font-size: var(--font-size-sm);
    color: var(--color-text-secondary);
    max-height: 320px;
    overflow: auto;
  }

  &__text-edit {
    width: 100%;
    display: flex;
    flex-direction: column;
    gap: var(--spacing-2);
    text-align: left;
  }

  &__script {
    width: 100%;
    display: flex;
    flex-direction: column;
    gap: var(--spacing-2);
    text-align: left;
  }

  &__script-actions {
    display: flex;
    gap: var(--spacing-2);
  }

  &__actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--spacing-2);
  }

  &__section-title {
    margin: 0 0 var(--spacing-2);
    font-size: var(--font-size-md);
    color: var(--color-text-primary);
  }

  &__usage-list {
    list-style: none;
    padding: 0;
    margin: 0;
    display: flex;
    flex-direction: column;
    gap: var(--spacing-2);

    li {
      font-size: var(--font-size-sm);
      color: var(--color-text-secondary);
    }
  }

  &__usage-time {
    color: var(--color-text-tertiary);
    margin-left: var(--spacing-2);
    font-size: var(--font-size-xs);
  }
}
</style>
