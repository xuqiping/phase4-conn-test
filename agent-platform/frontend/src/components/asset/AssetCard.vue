<!--
  项目资产库·资产卡片  plan §S11
  - 纯展示：点击开 S10 抽屉（emit open）
  - C2 缩略图懒加载：文件类（IMAGE/VIDEO）进入视口拉 objectURL 显缩略/首帧；
    AUDIO/文本类保持类型色块图标。预览失败/无 fileId → 回退色块（useLazyFilePreview 托管缓存+释放）
  - 状态/版本/叙事角色徽标（L2/L3 态可见）
-->
<template>
  <div class="asset-card" @click="emit('open', asset)">
    <div ref="coverRef" class="asset-card__cover" :class="`asset-card__cover--${coverTone}`">
      <img
        v-if="showImagePreview"
        class="asset-card__cover-media"
        :src="previewUrl ?? undefined"
        alt=""
        @error="onMediaError"
      />
      <video
        v-else-if="showVideoPreview"
        class="asset-card__cover-media"
        :src="previewUrl ?? undefined"
        preload="metadata"
        muted
        playsinline
        @loadedmetadata="seekFirstFrame"
        @error="onMediaError"
      ></video>
      <template v-else>
        <!-- S16：TEXT 类有正文片段 → 封面显引文片段（不再只显 emoji 色块，Bug④） -->
        <p v-if="effectiveCategory === 'text' && asset.textPreview" class="asset-card__cover-text">
          {{ asset.textPreview }}
        </p>
        <span v-else class="asset-card__cover-icon">{{ icon }}</span>
      </template>
      <n-tag class="asset-card__status" size="tiny" bordered :type="STATUS_TYPE[asset.status]">
        {{ STATUS_LABEL[asset.status] }}
      </n-tag>
    </div>
    <div class="asset-card__body">
      <div class="asset-card__name-row">
        <span class="asset-card__name" :title="asset.name">{{ asset.name }}</span>
        <!-- C7 上传者徽标（超长截断；title 兜底完整用户名） -->
        <span
          v-if="asset.createdByUsername"
          class="asset-card__uploader"
          :title="`上传者：${asset.createdByUsername}`"
        >
          ↑{{ asset.createdByUsername }}
        </span>
        <span class="asset-card__version">v{{ asset.currentVersion }}</span>
      </div>
      <div v-if="asset.description" class="asset-card__desc">{{ asset.description }}</div>
      <div v-else class="asset-card__desc asset-card__desc--empty">暂无描述</div>
      <!-- C7 双轨评分行：拥有者 ★88 B ｜ 成员均分 90 · 3人 A（2x#7 等级为后端派生字段；无任何评分不渲染） -->
      <div v-if="hasScores" class="asset-card__scores">
        <span v-if="asset.ownerScore != null" class="asset-card__score asset-card__score--owner">
          拥有者 ★{{ asset.ownerScore }}<b v-if="asset.ownerGrade" class="asset-card__grade">{{ asset.ownerGrade }}</b>
        </span>
        <span v-if="asset.memberAvgScore != null" class="asset-card__score asset-card__score--member">
          成员均分 {{ asset.memberAvgScore }} · {{ asset.memberCount ?? 0 }}人<b v-if="asset.memberAvgGrade" class="asset-card__grade">{{ asset.memberAvgGrade }}</b>
        </span>
      </div>
      <div class="asset-card__footer">
        <div class="asset-card__roles">
          <n-tag v-for="k in displayRoles" :key="k" size="tiny" round>{{ k }}</n-tag>
          <span v-if="extraRoles" class="asset-card__more">+{{ extraRoles }}</span>
        </div>
        <span class="asset-card__type">{{ typeLabel }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { NTag } from 'naive-ui'
import type { AssetMediaType, AssetStatus, AssetVO } from '@/types/asset'
import { MEDIA_TYPE } from '@/types/asset'
import { useLazyFilePreview } from '@/composables/useLazyFilePreview'

const props = defineProps<{ asset: AssetVO }>()
const emit = defineEmits<{ (e: 'open', asset: AssetVO): void }>()

const STATUS_LABEL: Record<AssetStatus, string> = { DRAFT: '草稿', LOCKED: '已定稿', ARCHIVED: '已归档' }
const STATUS_TYPE: Record<AssetStatus, 'default' | 'success' | 'warning'> = {
  DRAFT: 'default',
  LOCKED: 'success',
  ARCHIVED: 'warning'
}
const MEDIA_ICON: Record<AssetMediaType, string> = {
  [MEDIA_TYPE.PROMPT]: '📝',
  [MEDIA_TYPE.SCRIPT]: '🎬',
  [MEDIA_TYPE.STORYBOARD]: '🎞',
  [MEDIA_TYPE.IMAGE]: '🖼️',
  [MEDIA_TYPE.VIDEO]: '🎞️',
  [MEDIA_TYPE.AUDIO]: '🎵'
}

/** 媒体类型→处理类别 兜底推断（asset 无 mediaCategory 时按默认 key 推断；V60 两层）。 */
function inferCategoryFromType(type: string): string {
  switch (type) {
    case MEDIA_TYPE.PROMPT:
    case MEDIA_TYPE.SCRIPT:
    case MEDIA_TYPE.STORYBOARD:
      return 'text'
    case MEDIA_TYPE.IMAGE:
      return 'image'
    case MEDIA_TYPE.VIDEO:
      return 'video'
    case MEDIA_TYPE.AUDIO:
      return 'audio'
    default:
      return 'text'
  }
}

/** 处理类别（优先 asset.mediaCategory，V60 后端必返；旧数据兜底按 type 推断）。 */
const effectiveCategory = computed(() => {
  const c = props.asset.mediaCategory?.toLowerCase()
  return c && ['text', 'image', 'video', 'audio'].includes(c) ? c : inferCategoryFromType(props.asset.mediaType)
})

/** 封面色调按处理类别（暗色主题下作占位背景；自定义 type 走 category 色调） */
const coverTone = computed(() => effectiveCategory.value)

/** 图标：默认 5 类按 type 精确图标，自定义 type 走 category 图标兜底。 */
const CATEGORY_ICON: Record<string, string> = { text: '📝', image: '🖼️', video: '🎞️', audio: '🎵' }
const icon = computed(() => MEDIA_ICON[props.asset.mediaType] ?? CATEGORY_ICON[effectiveCategory.value] ?? '📄')

/** 类型标签：默认 5 类有中文，自定义 type 显原文 key。 */
const typeLabel = computed(() => props.asset.mediaType)

/** 叙事角色徽标最多展示 3 个，超出聚合计数（防溢出） */
const MAX_ROLES = 3
const displayRoles = computed(() => (props.asset.roleKeys ?? []).slice(0, MAX_ROLES))
const extraRoles = computed(() => Math.max(0, (props.asset.roleKeys?.length ?? 0) - MAX_ROLES))

/** C7 双轨评分行渲染条件：任一轨有分（无分不渲染该行）。 */
const hasScores = computed(
  () => props.asset.ownerScore != null || props.asset.memberAvgScore != null
)

// ---------- C2 缩略图懒加载 ----------
const coverRef = ref<HTMLElement | null>(null)
/** 仅 IMAGE/VIDEO 类启用预览（AUDIO 走图标，MVP 不做波形）。 */
const previewEnabled = computed(() => effectiveCategory.value === 'image' || effectiveCategory.value === 'video')
const { url: previewUrl, failed } = useLazyFilePreview(
  coverRef,
  () => props.asset.fileId ?? null,
  previewEnabled
)
/** 媒体加载错误（onerror）→ 显式置 failed，回退色块。 */
function onMediaError() {
  failed.value = true
}
const showImagePreview = computed(() => previewEnabled.value && effectiveCategory.value === 'image' && previewUrl.value && !failed.value)
const showVideoPreview = computed(() => previewEnabled.value && effectiveCategory.value === 'video' && previewUrl.value && !failed.value)

/** VIDEO 首帧：metadata 就绪后 seek 到 0.1s 显非黑屏首帧（blob URL 不一定支持 #t 片段）。 */
function seekFirstFrame(e: Event) {
  const v = e.target as HTMLVideoElement
  try {
    v.currentTime = 0.1
  } catch {
    /* 忽略 seek 异常 */
  }
}
</script>

<style lang="scss" scoped>
.asset-card {
  position: relative;
  display: flex;
  flex-direction: column;
  background: var(--color-card-bg, var(--color-bg-secondary));
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg, 12px);
  cursor: pointer;
  overflow: hidden;
  transition: border-color var(--duration-fast), transform var(--duration-fast);

  &:hover {
    border-color: var(--color-primary);
    transform: translateY(-2px);
  }

  &__cover {
    position: relative;
    height: 96px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 36px;
    color: var(--color-text-white, #fff);
    background: linear-gradient(135deg, var(--color-primary), var(--color-primary-hover, var(--color-primary)));

    &--prompt { background: linear-gradient(135deg, #4b5fbf, #6a7bd8); }
    &--script { background: linear-gradient(135deg, #b8862f, #d4a14a); }
    &--image { background: linear-gradient(135deg, #2f8f6b, #46b388); }
    &--video { background: linear-gradient(135deg, #9c4fbf, #b873d8); }
    &--audio { background: linear-gradient(135deg, #bf4f6a, #d8738a); }
  }

  &__cover-icon {
    opacity: 0.92;
  }

  &__cover-text {
    margin: 0;
    padding: var(--spacing-3);
    width: 100%;
    max-height: 96px;
    overflow: hidden;
    text-align: left;
    font-family: var(--font-family-mono, monospace);
    font-size: var(--font-size-xs);
    line-height: 1.5;
    color: var(--color-text-white, #fff);
    opacity: 0.92;
    display: -webkit-box;
    -webkit-line-clamp: 3;
    -webkit-box-orient: vertical;
    text-overflow: ellipsis;
  }

  &__cover-media {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
    pointer-events: none;
  }

  &__status {
    position: absolute;
    top: var(--spacing-2);
    right: var(--spacing-2);
  }

  &__body {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-2);
    padding: var(--spacing-3);
  }

  &__name-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--spacing-2);
  }

  &__name {
    font-size: var(--font-size-md);
    font-weight: var(--font-weight-bold);
    color: var(--color-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__version {
    flex-shrink: 0;
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
  }

  &__uploader {
    flex-shrink: 0;
    max-width: 96px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
  }

  &__scores {
    display: flex;
    flex-wrap: wrap;
    gap: var(--spacing-2);
    font-size: var(--font-size-xs);
  }

  &__score {
    &--owner {
      color: var(--color-warning, #d4a14a);
    }

    &--member {
      color: var(--color-text-secondary);
    }
  }

  // 2x#7 等级徽章（数值右侧小胶囊，如 ★88 B）
  &__grade {
    display: inline-block;
    margin-left: var(--spacing-1);
    padding: 0 4px;
    border: 1px solid var(--color-border-light);
    border-radius: var(--radius-sm, 4px);
    font-size: var(--font-size-xs);
    font-weight: 600;
    line-height: 16px;
    vertical-align: baseline;
  }

  &__desc {
    font-size: var(--font-size-sm);
    color: var(--color-text-secondary);
    line-height: 1.4;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;

    &--empty {
      color: var(--color-text-tertiary);
    }
  }

  &__footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--spacing-2);
  }

  &__roles {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    overflow: hidden;
  }

  &__more {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    align-self: center;
  }

  &__type {
    flex-shrink: 0;
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
  }
}
</style>
