<script setup lang="ts">
defineOptions({ inheritAttrs: false })
import { VideocamOutline } from '@vicons/ionicons5'
import NodeCardBase from '../NodeCardBase.vue'
import type { MockNodeData } from '@/mocks/types'

defineProps<{ data: MockNodeData; selected?: boolean }>()
</script>

<template>
  <NodeCardBase
    kind="video"
    kind-label="视频"
    :label="data.label"
    :status="data.status"
    :selected="selected"
    :scene-no="data.sceneNo"
    :duration-ms="data.durationMs"
    :tokens="data.tokens"
  >
    <template #icon><VideocamOutline /></template>
    <!-- 1.85:1 遮幅缩略图 + 时长徽标 -->
    <div class="video-node__thumb">
      <span v-if="data.durationSec" class="video-node__dur">{{ data.durationSec }}s</span>
    </div>
  </NodeCardBase>
</template>

<style lang="scss" scoped>
.video-node__thumb {
  position: relative;
  aspect-ratio: 1.85 / 1;
  border-radius: var(--r-sm);
  background: linear-gradient(
    160deg,
    color-mix(in srgb, var(--node-kind) 28%, transparent),
    color-mix(in srgb, var(--node-kind) 5%, transparent)
  );
  border: 1px solid var(--line-1);
}

.video-node__dur {
  position: absolute;
  right: 6px;
  bottom: 6px;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--tx-1);
  background: rgba(0, 0, 0, 0.55);
  border-radius: var(--r-sm);
  padding: 1px 5px;
}
</style>
