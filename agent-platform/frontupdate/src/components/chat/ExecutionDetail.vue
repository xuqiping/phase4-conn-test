<template>
  <div v-if="steps.length" class="execution-detail">
    <div class="execution-detail__header" @click="expanded = !expanded">
      <n-icon size="14" :component="expanded ? ChevronDownOutline : ChevronForwardOutline" />
      <span>执行详情 ({{ steps.length }}步)</span>
    </div>
    <div v-if="expanded" class="execution-detail__steps">
      <div
        v-for="step in steps"
        :key="step.id"
        class="execution-detail__step"
        :class="`execution-detail__step--${step.status.toLowerCase()}`"
      >
        <span class="execution-detail__step-icon">
          <template v-if="step.status === 'SUCCESS'">&#10003;</template>
          <template v-else-if="step.status === 'FAILED'">&#10007;</template>
          <template v-else-if="step.status === 'RUNNING'">&#9679;</template>
          <template v-else>&#9675;</template>
        </span>
        <span class="execution-detail__step-name">{{ step.stepName || step.action }}</span>
        <span v-if="step.duration" class="execution-detail__step-duration">{{ step.duration }}ms</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { NIcon } from 'naive-ui'
import { ChevronDownOutline, ChevronForwardOutline } from '@vicons/ionicons5'
import type { ExecutionStepLog } from '@/api/execution'

defineProps<{
  steps: ExecutionStepLog[]
}>()

const expanded = ref(false)
</script>

<style lang="scss" scoped>
.execution-detail {
  margin: 8px 0;
  border-radius: var(--radius-base);
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--color-border-light);
}

.execution-detail__header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  cursor: pointer;
  font-size: 12px;
  color: var(--color-text-secondary);

  &:hover {
    color: var(--color-text-primary);
  }
}

.execution-detail__steps {
  padding: 4px 12px 8px;
}

.execution-detail__step {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 12px;
  color: var(--color-text-secondary);
}

.execution-detail__step-icon {
  width: 16px;
  text-align: center;
}

.execution-detail__step--success .execution-detail__step-icon {
  color: #52c41a;
}

.execution-detail__step--failed .execution-detail__step-icon {
  color: #ff4d4f;
}

.execution-detail__step--running .execution-detail__step-icon {
  color: var(--color-primary);
  animation: pulse 1s infinite;
}

.execution-detail__step-name {
  flex: 1;
}

.execution-detail__step-duration {
  color: var(--color-text-tertiary);
  font-size: 11px;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}
</style>
