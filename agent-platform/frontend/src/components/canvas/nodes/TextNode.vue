<template>
  <CanvasNodeBase kind="text" kind-label="文本" :label="data.label" :status="data.status" :selected="selected" :asset-badge="assetBadge">
    <template #icon><DocumentTextOutline /></template>
    <div v-if="data.outputText" class="text-node__output">{{ data.outputText }}</div>
    <div v-else class="text-node__prompt">{{ data.prompt || '双击右侧面板编辑提示词' }}</div>
  </CanvasNodeBase>
</template>

<script setup lang="ts">
import { DocumentTextOutline } from '@vicons/ionicons5'
import CanvasNodeBase from './CanvasNodeBase.vue'
import { useNodeAssetBadge } from './useNodeAssetBadge'
import type { CanvasNodeStatus } from '@/types/canvas'

const props = defineProps<{
  data: { label?: string; prompt?: string; outputText?: string; status?: CanvasNodeStatus } & Record<string, unknown>
  selected?: boolean
}>()

const assetBadge = useNodeAssetBadge(props.data)
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

.text-node__output {
  display: -webkit-box;
  -webkit-line-clamp: 5;
  line-clamp: 5;
  -webkit-box-orient: vertical;
  overflow: hidden;
  color: var(--color-text-primary);
  font-size: var(--font-size-xs);
  line-height: 1.5;
  white-space: pre-wrap;
  min-height: 18px;
}
</style>
