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
// 见 VideoNode：关 vue-flow $attrs 透传，防 `label:undefined` 覆盖显式 :label。
defineOptions({ inheritAttrs: false })
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

// 2x 四轮 S2：用户拉过高度（--resized 由 CanvasNodeBase 按 data.height 加）→
// 解除行数截断，全文可见，超高由节点 body 滚动（canvas-node__body overflow-y:auto）
.canvas-node--resized .text-node__prompt,
.canvas-node--resized .text-node__output {
  display: block;
  -webkit-line-clamp: unset;
  line-clamp: unset;
}
</style>
