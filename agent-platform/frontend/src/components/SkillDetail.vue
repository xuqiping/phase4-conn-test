<template>
  <div class="skill-detail">
    <template v-if="skill">
      <!-- 技能头部 -->
      <div class="skill-detail__header">
        <h3 class="skill-detail__name">{{ skill.name }}</h3>
        <span v-if="skill.type" class="skill-detail__type">{{ skill.type }}</span>
      </div>

      <!-- 技能描述 -->
      <p class="skill-detail__desc">
        {{ skill.description || '暂无描述' }}
      </p>

      <!-- 工作流步骤标题 -->
      <div class="skill-detail__section-title">
        <n-icon size="16" :component="GitBranchOutline" />
        <span>工作流步骤</span>
        <span class="skill-detail__step-count">{{ skill.steps?.length || 0 }} 步</span>
      </div>

      <!-- 工作流时间线 -->
      <WorkflowTimeline :steps="skill.steps || []" />
    </template>

    <!-- 未选择状态 -->
    <div v-else class="skill-detail__placeholder">
      <n-icon size="32" :component="FlashOutline" />
      <p>请从左侧选择一个技能查看详情</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { NIcon } from 'naive-ui'
import { GitBranchOutline, FlashOutline } from '@vicons/ionicons5'
import type { SkillDetail } from '@/api/agent'
import WorkflowTimeline from '@/components/WorkflowTimeline.vue'

defineProps<{
  skill: SkillDetail | null
}>()
</script>

<style lang="scss" scoped>
.skill-detail {
  padding: var(--spacing-6);
}

.skill-detail__header {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  margin-bottom: var(--spacing-3);
}

.skill-detail__name {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  margin: 0;
}

.skill-detail__type {
  font-size: var(--font-size-xs);
  color: var(--color-primary);
  background: var(--color-primary-light);
  padding: 2px 8px;
  border-radius: var(--radius-full);
}

.skill-detail__desc {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-relaxed);
  margin: 0 0 var(--spacing-6);
}

.skill-detail__section-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
  margin-bottom: var(--spacing-4);
  padding-bottom: var(--spacing-2);
  border-bottom: 1px solid var(--color-border-light);
}

.skill-detail__step-count {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin-left: auto;
}

.skill-detail__placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 300px;
  gap: var(--spacing-3);
  color: var(--color-text-tertiary);

  p {
    font-size: var(--font-size-sm);
    margin: 0;
  }
}
</style>
