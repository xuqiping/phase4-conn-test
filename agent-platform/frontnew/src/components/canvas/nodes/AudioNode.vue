<script setup lang="ts">
defineOptions({ inheritAttrs: false })
import { MusicalNotesOutline } from '@vicons/ionicons5'
import NodeCardBase from '../NodeCardBase.vue'
import type { MockNodeData } from '@/mocks/types'

defineProps<{ data: MockNodeData; selected?: boolean }>()

// 波形占位：固定伪随机高度序列（不引随机数，保证渲染稳定）
const BARS = [38, 62, 45, 80, 55, 92, 48, 70, 36, 66, 84, 50, 74, 42, 60, 88, 46, 58, 40, 68, 52, 78, 44, 64]
</script>

<template>
  <NodeCardBase
    kind="audio"
    kind-label="音频"
    :label="data.label"
    :status="data.status"
    :selected="selected"
    :scene-no="data.sceneNo"
    :duration-ms="data.durationMs"
    :tokens="data.tokens"
  >
    <template #icon><MusicalNotesOutline /></template>
    <div class="audio-node__wave" aria-hidden="true">
      <i v-for="(hb, i) in BARS" :key="i" :style="{ height: hb + '%' }" />
    </div>
  </NodeCardBase>
</template>

<style lang="scss" scoped>
.audio-node__wave {
  display: flex;
  align-items: center;
  gap: 2px;
  height: 36px;

  i {
    flex: 1;
    border-radius: 1px;
    background: color-mix(in srgb, var(--node-kind) 65%, transparent);
  }
}
</style>
