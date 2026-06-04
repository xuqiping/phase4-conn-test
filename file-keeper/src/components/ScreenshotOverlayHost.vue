<template>
  <ScreenshotOverlay @capture="handleCapture" @cancel="handleCancel" />
</template>

<script setup lang="ts">
import { emit } from '@tauri-apps/api/event'
import { getCurrentWindow } from '@tauri-apps/api/window'
import ScreenshotOverlay from './ScreenshotOverlay.vue'
import type { ScreenshotRegion } from '../types/screenshot'

async function closeOverlayWindow() {
  await getCurrentWindow().destroy()
}

async function handleCapture(region: ScreenshotRegion) {
  await emit('screenshot://capture', region)
  await closeOverlayWindow()
}

async function handleCancel() {
  await emit('screenshot://cancel')
  await closeOverlayWindow()
}
</script>
