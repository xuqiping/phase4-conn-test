<template>
  <div
    class="canvas-node"
    :class="[
      `canvas-node--${kind}`,
      { 'canvas-node--selected': selected, [`canvas-node--${status}`]: !!status }
    ]"
  >
    <Handle type="target" :position="Position.Top" />
    <div class="canvas-node__accent" />
    <div class="canvas-node__header">
      <div class="canvas-node__icon"><n-icon size="14"><slot name="icon" /></n-icon></div>
      <span class="canvas-node__kind">{{ kindLabel }}</span>
      <!-- C6：节点名显在头部（类型标签旁）；空=「未命名」灰字。改名走 PropertyPanel 名称框 → 头部实时更新 -->
      <span v-if="label" class="canvas-node__label" :title="label">{{ label }}</span>
      <span v-else class="canvas-node__label canvas-node__label--empty">未命名</span>
      <span v-if="assetBadge" class="canvas-node__asset" :data-has-update="assetBadge.hasUpdate">
        {{ assetBadge.name }} v{{ assetBadge.version }}<template v-if="assetBadge.hasUpdate"> · 有新版</template>
      </span>
      <span v-if="status" class="canvas-node__status" :data-status="status">{{ statusLabel }}</span>
    </div>
    <div class="canvas-node__body">
      <slot />
    </div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { NIcon } from 'naive-ui'
import type { CanvasNodeStatus, AssetBadge } from '@/types/canvas'

const props = defineProps<{
  kind: 'text' | 'image' | 'video' | 'audio' | 'script' | 'storyboard'
  kindLabel: string
  /** C6：节点名（data.label）；空显「未命名」灰字。 */
  label?: string
  status?: CanvasNodeStatus
  selected?: boolean
  /** S12：资产绑定徽标（来自资产·name vN / 有新版）。 */
  assetBadge?: AssetBadge
}>()

const STATUS_LABEL: Record<CanvasNodeStatus, string> = {
  idle: '待生成',
  running: '生成中',
  success: '完成',
  failed: '失败'
}

const statusLabel = computed(() => (props.status ? STATUS_LABEL[props.status] : ''))
</script>

<style lang="scss" scoped>
.canvas-node {
  position: relative;
  width: 200px;
  min-height: 64px;
  background: linear-gradient(145deg, rgba(8, 18, 34, 0.98), rgba(17, 28, 47, 0.96));
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  cursor: pointer;
  box-shadow: 0 12px 26px rgba(0, 0, 0, 0.28), inset 0 1px 0 rgba(255, 255, 255, 0.06);
  transition: border-color var(--duration-fast) var(--ease-in-out),
    box-shadow var(--duration-fast) var(--ease-in-out);

  &:hover,
  &--selected {
    border-color: var(--color-primary);
    box-shadow: 0 14px 30px rgba(0, 0, 0, 0.34), 0 0 0 1px rgba(var(--color-primary-rgb), 0.3),
      0 0 22px rgba(var(--color-primary-rgb), 0.2);
  }
}

.canvas-node__accent {
  height: 3px;
  background: linear-gradient(90deg, var(--color-primary), var(--color-gradient-end));
}

.canvas-node--success { border-color: rgba(34, 197, 94, 0.5); }
.canvas-node--failed { border-color: rgba(239, 68, 68, 0.55); }
.canvas-node--running { border-color: rgba(56, 189, 248, 0.6); }

.canvas-node__header {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: 8px 10px 4px;
}

.canvas-node__icon {
  width: 22px;
  height: 22px;
  flex: 0 0 22px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(145deg, var(--color-primary), var(--color-gradient-end));
  color: #08111f;
}

.canvas-node__kind {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--color-text-tertiary);
}

// C6 节点名（头部主显位，占中间空间把资产/状态徽标推到右）
.canvas-node__label {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  font-weight: 500;
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  &--empty {
    color: var(--color-text-tertiary);
    font-weight: 400;
    font-style: italic;
  }
}

.canvas-node__asset {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  background: rgba(var(--color-primary-rgb), 0.14);
  color: var(--color-primary);
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  &[data-has-update='true'] {
    background: rgba(250, 204, 21, 0.16);
    color: #facc15;
  }
}

.canvas-node__status {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  background: var(--color-primary-light);
  color: var(--color-text-secondary);

  &[data-status='success'] { color: #4ade80; }
  &[data-status='failed'] { color: #f87171; }
  &[data-status='running'] { color: #38bdf8; }
}

.canvas-node__body {
  padding: 4px 10px 10px;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  word-break: break-word;
}
</style>
