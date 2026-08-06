<!--
  项目资产库·资产卡片  plan §S11
  - 纯展示：点击开 S10 抽屉（emit open）
  - 文件类首帧占位（IMAGE 缩略留待封面通道；本轮用类型色块图标，不拉 blob——列表多卡拉 blob 会爆）
  - 状态/版本/叙事角色徽标（L2/L3 态可见）
-->
<template>
  <div class="asset-card" @click="emit('open', asset)">
    <div class="asset-card__cover" :class="`asset-card__cover--${coverTone}`">
      <span class="asset-card__cover-icon">{{ icon }}</span>
      <n-tag class="asset-card__status" size="tiny" bordered :type="STATUS_TYPE[asset.status]">
        {{ STATUS_LABEL[asset.status] }}
      </n-tag>
    </div>
    <div class="asset-card__body">
      <div class="asset-card__name-row">
        <span class="asset-card__name" :title="asset.name">{{ asset.name }}</span>
        <span class="asset-card__version">v{{ asset.currentVersion }}</span>
      </div>
      <div v-if="asset.description" class="asset-card__desc">{{ asset.description }}</div>
      <div v-else class="asset-card__desc asset-card__desc--empty">暂无描述</div>
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
import { computed } from 'vue'
import { NTag } from 'naive-ui'
import type { AssetMediaType, AssetStatus, AssetVO } from '@/types/asset'

const props = defineProps<{ asset: AssetVO }>()
const emit = defineEmits<{ (e: 'open', asset: AssetVO): void }>()

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
const MEDIA_ICON: Record<AssetMediaType, string> = {
  PROMPT: '📝',
  SCRIPT: '🎬',
  IMAGE: '🖼️',
  VIDEO: '🎞️',
  AUDIO: '🎵'
}

/** 媒体类型→处理类别 兜底推断（asset 无 mediaCategory 时按默认 key 推断；V60 两层）。 */
function inferCategoryFromType(type: string): string {
  switch (type) {
    case 'PROMPT':
    case 'SCRIPT':
      return 'text'
    case 'IMAGE':
      return 'image'
    case 'VIDEO':
      return 'video'
    case 'AUDIO':
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
const typeLabel = computed(() => MEDIA_LABEL[props.asset.mediaType] ?? props.asset.mediaType)

/** 叙事角色徽标最多展示 3 个，超出聚合计数（防溢出） */
const MAX_ROLES = 3
const displayRoles = computed(() => (props.asset.roleKeys ?? []).slice(0, MAX_ROLES))
const extraRoles = computed(() => Math.max(0, (props.asset.roleKeys?.length ?? 0) - MAX_ROLES))
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
