<template>
  <div class="ref-node ref-node--workflow" :class="{ 'ref-node--selected': selected }">
    <Handle type="target" :position="Position.Top" />
    <div class="ref-node__header">
      <div class="ref-node__icon">
        <n-icon size="14" color="#fff">
          <GitBranchOutline />
        </n-icon>
      </div>
      <span class="ref-node__kind">Workflow</span>
    </div>
    <div class="ref-node__body">
      <span class="ref-node__name">{{ data.label }}</span>
      <span class="ref-node__meta">{{ data.workflowName || `ID ${data.workflowId || '-'}` }}</span>
    </div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'
import { NIcon } from 'naive-ui'
import { GitBranchOutline } from '@vicons/ionicons5'

defineProps<{
  data: {
    label: string
    workflowId?: number
    workflowName?: string
  }
  selected?: boolean
}>()
</script>

<style lang="scss" scoped>
.ref-node {
  min-width: 176px;
  max-width: 240px;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  cursor: pointer;
  transition: border-color var(--duration-fast) var(--ease-in-out),
              box-shadow var(--duration-fast) var(--ease-in-out);

  &:hover,
  &--selected {
    border-color: #f59e0b;
    box-shadow: 0 0 0 2px rgba(245, 158, 11, 0.18);
  }
}

.ref-node__header {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: var(--spacing-2) var(--spacing-3);
  border-bottom: 1px solid var(--color-border-light);
  background: var(--color-surface);
}

.ref-node__icon {
  width: 20px;
  height: 20px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f59e0b;
}

.ref-node__kind {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.ref-node__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--spacing-2) var(--spacing-3);
}

.ref-node__name,
.ref-node__meta {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ref-node__name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.ref-node__meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}
</style>
