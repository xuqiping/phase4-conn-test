<template>
  <CanvasNodeBase kind="audio" kind-label="音频" :status="data.status" :selected="selected">
    <template #icon><MusicalNotesOutline /></template>
    <div class="audio-node__desc">{{ data.label || '音频节点（上传 / TTS / 音乐生成）' }}</div>
  </CanvasNodeBase>
</template>

<script setup lang="ts">
import { MusicalNotesOutline } from '@vicons/ionicons5'
import CanvasNodeBase from './CanvasNodeBase.vue'
import type { CanvasNodeStatus } from '@/types/canvas'

defineProps<{
  data: { label?: string; fileId?: string; audioMode?: 'upload' | 'tts' | 'music'; status?: CanvasNodeStatus }
  selected?: boolean
}>()
</script>

<style lang="scss" scoped>
.audio-node__desc {
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  line-height: 1.5;
}
</style>
