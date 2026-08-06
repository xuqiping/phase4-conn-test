<template>
  <CanvasNodeBase kind="audio" kind-label="音频" :label="data.label" :status="data.status" :selected="selected" :asset-badge="assetBadge">
    <template #icon><MusicalNotesOutline /></template>
    <audio v-if="data.previewUrl" class="audio-node__player" :src="data.previewUrl" controls />
    <div v-else class="audio-node__desc">音频节点（上传 / TTS / 音乐生成）</div>
  </CanvasNodeBase>
</template>

<script setup lang="ts">
import { MusicalNotesOutline } from '@vicons/ionicons5'
import CanvasNodeBase from './CanvasNodeBase.vue'
import { useNodeAssetBadge } from './useNodeAssetBadge'
import type { CanvasNodeStatus } from '@/types/canvas'

const props = defineProps<{
  data: {
    label?: string
    fileId?: string
    /** 会话级音频 objectURL（上传/生成产物，不入快照）。 */
    previewUrl?: string
    audioMode?: 'upload' | 'tts' | 'music'
    status?: CanvasNodeStatus
  } & Record<string, unknown>
  selected?: boolean
}>()

const assetBadge = useNodeAssetBadge(props.data)
</script>

<style lang="scss" scoped>
.audio-node__desc {
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  line-height: 1.5;
}
.audio-node__player {
  width: 100%;
  height: 32px;
}
</style>
