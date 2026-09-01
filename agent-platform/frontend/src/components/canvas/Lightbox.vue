<template>
  <Teleport to="body">
    <!--
      D1（2x-8）：统一媒体预览灯箱（图片缩放/拖拽 + 视频播放）。
      与 MediaLightbox（仅图片静态全屏）分工：本组件供上游面板/画布大图细看——
      滚轮缩放 0.2–5x、拖拽平移、双击复位；视频走原生 controls（不自动播）。
      关闭三路：Esc / 点遮罩 / 右上角 ×；工具条 ±/复位 键盘可达（a11y）。
    -->
    <div
      v-if="open && src"
      ref="boxRef"
      class="lbx"
      role="dialog"
      aria-modal="true"
      :aria-label="kind === 'video' ? '视频预览' : '图片预览'"
      tabindex="-1"
      @click="emit('close')"
      @keydown.tab="onTab"
    >
      <template v-if="kind === 'video'">
        <video
          class="lbx__video"
          :src="src"
          :poster="poster"
          controls
          @click.stop
        ></video>
      </template>
      <template v-else>
        <img
          ref="imgRef"
          class="lbx__img"
          :src="src"
          :alt="alt ?? '图片预览'"
          :style="imgStyle"
          draggable="false"
          @click.stop
          @wheel.prevent="onWheel"
          @dblclick="reset"
          @pointerdown="onPointerDown"
          @pointermove="onPointerMove"
          @pointerup="onPointerUp"
          @pointercancel="onPointerUp"
        />
        <!-- 工具条：缩放 ±/复位（按钮键盘可达；比例文案只读） -->
        <div class="lbx__tools" @click.stop>
          <button type="button" class="lbx__btn" aria-label="缩小" @click="zoomBy(1 / 1.25)">－</button>
          <span class="lbx__pct">{{ Math.round(scale * 100) }}%</span>
          <button type="button" class="lbx__btn" aria-label="放大" @click="zoomBy(1.25)">＋</button>
          <button type="button" class="lbx__btn lbx__btn--reset" aria-label="复位缩放与位置" @click="reset">⤢ 复位</button>
        </div>
      </template>
      <button class="lbx__close" type="button" aria-label="关闭预览" @click.stop="emit('close')">×</button>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

const props = defineProps<{
  /** 开合（true 且 src 非空才挂载层）。 */
  open: boolean
  kind: 'image' | 'video'
  /** 媒体地址（任务产物 objectURL / 已存 URL；不接收任意用户输入 URL——由调用方保证来源）。 */
  src?: string
  poster?: string
  alt?: string
}>()

const emit = defineEmits<{ (e: 'close'): void }>()

/** 缩放区（图片）：0.2–5x；tx/ty 拖拽平移像素。 */
const scale = ref(1)
const tx = ref(0)
const ty = ref(0)
const imgRef = ref<HTMLImageElement | null>(null)
const boxRef = ref<HTMLDivElement | null>(null)

const imgStyle = computed(() => ({
  transform: `translate(${tx.value}px, ${ty.value}px) scale(${scale.value})`
}))

const MIN_SCALE = 0.2
const MAX_SCALE = 5

/** 滚轮步进缩放（deltaY<0 放大）；夹在 [0.2, 5]。 */
function onWheel(e: WheelEvent) {
  zoomBy(e.deltaY < 0 ? 1.15 : 1 / 1.15)
}

function zoomBy(factor: number) {
  const next = scale.value * factor
  scale.value = Math.min(MAX_SCALE, Math.max(MIN_SCALE, next))
}

function reset() {
  scale.value = 1
  tx.value = 0
  ty.value = 0
}

// ---- 拖拽平移（Pointer Events + 捕获；仅左键/触摸） ----
let dragging = false
let lastX = 0
let lastY = 0

function onPointerDown(e: PointerEvent) {
  if (e.button !== 0) return
  dragging = true
  lastX = e.clientX
  lastY = e.clientY
  imgRef.value?.setPointerCapture(e.pointerId)
}

function onPointerMove(e: PointerEvent) {
  if (!dragging) return
  tx.value += e.clientX - lastX
  ty.value += e.clientY - lastY
  lastX = e.clientX
  lastY = e.clientY
}

function onPointerUp(e: PointerEvent) {
  if (!dragging) return
  dragging = false
  imgRef.value?.releasePointerCapture(e.pointerId)
}

// ---- Esc 关闭 + 开层聚焦容器（Tab 圈在工具条/关闭钮内） ----
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}

/** 轻量 focus-trap：Tab 在层内可聚焦元素间循环（工具条 4 钮 + 关闭钮）。 */
function onTab(e: KeyboardEvent) {
  const root = boxRef.value
  if (!root) return
  const focusables = Array.from(root.querySelectorAll<HTMLElement>('button, [href], video'))
  if (!focusables.length) return
  e.preventDefault()
  const idx = focusables.indexOf(document.activeElement as HTMLElement)
  const nextIdx = e.shiftKey ? (idx <= 0 ? focusables.length - 1 : idx - 1) : (idx === focusables.length - 1 ? 0 : idx + 1)
  focusables[nextIdx].focus()
}

watch(
  () => props.open && props.src,
  (opened) => {
    window.removeEventListener('keydown', onKeydown)
    if (opened) {
      reset() // 重新打开复位缩放/平移（上次会话态不残留）
      window.addEventListener('keydown', onKeydown)
      nextTick(() => boxRef.value?.focus())
    }
  },
  { immediate: true }
)

defineExpose({ scale, tx, ty, reset, zoomBy })
</script>

<style scoped lang="scss">
.lbx {
  position: fixed;
  inset: 0;
  /* 修复X B1（2x 未解决②）：2000→3000——从库选择弹窗（n-modal，teleport body 默认 2000+ 栈）
     内开灯箱需盖住弹窗；AnnotateOverlay/FocusEditOverlay 的 2000 属编辑态独占交互，
     与灯箱不同屏共存不冲突。 */
  z-index: 3000;
  background: rgba(0, 0, 0, 0.92);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: zoom-out;
  outline: none;

  &__img {
    max-width: 94vw;
    max-height: 88vh;
    object-fit: contain;
    cursor: grab;
    user-select: none;
    transition: transform 0.05s linear;
    will-change: transform;

    &:active { cursor: grabbing; }
  }

  &__video {
    max-width: 94vw;
    max-height: 88vh;
    cursor: default;
  }

  &__tools {
    position: absolute;
    left: 50%;
    bottom: 20px;
    transform: translateX(-50%);
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 4px 8px;
    border-radius: 999px;
    background: rgba(20, 22, 30, 0.85);
    border: 1px solid rgba(255, 255, 255, 0.14);
  }

  &__btn {
    min-width: 30px;
    height: 28px;
    border: none;
    border-radius: 6px;
    background: transparent;
    color: #fff;
    font-size: 14px;
    line-height: 1;
    cursor: pointer;

    &:hover { background: rgba(255, 255, 255, 0.16); }
  }

  &__btn--reset { padding: 0 8px; font-size: 12px; }

  &__pct {
    min-width: 44px;
    text-align: center;
    color: rgba(255, 255, 255, 0.75);
    font-size: 12px;
    user-select: none;
  }

  &__close {
    position: absolute;
    top: 16px;
    right: 20px;
    width: 36px;
    height: 36px;
    border: none;
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.14);
    color: #fff;
    font-size: 22px;
    line-height: 1;
    cursor: pointer;

    &:hover { background: rgba(255, 255, 255, 0.26); }
  }
}
</style>
