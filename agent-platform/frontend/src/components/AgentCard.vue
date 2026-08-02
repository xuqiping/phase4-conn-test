<template>
  <div class="agent-card" @click="navigateToDetail">
    <!-- 顶部渐变色条 -->
    <div class="agent-card__gradient-bar" />

    <!-- 主体内容 -->
    <div class="agent-card__body">
      <!-- 图标 + 名称 -->
      <div class="agent-card__header">
        <div class="agent-card__avatar">
          <img
            v-if="agent.avatar"
            :src="agent.avatar"
            :alt="agent.name"
            class="agent-card__avatar-img"
          />
          <span v-else class="agent-card__avatar-placeholder">
            {{ agent.name.charAt(0).toUpperCase() }}
          </span>
        </div>
        <div class="agent-card__info">
          <h3 class="agent-card__name">{{ agent.name }}</h3>
          <span v-if="agent.groupName" class="agent-card__group">{{ agent.groupName }}</span>
        </div>
      </div>

      <!-- 描述 -->
      <p class="agent-card__desc">{{ agent.description || '暂无描述' }}</p>

      <!-- 底部统计 -->
      <div class="agent-card__footer">
        <div class="agent-card__stat">
          <n-icon size="14" :component="FlashOutline" />
          <span>{{ agent.skillCount }} 个技能</span>
        </div>
        <div
          class="agent-card__status"
          :class="{
            'agent-card__status--active': agent.status === 'ACTIVE',
            'agent-card__status--inactive': agent.status !== 'ACTIVE'
          }"
        >
          {{ agent.status === 'ACTIVE' ? '在线' : '离线' }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { NIcon } from 'naive-ui'
import { FlashOutline } from '@vicons/ionicons5'
import type { Agent } from '@/api/agent'

const props = defineProps<{
  agent: Agent
}>()

const router = useRouter()

function navigateToDetail() {
  router.push(`/agents/${props.agent.id}`)
}
</script>

<style lang="scss" scoped>
.agent-card {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  cursor: pointer;
  transition: all var(--duration-normal) var(--ease-in-out);

  &:hover {
    transform: translateY(-4px);
    border-color: rgba(var(--color-primary-rgb), 0.4);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4),
                0 0 20px rgba(var(--color-primary-rgb), 0.1);
  }
}

// 顶部渐变色条
.agent-card__gradient-bar {
  height: 4px;
  background: linear-gradient(
    90deg,
    var(--color-gradient-start),
    var(--color-gradient-end)
  );
}

// 主体内容
.agent-card__body {
  padding: var(--spacing-4);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}

// 头部：图标 + 名称
.agent-card__header {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
}

.agent-card__avatar {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-md);
  overflow: hidden;
  flex-shrink: 0;
}

.agent-card__avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.agent-card__avatar-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background: linear-gradient(
    135deg,
    var(--color-gradient-start),
    var(--color-gradient-end)
  );
  color: #fff;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.agent-card__info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow: hidden;
}

.agent-card__name {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin: 0;
}

.agent-card__group {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

// 描述
.agent-card__desc {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-base);
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

// 底部
.agent-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: var(--spacing-2);
  border-top: 1px solid var(--color-border-light);
}

.agent-card__stat {
  display: flex;
  align-items: center;
  gap: var(--spacing-1);
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.agent-card__status {
  font-size: var(--font-size-xs);
  padding: 2px 8px;
  border-radius: var(--radius-full);

  &--active {
    color: var(--color-success);
    background: rgba(74, 222, 128, 0.1);
  }

  &--inactive {
    color: var(--color-text-tertiary);
    background: var(--color-elevated);
  }
}
</style>
