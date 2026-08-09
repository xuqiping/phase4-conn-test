<template>
  <teleport to="body">
    <div class="focus-overlay" @mouseup="onMouseUp" @mousemove="onMouseMove">
      <div class="focus-overlay__bar">
        <span class="focus-overlay__title">焦点编辑 · 在图上框选要提取的元素</span>
        <n-button size="small" quaternary @click="emit('cancel')">取消</n-button>
        <n-button size="small" type="primary" :disabled="!rect" @click="onConfirm">提取为新图节点</n-button>
      </div>
      <div ref="stageRef" class="focus-overlay__stage" @mousedown="onMouseDown">
        <img v-if="previewUrl" :src="previewUrl" class="focus-overlay__img" alt="焦点编辑底图" draggable="false" />
        <div v-if="rect" class="focus-overlay__rect" :style="rectStyle" />
        <div v-if="!previewUrl" class="focus-overlay__empty">该图节点尚无可预览图片</div>
      </div>
      <div class="focus-overlay__hint">
        R-8 弱保底：框选区会作为新图节点的 cropRect + 描述（prompt）记录，实际元素提取质量依赖后续生图/分割模型。
      </div>
    </div>
  </teleport>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton } from 'naive-ui'
import type { CropRect } from '@/types/canvas'

defineProps<{
  /** 底图预览 URL（blob objectURL，会话级）。 */
  previewUrl?: string
}>()

const emit = defineEmits<{
  (e: 'confirm', payload: { rect: CropRect; description: string }): void
  (e: 'cancel'): void
}>()

const stageRef = ref<HTMLElement | null>(null)
const start = ref<{ x: number; y: number } | null>(null)
/** 框选矩形（相对 stage 的 px，归一化由调用方按需换算）。 */
const rect = ref<CropRect | null>(null)

function onMouseDown(e: MouseEvent) {
  // 抑制浏览器原生 img 拖拽（幽灵预览会劫持后续 drag 事件致框选断裂 + 视觉上图片被拉动）
  e.preventDefault()
  e.stopPropagation()
  const stage = stageRef.value
  if (!stage) return
  const r = stage.getBoundingClientRect()
  start.value = { x: e.clientX - r.left, y: e.clientY - r.top }
  rect.value = { x: start.value.x, y: start.value.y, w: 0, h: 0 }
}

function onMouseMove(e: MouseEvent) {
  if (!start.value || !stageRef.value) return
  const r = stageRef.value.getBoundingClientRect()
  const cx = clamp(e.clientX - r.left, 0, r.width)
  const cy = clamp(e.clientY - r.top, 0, r.height)
  rect.value = {
    x: Math.min(start.value.x, cx),
    y: Math.min(start.value.y, cy),
    w: Math.abs(cx - start.value.x),
    h: Math.abs(cy - start.value.y)
  }
}

function onMouseUp() {
  start.value = null
}

function onConfirm() {
  if (!rect.value || (rect.value.w < 8 || rect.value.h < 8)) return
  emit('confirm', {
    rect: { ...rect.value },
    description: '从图节点框选元素提取（待生图/分割模型）'
  })
}

const rectStyle = computed(() => {
  if (!rect.value) return {}
  const { x, y, w, h } = rect.value
  return { left: `${x}px`, top: `${y}px`, width: `${w}px`, height: `${h}px` }
})

function clamp(v: number, lo: number, hi: number) {
  return Math.max(lo, Math.min(hi, v))
}
</script>

<style lang="scss" scoped>
.focus-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(0, 0, 0, 0.88);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-3);
  user-select: none;

  &__bar {
    display: flex;
    align-items: center;
    gap: var(--spacing-2);
    color: var(--color-text-primary);
  }

  &__title {
    font-size: var(--font-size-sm);
    margin-right: var(--spacing-3);
  }

  &__stage {
    position: relative;
    max-width: 90vw;
    max-height: 70vh;
    cursor: crosshair;
    line-height: 0;
  }

  &__img {
    max-width: 90vw;
    max-height: 70vh;
    display: block;
    -webkit-user-drag: none;
    user-select: none;
  }

  &__rect {
    position: absolute;
    border: 2px dashed var(--color-primary);
    background: rgba(var(--color-primary-rgb), 0.15);
    pointer-events: none;
  }

  &__empty {
    color: var(--color-text-tertiary);
    padding: var(--spacing-6);
    font-size: var(--font-size-sm);
  }

  &__hint {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    max-width: 600px;
    text-align: center;
  }
}
</style>
