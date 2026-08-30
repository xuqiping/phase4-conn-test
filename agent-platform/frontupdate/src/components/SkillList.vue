<template>
  <div class="skill-list">
    <div
      v-for="skill in skills"
      :key="skill.id"
      class="skill-list__item"
      :class="{ 'skill-list__item--active': skill.id === selectedSkillId }"
      @click="$emit('select', skill.id)"
    >
      <div class="skill-list__item-bar" />
      <div class="skill-list__item-content">
        <span class="skill-list__item-name">{{ skill.name }}</span>
        <span v-if="skill.description" class="skill-list__item-desc">
          {{ skill.description }}
        </span>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-if="skills.length === 0" class="skill-list__empty">
      <span>暂无技能</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { Skill } from '@/api/agent'

defineProps<{
  skills: Skill[]
  selectedSkillId: number | null
}>()

defineEmits<{
  select: [skillId: number]
}>()
</script>

<style lang="scss" scoped>
.skill-list {
  display: flex;
  flex-direction: column;
}

.skill-list__item {
  display: flex;
  align-items: stretch;
  cursor: pointer;
  border-radius: var(--radius-base);
  transition: all var(--duration-instant) var(--ease-in-out);

  &:hover {
    background: var(--color-elevated);
  }

  &--active {
    background: var(--color-primary-light);

    .skill-list__item-bar {
      opacity: 1;
    }

    .skill-list__item-name {
      color: var(--color-primary);
    }
  }
}

.skill-list__item-bar {
  width: 3px;
  border-radius: var(--radius-full);
  background: linear-gradient(
    180deg,
    var(--color-gradient-start),
    var(--color-gradient-end)
  );
  opacity: 0;
  flex-shrink: 0;
  transition: opacity var(--duration-instant) var(--ease-in-out);
}

.skill-list__item-content {
  flex: 1;
  padding: var(--spacing-3) var(--spacing-3);
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow: hidden;
}

.skill-list__item-name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.skill-list__item-desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.skill-list__empty {
  padding: var(--spacing-6);
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}
</style>
