<template>
  <!--
    修复X B2（2x 未解决②）：从库选择行——缩略（四态）+ meta + 音频条 + 选择按钮。
    交互三分离：点缩略=预览（图/视开 Lightbox）、点「选择」=选定、行空白不动作（防误选）。
    独立成行组件：useLazyFilePreview 是组合式，须每行一个实例（AssetCard 网格同范式）。
  -->
  <div class="picker-row" :class="{ 'picker-row--archived': asset.status === 'ARCHIVED' }">
    <!-- 图/视缩略 = 预览按钮（键盘 Enter 可开灯箱）；音/文无灯箱语义 → 静态块（音频走行内条、文本即片段） -->
    <button
      v-if="isImage || isVideo"
      ref="thumbRoot"
      type="button"
      class="picker-row__thumb"
      :class="{ 'picker-row__thumb--ready': !!url }"
      :aria-label="`预览 ${asset.name}`"
      :title="url ? '点击放大预览' : '预览加载中'"
      @click="onPreview"
    >
      <HoverPreviewImage :preview-src="url" :kind="isVideo ? 'video' : 'image'" :alt="asset.name">
        <img v-if="isImage && url" :src="url" :alt="asset.name" />
        <video v-else-if="isVideo && url" :src="url" preload="metadata" muted playsinline />
        <span v-else class="picker-row__ph">{{ typeGlyph }}</span>
      </HoverPreviewImage>
      <span v-if="isVideo && url" class="picker-row__play" aria-hidden="true">▶</span>
    </button>
    <div v-else ref="thumbRoot" class="picker-row__thumb" :aria-label="`${asset.mediaType}资产`">
      <span v-if="isAudio" class="picker-row__ph">音</span>
      <p v-else-if="asset.textPreview" class="picker-row__thumb-text">{{ asset.textPreview }}</p>
      <span v-else class="picker-row__ph">{{ typeGlyph }}</span>
    </div>

    <div class="picker-row__main">
      <div class="picker-row__name">{{ asset.name }}</div>
      <div class="picker-row__meta">
        v{{ asset.currentVersion }} · {{ statusLabel }}
        <span v-if="asset.roleKeys?.length"> · {{ asset.roleKeys.join('/') }}</span>
      </div>
      <!-- 音频行内播放条（细化1）：整行宽、点击不冒泡（交互三分离：播条≠选定） -->
      <audio
        v-if="isAudio"
        class="picker-row__audio"
        :src="url ?? undefined"
        controls
        preload="none"
        @click.stop
      />
    </div>

    <n-button size="small" type="primary" tertiary :loading="picking" @click="emit('pick', asset)">
      选择
    </n-button>

    <!-- 点缩略放大（z-index 3000 盖住本 n-modal 弹窗）；url 未就绪/失败不开 -->
    <Lightbox
      :open="lightboxOpen"
      :kind="isVideo ? 'video' : 'image'"
      :src="url ?? undefined"
      :alt="asset.name"
      @close="lightboxOpen = false"
    />
  </div>
</template>

<script setup lang="ts">
// 修复X B2：单行渲染 + 懒加载 + 预览态全内聚；AssetPicker 只管列表与选定链（resolve 归父组件）。
import { computed, ref } from 'vue'
import { NButton } from 'naive-ui'
import HoverPreviewImage from '@/components/media/HoverPreviewImage.vue'
import Lightbox from './Lightbox.vue'
import { useLazyFilePreview } from '@/composables/useLazyFilePreview'
import type { AssetStatus, AssetVO } from '@/types/asset'

const props = defineProps<{
  asset: AssetVO
  /** 该行 resolve 进行中（「选择」按钮 loading）。 */
  picking?: boolean
}>()

const emit = defineEmits<{ (e: 'pick', a: AssetVO): void }>()

/** 类型判定按 mediaType（'图片'/'视频'/'音频'），其余（提示词/剧本/分镜）= 文本类（plan 坑点8：不依赖列表态 mediaCategory）。 */
const isImage = computed(() => props.asset.mediaType === '图片')
const isVideo = computed(() => props.asset.mediaType === '视频')
const isAudio = computed(() => props.asset.mediaType === '音频')
/** 类型字标回落（无 fileId/加载失败/文本无片段）：提示词→提、剧本→剧、分镜→分…… */
const typeGlyph = computed(() => props.asset.mediaType.charAt(0))

const STATUS_LABEL: Record<AssetStatus, string> = { DRAFT: '草稿', LOCKED: '已定稿', ARCHIVED: '已归档' }
const statusLabel = computed(() => STATUS_LABEL[props.asset.status] ?? props.asset.status)

/** 缩略懒加载：IO 门控 + LRU 复用；文本类不拉（enabled=false），音频拉 objectURL 供行内条播放。 */
const thumbRoot = ref<HTMLElement | null>(null)
const enabled = computed(() => isImage.value || isVideo.value || isAudio.value)
// failed 仅影响 url 为空（回落字标已覆盖，无需单独消费）
const { url } = useLazyFilePreview(thumbRoot, () => props.asset.fileId, enabled)

const lightboxOpen = ref(false)
function onPreview() {
  if (!url.value) return // 未就绪/失败不开（占位不可预期点）
  lightboxOpen.value = true
}
</script>

<style lang="scss" scoped>
.picker-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: var(--spacing-2) var(--spacing-3);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-base);
  transition: border-color var(--duration-instant) var(--ease-in-out);

  &:hover {
    border-color: var(--color-primary);
    background: var(--color-primary-light);
  }

  &--archived {
    opacity: 0.55;
  }
}

.picker-row__thumb {
  position: relative;
  width: 72px;
  height: 48px;
  flex: 0 0 72px;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: none;
  border-radius: var(--radius-small);
  background: #000;
  color: var(--color-text-tertiary);
  font-size: 11px;

  // 仅图/视是 button——有灯箱语义才给手型；音/文静态块保持默认
  &:is(button) {
    cursor: pointer;

    &:not(.picker-row__thumb--ready) {
      cursor: default;
    }
  }

  img,
  video {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.picker-row__ph {
  color: var(--color-text-tertiary);
}

.picker-row__thumb-text {
  margin: 0;
  padding: 2px 4px;
  width: 100%;
  font-size: 10px;
  line-height: 1.3;
  color: var(--color-text-tertiary);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.picker-row__play {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(255, 255, 255, 0.9);
  font-size: 14px;
  text-shadow: 0 0 4px rgba(0, 0, 0, 0.7);
  pointer-events: none;
}

.picker-row__main {
  flex: 1;
  min-width: 0;
}

.picker-row__name {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.picker-row__meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin-top: 2px;
}

.picker-row__audio {
  width: 100%;
  height: 32px;
  margin-top: var(--spacing-1);
}
</style>
