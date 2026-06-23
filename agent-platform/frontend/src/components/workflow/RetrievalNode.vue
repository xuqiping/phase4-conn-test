<template>
  <div class="retrieval-node" :class="{ 'retrieval-node--selected': selected }">
    <Handle type="target" :position="Position.Top" />
    <div class="retrieval-node__header">
      <div class="retrieval-node__icon">
        <n-icon size="14" color="#fff">
          <SearchOutline />
        </n-icon>
      </div>
      <span class="retrieval-node__kind">检索</span>
    </div>
    <div class="retrieval-node__body">
      <span class="retrieval-node__name">{{ data.label }}</span>
      <span class="retrieval-node__meta">{{ metaText }}</span>
    </div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { NIcon } from 'naive-ui'
import { SearchOutline } from '@vicons/ionicons5'

const props = defineProps<{
  data: {
    label: string
    kbId?: number
    kbIds?: number[]
    query?: string
  }
  selected?: boolean
}>()

const metaText = computed(() => {
  const ids = props.data.kbIds && props.data.kbIds.length > 0
    ? props.data.kbIds
    : (props.data.kbId ? [props.data.kbId] : [])
  const kbPart = ids.length > 0 ? `KB ${ids.join(',')}` : '未绑定知识库'
  const queryPart = props.data.query ? '· 已设查询' : '· 无查询'
  return `${kbPart} ${queryPart}`
})
</script>

<style lang="scss" scoped>
.retrieval-node {
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
    border-color: #8b5cf6;
    box-shadow: 0 0 0 2px rgba(139, 92, 246, 0.18);
  }
}

.retrieval-node__header {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: var(--spacing-2) var(--spacing-3);
  border-bottom: 1px solid var(--color-border-light);
  background: var(--color-surface);
}

.retrieval-node__icon {
  width: 20px;
  height: 20px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  background: #8b5cf6;
}

.retrieval-node__kind {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.retrieval-node__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--spacing-2) var(--spacing-3);
}

.retrieval-node__name,
.retrieval-node__meta {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.retrieval-node__name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.retrieval-node__meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}
</style>
