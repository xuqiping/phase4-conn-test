<template>
  <NPopover
    v-model:show="show"
    trigger="manual"
    placement="right"
    :show-arrow="false"
    :duration="80"
  >
    <template #trigger>
      <span
        class="hover-preview-image"
        @mouseenter="onEnter"
        @mouseleave="onLeave"
      >
        <slot />
      </span>
    </template>
    <!-- previewSrc 由调用方传入「已加载的 objectURL」→ 悬浮零请求（4x#3/6x#1 拍板） -->
    <img v-if="previewSrc" :src="previewSrc" class="hover-preview-image__big" :alt="alt" />
    <span v-else class="hover-preview-image__empty">预览未加载</span>
  </NPopover>
</template>

<script setup lang="ts">
// 悬浮放大预览（共享组件）：包住任意触发元素，停留 delay(默认300ms) 弹大图浮层，移出即关。
// 快速划过不弹（防抖）；unmount 清计时器防泄漏。画布 @参考预览、反推关键帧时间轴复用。
import { onBeforeUnmount, ref } from 'vue'
import { NPopover } from 'naive-ui'

const props = withDefaults(defineProps<{
  /** 大图地址：调用方应传已加载的 objectURL（悬浮零请求）；null 显示占位 */
  previewSrc: string | null
  alt?: string
  /** 停留多少 ms 才弹（默认 300） */
  delay?: number
}>(), { alt: '预览', delay: 300 })

const show = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

function clearTimer() {
  if (timer !== null) {
    clearTimeout(timer)
    timer = null
  }
}
function onEnter() {
  clearTimer()
  timer = setTimeout(() => {
    timer = null
    show.value = true
  }, props.delay)
}
function onLeave() {
  clearTimer()
  show.value = false
}

onBeforeUnmount(clearTimer)
</script>

<style scoped lang="scss">
.hover-preview-image {
  display: inline-flex;
  cursor: zoom-in;

  &__big { max-width: 420px; max-height: 320px; object-fit: contain; border-radius: 6px; display: block; }
  &__empty { color: var(--text-color-3); font-size: 12px; }
}
</style>
