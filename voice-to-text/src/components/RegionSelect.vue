<script setup lang="ts">
// 区域框选层（规格 §3.1 Should；2026-08-08 Phase4 手测问题4）。
// 单窗口方案：store.beginRegionSelect 抓主屏截图并把主窗口 setFullscreen(true)，
// App.vue 在 regionSelectMode 时把本组件铺满窗口（截图当背景拖框）。
// 为什么不用独立窗口/透明窗口：Win10 上 Tauri 运行时二窗渲染纯白死窗（3 次实测）。
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { invoke } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { useSessionStore } from '../stores/session'

const store = useSessionStore()

const dragging = ref(false)
const startX = ref(0)
const startY = ref(0)
const curX = ref(0)
const curY = ref(0)
const error = ref('')

const rect = ref<{ x: number; y: number; w: number; h: number } | null>(null)

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

function computeRect() {
  const x = Math.min(startX.value, curX.value)
  const y = Math.min(startY.value, curY.value)
  const w = Math.abs(curX.value - startX.value)
  const h = Math.abs(curY.value - startY.value)
  rect.value = w >= 4 && h >= 4 ? { x, y, w, h } : null
}

function onDown(e: MouseEvent) {
  dragging.value = true
  startX.value = e.clientX
  startY.value = e.clientY
  curX.value = e.clientX
  curY.value = e.clientY
  rect.value = null
  error.value = ''
}

function onMove(e: MouseEvent) {
  if (!dragging.value) return
  curX.value = e.clientX
  curY.value = e.clientY
  computeRect()
}

function onUp() {
  dragging.value = false
  computeRect()
}

async function confirm() {
  if (!rect.value) {
    error.value = '请先拖动框选一个区域'
    return
  }
  try {
    // WGC 按物理像素裁剪：CSS 像素 × 缩放比（高分屏 1.25/1.5 必须换算）。
    const scale = await getCurrentWindow().scaleFactor()
    await invoke('finish_region_select', {
      x: Math.round(rect.value.x * scale),
      y: Math.round(rect.value.y * scale),
      width: Math.round(rect.value.w * scale),
      height: Math.round(rect.value.h * scale),
    })
    await store.endRegionSelect()
  } catch (e) {
    error.value = `确认失败: ${e}`
  }
}

async function cancel() {
  await store.endRegionSelect()
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') cancel()
  if (e.key === 'Enter') confirm()
}
</script>

<template>
  <div
    class="overlay"
    @mousedown="onDown"
    @mousemove="onMove"
    @mouseup="onUp"
  >
    <img v-if="store.regionShotSrc" class="bg" :src="store.regionShotSrc" alt="" draggable="false" />
    <div class="dim" />

    <div class="hint-bar" @mousedown.stop @mousemove.stop @mouseup.stop>
      拖动鼠标框选录制区域（截图即当前主屏画面） · Enter 确认 · Esc 取消
      <button class="btn" :disabled="!rect" @click.stop="confirm">确定</button>
      <button class="btn ghost" @click.stop="cancel">取消</button>
    </div>

    <div
      v-if="rect"
      class="selection"
      :style="{
        left: `${rect.x}px`,
        top: `${rect.y}px`,
        width: `${rect.w}px`,
        height: `${rect.h}px`,
      }"
    >
      <span class="size-tag">{{ rect.w }} × {{ rect.h }}</span>
    </div>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  cursor: crosshair;
  overflow: hidden;
  user-select: none;
  z-index: 100;
  background: #000;
}
.bg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: fill;
}
.dim {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
}
.hint-bar {
  position: fixed;
  top: 24px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 18px;
  font-size: 14px;
  color: #f0f0f0;
  background: rgba(20, 20, 20, 0.92);
  border: 1px solid #333;
  border-radius: 8px;
  cursor: default;
  z-index: 3;
}
.selection {
  position: fixed;
  border: 2px solid #2563eb;
  /* 选区内保持清晰：大阴影把遮罩"挖洞"，露出底下的截图 */
  box-shadow: 0 0 0 9999px rgba(0, 0, 0, 0.4);
  z-index: 2;
}
.size-tag {
  position: absolute;
  right: 0;
  bottom: -26px;
  font-size: 12px;
  color: #fff;
  background: #2563eb;
  border-radius: 4px;
  padding: 2px 8px;
  font-variant-numeric: tabular-nums;
}
.btn {
  padding: 6px 16px;
  font-size: 13px;
  background: #2563eb;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn.ghost {
  background: #222;
  border: 1px solid #333;
}
.error {
  position: fixed;
  top: 80px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 13px;
  color: #f87171;
  background: rgba(20, 20, 20, 0.92);
  padding: 6px 14px;
  border-radius: 6px;
  z-index: 3;
}
</style>
