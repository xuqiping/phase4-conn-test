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
          <n-tag size="small" bordered>{{ MEDIA_LABEL[asset.mediaType] }}</n-tag>
          <n-tag size="small" bordered type="info">v{{ asset.currentVersion }}</n-tag>
        </n-space>

        <!-- 预览 -->
        <div class="asset-detail__preview">
          <template v-if="asset.mediaType === 'IMAGE'">
            <img v-if="previewUrl" :src="previewUrl" class="asset-detail__media" alt="预览" />
          </template>
          <template v-else-if="asset.mediaType === 'VIDEO'">
            <video v-if="previewUrl" :src="previewUrl" controls class="asset-detail__media" />
          </template>
          <template v-else-if="asset.mediaType === 'AUDIO'">
            <audio v-if="previewUrl" :src="previewUrl" controls />
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
  NSpace,
  NSpin,
  NTag,
  useMessage
} from 'naive-ui'
import { assetApi, assetBridgeApi, versionApi } from '@/api/assets'
import { fetchCanvasPreview } from '@/api/canvas'
import request from '@/api/request'
import ConsistencyPack, { type ConsistencyPack as ConsistencyPackData } from '@/components/asset/ConsistencyPack.vue'
import VersionTimeline from '@/components/asset/VersionTimeline.vue'
import type { AssetStatus, AssetMediaType, AssetUsageVO, AssetVO } from '@/types/asset'

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

const drawerWidth = computed(() => (window.innerWidth < 768 ? '100%' : 520))

const STATUS_LABEL: Record<AssetStatus, string> = { DRAFT: '草稿', LOCKED: '已定稿', ARCHIVED: '已归档' }
const STATUS_TYPE: Record<AssetStatus, 'default' | 'success' | 'warning'> = {
  DRAFT: 'default',
  LOCKED: 'success',
  ARCHIVED: 'warning'
}
const MEDIA_LABEL: Record<AssetMediaType, string> = {
  PROMPT: '提示词',
  SCRIPT: '剧本',
  IMAGE: '图片',
  VIDEO: '视频',
  AUDIO: '音频'
}

const needsFile = computed(() => props.show && !!asset.value && ['IMAGE', 'VIDEO', 'AUDIO'].includes(asset.value.mediaType))

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
    // 文件类拉预览 objectURL
    if (asset.value?.fileId && ['IMAGE', 'VIDEO', 'AUDIO'].includes(asset.value.mediaType)) {
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

/** 状态机动作分发（L2 定稿/解锁；L3 归档/取消归档） */
async function doAction(action: 'lock' | 'unlock' | 'archive' | 'unarchive') {
  if (!asset.value) return
  try {
    const res = await versionApi[action](asset.value.id)
    asset.value = res.data.data
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

defineExpose({ asset, usages, loading, doAction, download, loadAll })

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
