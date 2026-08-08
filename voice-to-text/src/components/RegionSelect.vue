<script setup lang="ts">
// 区域框选 overlay（规格 §3.1 Should；2026-08-08 Phase4 手测问题4）。
// 独立全屏透明窗口（region-select），由 open_region_select 创建，
// 加载 index.html#/region-select 时 App.vue 只渲染本组件。
// 拖出矩形 → Enter/确定 → finish_region_select（CSS 像素 × scaleFactor = 物理像素）；
// Esc/取消 → cancel_region_select。
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { invoke } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'

const dragging = ref(false)
const startX = ref(0)
const startY = ref(0)
const curX = ref(0)
const curY = ref(0)
const error = ref('')

const rect = ref<{ x: number; y: number; w: number; h: number } | null>(null)

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
    // 窗口由后端关闭
  } catch (e) {
    error.value = `确认失败: ${e}`
  }
}

async function cancel() {
  await invoke('cancel_region_select').catch(() => {})
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') cancel()
  if (e.key === 'Enter') confirm()
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div
    class="overlay"
    @mousedown="onDown"
    @mousemove="onMove"
    @mouseup="onUp"
  >
    <div class="hint-bar">
      拖动鼠标框选录制区域（仅主显示器） · Enter 确认 · Esc 取消
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
  /* 半透明遮罩：窗口本身 transparent，看得见底下的屏幕内容 */
  background: rgba(0, 0, 0, 0.35);
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
}
.selection {
  position: fixed;
  border: 2px solid #2563eb;
  /* 选区内保持清晰：用大阴影把遮罩“挖洞” */
  box-shadow: 0 0 0 9999px rgba(0, 0, 0, 0.35);
  background: transparent;
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
}
</style>
