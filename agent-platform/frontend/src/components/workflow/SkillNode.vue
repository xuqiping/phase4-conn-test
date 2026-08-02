<!-- ============================================================
  技能节点 — 圆角卡片，显示技能名+Agent主题色图标
  ============================================================ -->
<template>
  <div class="skill-node" :class="{ 'skill-node--selected': selected }">
    <Handle type="target" :position="Position.Top" />
    <div class="skill-node__header">
      <div class="skill-node__icon" :style="{ background: agentColor }">
        <n-icon size="14" color="#fff">
          <FlashOutline />
        </n-icon>
      </div>
      <span class="skill-node__agent">{{ data.agentName || 'Agent' }}</span>
    </div>
    <div class="skill-node__body">
      <span class="skill-node__name">{{ data.label }}</span>
      <span v-if="data.description" class="skill-node__desc">{{ data.description }}</span>
    </div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { NIcon } from 'naive-ui'
import { FlashOutline } from '@vicons/ionicons5'

const props = defineProps<{
  id: string
  data: {
    label: string
    agentName?: string
    description?: string
    agentId?: number
  }
  selected?: boolean
}>()

/** 根据agentId生成不同的主题色 */
const agentColor = computed(() => {
  const colors = [
    '#4F7CFF', '#9333EA', '#F59E0B',
    '#10B981', '#EF4444', '#EC4899',
    '#6366F1', '#14B8A6'
  ]
  const index = (props.data.agentId || 0) % colors.length
  return colors[index]
})
</script>

<style lang="scss" scoped>
.skill-node {
  min-width: 160px;
  max-width: 220px;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  cursor: pointer;
  transition: border-color var(--duration-fast) var(--ease-in-out),
              box-shadow var(--duration-fast) var(--ease-in-out);

  &:hover {
    border-color: var(--color-primary);
    box-shadow: var(--shadow-primary);
  }

  &--selected {
    border-color: var(--color-primary);
    box-shadow: 0 0 0 2px rgba(var(--color-primary-rgb), 0.2);
  }
}

.skill-node__header {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: var(--spacing-2) var(--spacing-3);
  border-bottom: 1px solid var(--color-border-light);
  background: var(--color-surface);
}

.skill-node__icon {
  width: 20px;
  height: 20px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.skill-node__agent {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.skill-node__body {
  padding: var(--spacing-2) var(--spacing-3);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.skill-node__name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.skill-node__desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
