<script setup lang="ts">
defineOptions({ inheritAttrs: false })
import { CodeSlashOutline } from '@vicons/ionicons5'
import NodeCardBase from '../NodeCardBase.vue'
import type { MockNodeData } from '@/mocks/types'

defineProps<{ data: MockNodeData; selected?: boolean }>()
</script>

<template>
  <NodeCardBase
    kind="script"
    kind-label="脚本"
    :label="data.label"
    :status="data.status"
    :selected="selected"
    :scene-no="data.sceneNo"
    :duration-ms="data.durationMs"
    :tokens="data.tokens"
  >
    <template #icon><CodeSlashOutline /></template>
    <div class="script-node__first">{{ data.firstLine || '# 空脚本' }}</div>
    <div class="script-node__lines">{{ data.lines ?? 0 }} 行</div>
  </NodeCardBase>
</template>

<style lang="scss" scoped>
.script-node__first {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--tx-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.script-node__lines {
  margin-top: var(--sp-1);
  font-size: 10px;
  color: var(--tx-3);
}
</style>
