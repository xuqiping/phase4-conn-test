<script setup lang="ts">
// 关 vue-flow $attrs 透传，防 `label:undefined` 覆盖显式 prop（沿用 frontend 的坑批注）
defineOptions({ inheritAttrs: false })
import { DocumentTextOutline } from '@vicons/ionicons5'
import NodeCardBase from '../NodeCardBase.vue'
import type { MockNodeData } from '@/mocks/types'

defineProps<{ data: MockNodeData; selected?: boolean }>()
</script>

<template>
  <NodeCardBase
    kind="text"
    kind-label="文本"
    :label="data.label"
    :status="data.status"
    :selected="selected"
    :scene-no="data.sceneNo"
    :duration-ms="data.durationMs"
    :tokens="data.tokens"
  >
    <template #icon><DocumentTextOutline /></template>
    <div v-if="data.outputText" class="text-node__output">{{ data.outputText }}</div>
    <div v-else class="text-node__prompt">{{ data.prompt || '双击编辑提示词' }}</div>
  </NodeCardBase>
</template>

<style lang="scss" scoped>
.text-node__prompt,
.text-node__output {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: var(--fs-xs);
  line-height: 1.5;
}
.text-node__prompt {
  color: var(--tx-3);
}
.text-node__output {
  color: var(--tx-2);
}
</style>
