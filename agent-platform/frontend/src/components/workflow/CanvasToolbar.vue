<!-- ============================================================
  画布工具栏 — 底部悬浮，缩放/撤销/导出操作
  ============================================================ -->
<template>
  <div class="canvas-toolbar">
    <div class="canvas-toolbar__group">
      <n-tooltip trigger="hover" placement="top">
        <template #trigger>
          <n-button quaternary size="small" @click="emit('zoom-in')">
            <template #icon>
              <n-icon :component="AddOutline" />
            </template>
          </n-button>
        </template>
        放大
      </n-tooltip>

      <n-tooltip trigger="hover" placement="top">
        <template #trigger>
          <n-button quaternary size="small" @click="emit('zoom-out')">
            <template #icon>
              <n-icon :component="RemoveOutline" />
            </template>
          </n-button>
        </template>
        缩小
      </n-tooltip>

      <n-tooltip trigger="hover" placement="top">
        <template #trigger>
          <n-button quaternary size="small" @click="emit('fit-view')">
            <template #icon>
              <n-icon :component="ExpandOutline" />
            </template>
          </n-button>
        </template>
        适应画布
      </n-tooltip>
    </div>

    <div class="canvas-toolbar__divider" />

    <div class="canvas-toolbar__group">
      <n-tooltip trigger="hover" placement="top">
        <template #trigger>
          <n-button quaternary size="small" @click="emit('undo')">
            <template #icon>
              <n-icon :component="ArrowUndoOutline" />
            </template>
          </n-button>
        </template>
        撤销
      </n-tooltip>

      <n-tooltip trigger="hover" placement="top">
        <template #trigger>
          <n-button quaternary size="small" @click="emit('redo')">
            <template #icon>
              <n-icon :component="ArrowRedoOutline" />
            </template>
          </n-button>
        </template>
        重做
      </n-tooltip>
    </div>

    <div class="canvas-toolbar__divider" />

    <div class="canvas-toolbar__group">
      <n-tooltip trigger="hover" placement="top">
        <template #trigger>
          <n-button quaternary size="small" @click="emit('export')">
            <template #icon>
              <n-icon :component="DownloadOutline" />
            </template>
          </n-button>
        </template>
        导出JSON
      </n-tooltip>
    </div>
  </div>
</template>

<script setup lang="ts">
import { NButton, NIcon, NTooltip } from 'naive-ui'
import {
  AddOutline,
  RemoveOutline,
  ExpandOutline,
  ArrowUndoOutline,
  ArrowRedoOutline,
  DownloadOutline
} from '@vicons/ionicons5'

const emit = defineEmits<{
  (e: 'zoom-in'): void
  (e: 'zoom-out'): void
  (e: 'fit-view'): void
  (e: 'undo'): void
  (e: 'redo'): void
  (e: 'export'): void
}>()
</script>

<style lang="scss" scoped>
.canvas-toolbar {
  display: flex;
  align-items: center;
  gap: var(--spacing-1);
  padding: var(--spacing-1) var(--spacing-2);
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
}

.canvas-toolbar__group {
  display: flex;
  align-items: center;
  gap: 2px;
}

.canvas-toolbar__divider {
  width: 1px;
  height: 20px;
  background: var(--color-border);
  margin: 0 var(--spacing-1);
}
</style>
