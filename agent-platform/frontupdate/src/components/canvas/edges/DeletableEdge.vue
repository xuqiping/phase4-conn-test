<template>
  <!-- BaseEdge 画贝塞尔弧线；选中高亮由 CanvasBoard watch 注入 edge.class（:deep canvas-edge--selected） -->
  <BaseEdge :id="id" :path="path[0]" :style="style" :marker-end="markerEnd" />
  <!-- 中点删除按钮（EdgeLabelRenderer 在屏幕坐标层，pan/zoom 自动跟随） -->
  <EdgeLabelRenderer>
    <div
      class="deletable-edge__del"
      :style="{ transform: `translate(-50%, -50%) translate(${path[1]}px, ${path[2]}px)` }"
      title="删除连线"
      @click.stop="onDelete"
    >
      <span class="deletable-edge__x">×</span>
    </div>
  </EdgeLabelRenderer>
</template>

<script setup lang="ts">
import { computed, inject } from 'vue'
import { BaseEdge, EdgeLabelRenderer, getBezierPath } from '@vue-flow/core'
import type { EdgeProps } from '@vue-flow/core'

const props = defineProps<EdgeProps>()

/** 由 CanvasBoard provide 的删除回调（走统一 removeEdges → emit structure-changed 落库）。 */
const removeEdge = inject<(id: string) => void>('canvasRemoveEdge', () => {})

const path = computed(() =>
  getBezierPath({
    sourceX: props.sourceX,
    sourceY: props.sourceY,
    sourcePosition: props.sourcePosition,
    targetX: props.targetX,
    targetY: props.targetY,
    targetPosition: props.targetPosition
  })
)

function onDelete() {
  if (props.id) removeEdge(props.id)
}
</script>

<style lang="scss" scoped>
.deletable-edge__del {
  position: absolute;
  pointer-events: all;
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--color-surface, #1f2937);
  border: 1px solid var(--color-border, #374151);
  color: var(--color-text-secondary, #9ca3af);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  opacity: 0.55;
  transition: opacity var(--duration-instant) var(--ease-in-out), background var(--duration-instant) var(--ease-in-out), color var(--duration-instant) var(--ease-in-out);
  z-index: 5;

  &:hover {
    opacity: 1;
    background: #ef4444;
    border-color: #ef4444;
    color: #fff;
  }
}

.deletable-edge__x {
  pointer-events: none;
  transform: translateY(-1px);
}
</style>
