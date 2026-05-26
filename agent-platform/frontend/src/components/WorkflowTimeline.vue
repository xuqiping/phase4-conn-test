<template>
  <div class="workflow-timeline">
    <div
      v-for="(step, index) in steps"
      :key="step.id"
      class="workflow-timeline__item"
    >
      <!-- 序号圆点 + 连接线 -->
      <div class="workflow-timeline__line">
        <div class="workflow-timeline__dot">
          <span class="workflow-timeline__number">{{ step.stepOrder || index + 1 }}</span>
        </div>
        <div
          v-if="index < steps.length - 1"
          class="workflow-timeline__connector"
        />
      </div>

      <!-- 步骤内容 -->
      <div class="workflow-timeline__content">
        <h4 class="workflow-timeline__step-name">{{ step.name }}</h4>
        <p v-if="step.action" class="workflow-timeline__step-action">
          <n-icon size="12" :component="PlayOutline" />
          <span>{{ step.action }}</span>
        </p>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-if="steps.length === 0" class="workflow-timeline__empty">
      <span>暂无工作流步骤</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { NIcon } from 'naive-ui'
import { PlayOutline } from '@vicons/ionicons5'
import type { SkillStep } from '@/api/agent'

defineProps<{
  steps: SkillStep[]
}>()
</script>

<style lang="scss" scoped>
.workflow-timeline {
  padding-left: var(--spacing-2);
}

.workflow-timeline__item {
  display: flex;
  gap: var(--spacing-3);
  position: relative;
}

.workflow-timeline__line {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 28px;
  flex-shrink: 0;
}

.workflow-timeline__dot {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-full);
  background: linear-gradient(
    135deg,
    var(--color-gradient-start),
    var(--color-gradient-end)
  );
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.workflow-timeline__number {
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-bold);
  color: #fff;
}

.workflow-timeline__connector {
  width: 1px;
  flex: 1;
  min-height: 16px;
  border-left: 1px dashed var(--color-border);
}

.workflow-timeline__content {
  flex: 1;
  padding-bottom: var(--spacing-4);
}

.workflow-timeline__step-name {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  margin: 2px 0 var(--spacing-1);
}

.workflow-timeline__step-action {
  display: flex;
  align-items: center;
  gap: var(--spacing-1);
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin: 0;
}

.workflow-timeline__empty {
  padding: var(--spacing-6);
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}
</style>
