<template>
  <CanvasNodeBase kind="video" kind-label="视频" :status="data.status" :selected="selected">
    <template #icon><VideocamOutline /></template>
    <div class="video-node__prompt">{{ data.prompt || '视频节点（面板配置 prompt/比例/时长）' }}</div>
    <div v-if="data.ratio || data.duration" class="video-node__meta">
      <span v-if="data.ratio">{{ data.ratio }}</span>
      <span v-if="data.duration">{{ data.duration }}s</span>
      <span v-if="data.resolution">{{ data.resolution }}</span>
    </div>
  </CanvasNodeBase>
</template>

<script setup lang="ts">
import { VideocamOutline } from '@vicons/ionicons5'
import CanvasNodeBase from './CanvasNodeBase.vue'
import type { CanvasNodeStatus } from '@/types/canvas'

defineProps<{
  data: {
    label?: string
    prompt?: string
    ratio?: string
    duration?: number
    resolution?: string
    status?: CanvasNodeStatus
  }
  selected?: boolean
}>()
</script>

<style lang="scss" scoped>
.video-node__prompt {
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.video-node__meta {
  display: flex;
  gap: var(--spacing-2);
  margin-top: var(--spacing-1);
  font-size: 10px;
  color: var(--color-text-tertiary);
}
</style>
