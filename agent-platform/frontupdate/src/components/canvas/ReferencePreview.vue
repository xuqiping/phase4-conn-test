<template>
  <div
    ref="rootEl"
    class="ref-preview"
    role="button"
    tabindex="0"
    :title="failed ? '预览加载失败' : `${item.label} · 点击${item.kind === 'video' ? '播放' : '放大'}`"
    @click="onOpen"
    @keydown.enter.prevent="onOpen"
  >
    <!-- 图片：HoverPreviewImage 悬浮放大（previewSrc 传已加载 objectURL，悬浮零请求） -->
    <HoverPreviewImage v-if="item.kind === 'image' && url" :preview-src="url" :alt="item.label">
      <img :src="url" class="ref-preview__thumb" :alt="item.label" draggable="false" />
    </HoverPreviewImage>
    <!-- 视频：blob objectURL + preload=metadata 原生渲染首帧当 poster（不调 /frames——那会每次产新 stored file 污染产出物） -->
    <video
      v-else-if="item.kind === 'video' && url"
      :src="url"
      class="ref-preview__thumb"
      preload="metadata"
      muted
    />
    <span v-else class="ref-preview__thumb ref-preview__placeholder">{{ failed ? '失败' : '…' }}</span>
    <!-- 播放角标（视频缩略右上角） -->
    <span v-if="item.kind === 'video'" class="ref-preview__play" aria-hidden="true">▶</span>
    <!-- 帧角色 / 图N / 视频N 徽标（与提交序号化同源） -->
    <span class="ref-preview__badge" :data-kind="item.kind">{{ item.label }}</span>
  </div>
</template>

<script setup lang="ts">
// 2x 四轮 S8：属性面板「参考」区单项缩略。fileId 懒加载（IntersectionObserver 门控 +
// 模块级 LRU 缓存，复用资产卡片同款 useLazyFilePreview）；点击上抛由父级开 MediaLightbox/播放弹窗。
import { ref } from 'vue'
import HoverPreviewImage from '@/components/media/HoverPreviewImage.vue'
import { useLazyFilePreview } from '@/composables/useLazyFilePreview'
import type { CanvasReferenceItem } from '@/utils/canvasVideoAttachments'

const props = defineProps<{
  item: CanvasReferenceItem
}>()

const emit = defineEmits<{
  /** 点击缩略 → 父级按 kind 开图片全屏（MediaLightbox）或视频播放弹窗。url 为当前 objectURL（可能未就绪 null）。 */
  (e: 'open', payload: { item: CanvasReferenceItem; url: string | null }): void
}>()

const rootEl = ref<HTMLElement | null>(null)
// 图片/视频同为文件字节流，走同一 /api/files/{id} 预览通道（fetchFilePreview 拉 blob）
const { url, failed } = useLazyFilePreview(
  rootEl,
  () => props.item.fileId,
  () => true
)

function onOpen() {
  if (failed.value) return
  emit('open', { item: props.item, url: url.value })
}
</script>

<style lang="scss" scoped>
.ref-preview {
  position: relative;
  width: 64px;
  height: 48px;
  flex-shrink: 0;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-base);
  overflow: hidden;
  cursor: pointer;
  background: var(--color-bg);

  &:hover,
  &:focus-visible {
    border-color: var(--color-primary);
  }

  &:focus-visible {
    outline: 2px solid var(--color-primary);
    outline-offset: 1px;
  }

  &__thumb {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }

  &__placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
  }

  &__play {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    color: rgba(255, 255, 255, 0.9);
    font-size: 16px;
    text-shadow: 0 0 4px rgba(0, 0, 0, 0.7);
    pointer-events: none;
  }

  &__badge {
    position: absolute;
    left: 0;
    bottom: 0;
    padding: 0 4px;
    font-size: 10px;
    line-height: 14px;
    color: #fff;
    background: rgba(0, 0, 0, 0.65);
    border-radius: 0 var(--radius-base) 0 0;
    pointer-events: none;

    &[data-kind='video'] {
      background: rgba(30, 110, 255, 0.85);
    }
  }
}
</style>
