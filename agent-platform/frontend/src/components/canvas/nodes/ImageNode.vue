<template>
  <CanvasNodeBase kind="image" kind-label="图片" :label="data.label" :status="data.status" :selected="selected" :asset-badge="assetBadge">
    <template #icon><ImageOutline /></template>
    <div
      v-if="data.previewUrl"
      class="image-node__thumb"
      @pointerdown="onPointerDown"
      @click="onMediaClick"
    >
      <img :src="data.previewUrl" alt="节点图" />
    </div>
    <div v-else class="image-node__empty">
      <n-icon :component="ImageOutline" size="22" />
      <span>上传或衍生图片</span>
    </div>
  </CanvasNodeBase>
</template>

<script setup lang="ts">
import { NIcon } from 'naive-ui'
import { ImageOutline } from '@vicons/ionicons5'
import CanvasNodeBase from './CanvasNodeBase.vue'
// 见 VideoNode：关 vue-flow $attrs 透传，防 `label:undefined` 覆盖显式 :label。
defineOptions({ inheritAttrs: false })
import { useNodeAssetBadge } from './useNodeAssetBadge'
// 修复IV B1（C-1 两段式）：已选中后再点缩略图才弹 Lightbox（第一击只选中）
import { useMediaPreviewClick } from './useMediaPreviewClick'
import type { CanvasNodeStatus } from '@/types/canvas'

const props = defineProps<{
  data: { label?: string; fileId?: string; previewUrl?: string; status?: CanvasNodeStatus } & Record<string, unknown>
  selected?: boolean
}>()

const assetBadge = useNodeAssetBadge(props.data)
const { onPointerDown, onMediaClick } = useMediaPreviewClick(() => props.selected === true)
</script>

<style lang="scss" scoped>
/* 修复III C5（2x-5）：结果图占满节点盒 contain 居中（16:9/9:16/1:1 同盒不裁切） */
.image-node__thumb {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
.image-node__thumb img {
  width: 100%;
  height: 100%;
  object-fit: contain;
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
