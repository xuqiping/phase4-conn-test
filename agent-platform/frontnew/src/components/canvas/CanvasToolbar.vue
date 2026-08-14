<script setup lang="ts">
import { NIcon, NTooltip } from 'naive-ui'
import {
  AddOutline,
  RemoveOutline,
  ScanOutline,
  GridOutline
} from '@vicons/ionicons5'
import { useVueFlow } from '@vue-flow/core'

// 画布工具栏：缩放/适应视图/节点计数（vue-flow 内置 Controls 隐藏，样式自绘保证主题一致）
const props = defineProps<{ nodeCount: number }>()

const { zoomIn, zoomOut, fitView } = useVueFlow()
</script>

<template>
  <div class="canvas-toolbar">
    <n-tooltip placement="left">
      <template #trigger>
        <button class="canvas-toolbar__btn" aria-label="放大" @click="zoomIn()">
          <n-icon :size="16"><AddOutline /></n-icon>
        </button>
      </template>
      放大
    </n-tooltip>
    <n-tooltip placement="left">
      <template #trigger>
        <button class="canvas-toolbar__btn" aria-label="缩小" @click="zoomOut()">
          <n-icon :size="16"><RemoveOutline /></n-icon>
        </button>
      </template>
      缩小
    </n-tooltip>
    <n-tooltip placement="left">
      <template #trigger>
        <button class="canvas-toolbar__btn" aria-label="适应视图" @click="fitView()">
          <n-icon :size="16"><ScanOutline /></n-icon>
        </button>
      </template>
      适应视图
    </n-tooltip>
    <span class="canvas-toolbar__count">
      <n-icon :size="12"><GridOutline /></n-icon>
      {{ props.nodeCount }} 节点
    </span>
  </div>
</template>

<style lang="scss" scoped>
.canvas-toolbar {
  position: absolute;
  right: var(--sp-4);
  top: var(--sp-4);
  z-index: 10;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: var(--sp-1);
  border-radius: var(--r-md);
  background: var(--sf-3);
  border: 1px solid var(--line-1);
  box-shadow: var(--shadow-pop);

  &__btn {
    width: 28px;
    height: 28px;
    display: grid;
    place-items: center;
    border: none;
    border-radius: var(--r-sm);
    background: transparent;
    color: var(--tx-2);
    cursor: pointer;
    transition: background var(--d-fast) var(--ease), color var(--d-fast) var(--ease);

    &:hover {
      background: var(--sf-2);
      color: var(--tx-1);
    }
  }

  &__count {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    margin-top: var(--sp-1);
    padding-top: var(--sp-1);
    border-top: 1px solid var(--line-1);
    font-family: var(--font-mono);
    font-size: 10px;
    color: var(--tx-3);
  }
}
</style>
