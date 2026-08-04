<template>
  <CanvasNodeBase kind="text" kind-label="文本" :status="data.status" :selected="selected">
    <template #icon><DocumentTextOutline /></template>
    <div class="text-node__prompt">{{ data.prompt || data.label || '双击右侧面板编辑提示词' }}</div>
  </CanvasNodeBase>
</template>

<script setup lang="ts">
import { DocumentTextOutline } from '@vicons/ionicons5'
import CanvasNodeBase from './CanvasNodeBase.vue'
import type { CanvasNodeStatus } from '@/types/canvas'

defineProps<{
  data: { label?: string; prompt?: string; status?: CanvasNodeStatus }
  selected?: boolean
}>()
</script>

<style lang="scss" scoped>
.text-node__prompt {
  display: -webkit-box;
  -webkit-line-clamp: 4;
  line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  line-height: 1.5;
  white-space: pre-wrap;
  min-height: 18px;
}
</style>
