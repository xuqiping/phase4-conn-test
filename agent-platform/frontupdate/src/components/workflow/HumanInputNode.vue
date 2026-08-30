<template>
  <div class="human-input-node" :class="{ 'human-input-node--selected': selected }">
    <Handle type="target" :position="Position.Top" />
    <div class="human-input-node__header">
      <div class="human-input-node__icon">
        <n-icon size="14" color="#fff">
          <ChatbubbleEllipsesOutline />
        </n-icon>
      </div>
      <span class="human-input-node__kind">提问</span>
    </div>
    <div class="human-input-node__body">
      <span class="human-input-node__name">{{ data.label }}</span>
      <span class="human-input-node__meta">{{ metaText }}</span>
    </div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { NIcon } from 'naive-ui'
import { ChatbubbleEllipsesOutline } from '@vicons/ionicons5'

const props = defineProps<{
  data: {
    label: string
    inputKey?: string
    inputType?: string
    questionTemplate?: string
    options?: string[]
  }
  selected?: boolean
}>()

const metaText = computed(() => {
  const key = props.data.inputKey ? `#${props.data.inputKey}` : '未设变量'
  const type = props.data.inputType || 'text'
  if (type === 'select') {
    const count = Array.isArray(props.data.options) ? props.data.options.length : 0
    return `${key} · select(${count})`
  }
  return `${key} · ${type}`
})
</script>

<style lang="scss" scoped>
.human-input-node {
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

.human-input-node__header {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: var(--spacing-2) var(--spacing-3);
  border-bottom: 1px solid var(--color-border-light);
  background: var(--color-surface);
}

.human-input-node__icon {
  width: 20px;
  height: 20px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f59e0b;
}

.human-input-node__kind {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.human-input-node__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--spacing-2) var(--spacing-3);
}

.human-input-node__name,
.human-input-node__meta {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.human-input-node__name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.human-input-node__meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}
</style>
