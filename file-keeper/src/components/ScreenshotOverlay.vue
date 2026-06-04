<template>
  <div
    class="fixed inset-0 z-[120] cursor-crosshair bg-black/30"
    tabindex="0"
    @mousedown="startSelection"
    @mousemove="updateSelection"
    @mouseup="finishSelection"
  >
    <div class="pointer-events-none absolute left-4 top-4 rounded bg-black/70 px-3 py-2 text-sm text-white">
      {{ t('screenshot.dragHint') }}
    </div>
    <div
      v-if="selectionBox"
      class="pointer-events-none absolute border border-white bg-white/10 shadow-[0_0_0_9999px_rgba(0,0,0,0.35)]"
      :style="selectionStyle"
    ></div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from '../composables/useI18n'
import type { ScreenshotRegion } from '../types/screenshot'

const emit = defineEmits<{
  capture: [region: ScreenshotRegion]
  cancel: []
}>()

const { t } = useI18n()
const minSize = 8
const start = ref<{ x: number; y: number } | null>(null)
const current = ref<{ x: number; y: number } | null>(null)
const screenStart = ref<{ x: number; y: number } | null>(null)
const screenCurrent = ref<{ x: number; y: number } | null>(null)

const selectionBox = computed(() => {
  if (!start.value || !current.value) return null
  const x = Math.min(start.value.x, current.value.x)
  const y = Math.min(start.value.y, current.value.y)
  const width = Math.abs(current.value.x - start.value.x)
  const height = Math.abs(current.value.y - start.value.y)
  return { x, y, width, height }
})

const selectionStyle = computed(() => {
  const box = selectionBox.value
  if (!box) return {}
  return {
    left: `${box.x}px`,
    top: `${box.y}px`,
    width: `${box.width}px`,
    height: `${box.height}px`
  }
})

function startSelection(event: MouseEvent) {
  start.value = { x: event.clientX, y: event.clientY }
  current.value = { x: event.clientX, y: event.clientY }
  screenStart.value = { x: event.screenX, y: event.screenY }
  screenCurrent.value = { x: event.screenX, y: event.screenY }
}

function updateSelection(event: MouseEvent) {
  if (!start.value) return
  current.value = { x: event.clientX, y: event.clientY }
  screenCurrent.value = { x: event.screenX, y: event.screenY }
}

function finishSelection(event: MouseEvent) {
  if (!start.value) return
  current.value = { x: event.clientX, y: event.clientY }
  screenCurrent.value = { x: event.screenX, y: event.screenY }
  const box = selectionBox.value
  const screenBox = screenStart.value && screenCurrent.value
    ? {
        x: Math.min(screenStart.value.x, screenCurrent.value.x),
        y: Math.min(screenStart.value.y, screenCurrent.value.y),
        width: Math.abs(screenCurrent.value.x - screenStart.value.x),
        height: Math.abs(screenCurrent.value.y - screenStart.value.y)
      }
    : null
  start.value = null
  current.value = null
  screenStart.value = null
  screenCurrent.value = null
  if (!box || !screenBox || box.width < minSize || box.height < minSize) {
    emit('cancel')
    return
  }
  emit('capture', { ...screenBox, scaleFactor: window.devicePixelRatio || 1 })
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    emit('cancel')
  }
}

onMounted(() => window.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown))
</script>
