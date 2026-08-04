<template>
  <CanvasNodeBase kind="image" kind-label="图片" :status="data.status" :selected="selected">
    <template #icon><ImageOutline /></template>
    <div v-if="data.previewUrl" class="image-node__thumb">
      <img :src="data.previewUrl" alt="节点图" />
    </div>
    <div v-else class="image-node__empty">
      <n-icon :component="ImageOutline" size="22" />
      <span>{{ data.label || '上传或衍生图片' }}</span>
    </div>
  </CanvasNodeBase>
</template>

<script setup lang="ts">
import { NIcon } from 'naive-ui'
import { ImageOutline } from '@vicons/ionicons5'
import CanvasNodeBase from './CanvasNodeBase.vue'
import type { CanvasNodeStatus } from '@/types/canvas'

defineProps<{
  data: { label?: string; fileId?: string; previewUrl?: string; status?: CanvasNodeStatus }
  selected?: boolean
}>()
</script>

<style lang="scss" scoped>
.image-node__thumb img {
  width: 100%;
  border-radius: var(--radius-sm);
  display: block;
}
.image-node__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-1);
  padding: var(--spacing-3);
  color: var(--color-text-tertiary);
  font-size: var(--font-size-xs);
}
</style>
