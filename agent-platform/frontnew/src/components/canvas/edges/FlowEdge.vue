<script setup lang="ts">
import { computed } from 'vue'
import { BaseEdge, getBezierPath, type EdgeProps } from '@vue-flow/core'
import { NIcon } from 'naive-ui'
import { CloseOutline } from '@vicons/ionicons5'

// 贝塞尔连线：选中时中点显示删除钮（沿用现有 DeletableEdge 交互）
const props = defineProps<EdgeProps>()

const emit = defineEmits<{ remove: [string] }>()

const path = computed(() => {
  const [p] = getBezierPath({
    sourceX: props.sourceX,
    sourceY: props.sourceY,
    sourcePosition: props.sourcePosition,
    targetX: props.targetX,
    targetY: props.targetY,
    targetPosition: props.targetPosition
  })
  return p
})

const midX = computed(() => (props.sourceX + props.targetX) / 2)
const midY = computed(() => (props.sourceY + props.targetY) / 2)
</script>

<template>
  <BaseEdge :id="id" :path="path" class="flow-edge" :class="{ 'flow-edge--selected': selected }" />
  <foreignObject
    v-if="selected"
    :x="midX - 10"
    :y="midY - 10"
    width="20"
    height="20"
    class="flow-edge__btn-wrap"
  >
    <button class="flow-edge__btn" aria-label="删除连线" @click.stop="emit('remove', id)">
      <n-icon :size="12"><CloseOutline /></n-icon>
    </button>
  </foreignObject>
</template>

<style lang="scss">
/* vue-flow edge path 在其内部渲染，须非 scoped */
.flow-edge {
  stroke: var(--edge-color);
  stroke-width: 1.5;

  &--selected {
    stroke: var(--edge-active, var(--accent));
    stroke-width: 2;
  }
}

.flow-edge__btn {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 1px solid var(--line-2);
  background: var(--sf-3);
  color: var(--tx-1);
  cursor: pointer;
  display: grid;
  place-items: center;

  &:hover {
    background: var(--err);
    border-color: var(--err);
  }
}
</style>
