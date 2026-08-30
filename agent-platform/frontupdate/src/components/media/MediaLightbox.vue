<template>
  <Teleport to="body">
    <div
      v-if="src"
      class="media-lightbox"
      role="dialog"
      aria-modal="true"
      aria-label="图片预览"
      @click="emit('close')"
    >
      <!-- 点图不关（防误触），关=点遮罩/Esc/右上角按钮三路 -->
      <img :src="src" class="media-lightbox__img" :alt="alt" @click.stop />
      <button class="media-lightbox__close" type="button" aria-label="关闭预览" @click.stop="emit('close')">
        ×
      </button>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
// 全屏图片灯箱（共享组件）：从 ImageGenView 内联灯箱抽取（4x#3/6x#1）。
// 画布属性面板 @参考预览、视频反推关键帧时间轴等复用本组件——改动需回归所有调用方。
// 打开期间挂 window keydown 关 Esc；src 置空即卸载层（调用方持状态，组件无内部可见态）。
import { onBeforeUnmount, watch } from 'vue'

const props = defineProps<{
  /** 大图地址（objectURL 或可直访 URL）；null=关闭 */
  src: string | null
  alt?: string
}>()
const emit = defineEmits<{ close: [] }>()

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}

watch(() => props.src, opened => {
  if (opened) window.addEventListener('keydown', onKeydown)
  else window.removeEventListener('keydown', onKeydown)
}, { immediate: true })

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<style scoped lang="scss">
.media-lightbox {
  position: fixed; inset: 0; z-index: 2000;
  background: rgba(0, 0, 0, 0.92);
  display: flex; align-items: center; justify-content: center;
  cursor: zoom-out;

  &__img { max-width: 94vw; max-height: 92vh; object-fit: contain; cursor: default; }
  &__close {
    position: absolute; top: 16px; right: 20px;
    width: 36px; height: 36px; border: none; border-radius: 50%;
    background: rgba(255, 255, 255, 0.14); color: #fff;
    font-size: 22px; line-height: 1; cursor: pointer;
    &:hover { background: rgba(255, 255, 255, 0.26); }
  }
}
</style>
