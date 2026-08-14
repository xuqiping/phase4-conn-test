<script setup lang="ts">
defineOptions({ inheritAttrs: false })
import { ImageOutline } from '@vicons/ionicons5'
import NodeCardBase from '../NodeCardBase.vue'
import type { MockNodeData } from '@/mocks/types'

defineProps<{ data: MockNodeData; selected?: boolean }>()
</script>

<template>
  <NodeCardBase
    kind="image"
    kind-label="图像"
    :label="data.label"
    :status="data.status"
    :selected="selected"
    :scene-no="data.sceneNo"
    :duration-ms="data.durationMs"
    :tokens="data.tokens"
  >
    <template #icon><ImageOutline /></template>
    <!-- 16:9 缩略图占位（渐变 div，不引图片资源）+ 取景框四角 -->
    <div class="image-node__thumb">
      <i class="image-node__corner image-node__corner--tl" />
      <i class="image-node__corner image-node__corner--tr" />
      <i class="image-node__corner image-node__corner--bl" />
      <i class="image-node__corner image-node__corner--br" />
    </div>
  </NodeCardBase>
</template>

<style lang="scss" scoped>
.image-node__thumb {
  position: relative;
  aspect-ratio: 16 / 9;
  border-radius: var(--r-sm);
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--node-kind) 24%, transparent),
    color-mix(in srgb, var(--node-kind) 6%, transparent)
  );
  border: 1px solid var(--line-1);
}

// 取景框四角标记（影像母题，选中态加亮）
.image-node__corner {
  position: absolute;
  width: 8px;
  height: 8px;
  border: 1.5px solid color-mix(in srgb, var(--node-kind) 55%, transparent);

  &--tl { top: 4px; left: 4px; border-right: none; border-bottom: none; }
  &--tr { top: 4px; right: 4px; border-left: none; border-bottom: none; }
  &--bl { bottom: 4px; left: 4px; border-right: none; border-top: none; }
  &--br { bottom: 4px; right: 4px; border-left: none; border-top: none; }
}
</style>
