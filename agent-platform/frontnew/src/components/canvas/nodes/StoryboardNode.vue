<script setup lang="ts">
defineOptions({ inheritAttrs: false })
import { FilmOutline } from '@vicons/ionicons5'
import NodeCardBase from '../NodeCardBase.vue'
import type { MockNodeData } from '@/mocks/types'

defineProps<{ data: MockNodeData; selected?: boolean }>()
</script>

<template>
  <NodeCardBase
    kind="storyboard"
    kind-label="分镜"
    :label="data.label"
    :status="data.status"
    :selected="selected"
    :scene-no="data.sceneNo"
    :duration-ms="data.durationMs"
    :tokens="data.tokens"
  >
    <template #icon><FilmOutline /></template>
    <div class="storyboard-node__grid" aria-hidden="true">
      <i v-for="n in 4" :key="n" />
    </div>
    <div class="storyboard-node__shots">{{ data.shots ?? 0 }} 个镜头</div>
  </NodeCardBase>
</template>

<style lang="scss" scoped>
.storyboard-node__grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 3px;

  i {
    aspect-ratio: 16 / 9;
    border-radius: 3px;
    background: color-mix(in srgb, var(--node-kind) 18%, transparent);
    border: 1px solid var(--line-1);
  }
}
.storyboard-node__shots {
  margin-top: var(--sp-1);
  font-size: 10px;
  color: var(--tx-3);
}
</style>
