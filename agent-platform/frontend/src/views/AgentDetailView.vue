<template>
  <div class="agent-detail">
    <!-- 面包屑 -->
    <div class="agent-detail__breadcrumb">
      <router-link to="/agents" class="agent-detail__breadcrumb-link">
        Agent大厅
      </router-link>
      <n-icon size="14" :component="ChevronForwardOutline" />
      <span class="agent-detail__breadcrumb-current">{{ agentDetail?.name || '加载中...' }}</span>
    </div>

    <!-- 加载状态 -->
    <div v-if="loading" class="agent-detail__loading">
      <n-spin size="large" />
    </div>

    <template v-else-if="agentDetail">
      <!-- Agent头部区域 -->
      <div class="agent-detail__hero">
        <div class="agent-detail__hero-gradient" />
        <div class="agent-detail__hero-content">
          <div class="agent-detail__hero-avatar">
            <img
              v-if="agentDetail.avatar"
              :src="agentDetail.avatar"
              :alt="agentDetail.name"
              class="agent-detail__hero-avatar-img"
            />
            <span v-else class="agent-detail__hero-avatar-placeholder">
              {{ agentDetail.name.charAt(0).toUpperCase() }}
            </span>
          </div>
          <div class="agent-detail__hero-info">
            <div class="agent-detail__hero-name-row">
              <h1 class="agent-detail__hero-name">{{ agentDetail.name }}</h1>
              <div
                class="agent-detail__hero-status"
                :class="{
                  'agent-detail__hero-status--active': agentDetail.status === 'ACTIVE'
                }"
              >
                {{ agentDetail.status === 'ACTIVE' ? '在线' : '离线' }}
              </div>
            </div>
            <p class="agent-detail__hero-desc">{{ agentDetail.description || '暂无描述' }}</p>
            <div class="agent-detail__hero-meta">
              <span v-if="agentDetail.groupName" class="agent-detail__hero-tag">
                <n-icon size="12" :component="FolderOutline" />
                {{ agentDetail.groupName }}
              </span>
              <span class="agent-detail__hero-tag">
                <n-icon size="12" :component="FlashOutline" />
                {{ agentDetail.skills.length }} 个技能
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- 左右分栏布局 -->
      <div class="agent-detail__body">
        <!-- 左侧：技能列表 -->
        <div class="agent-detail__skill-list">
          <div class="agent-detail__skill-list-header">
            <h3>技能列表</h3>
          </div>
          <SkillList
            :skills="agentDetail.skills"
            :selected-skill-id="selectedSkillId"
            @select="onSkillSelect"
          />
        </div>

        <!-- 右侧：技能详情 -->
        <div class="agent-detail__skill-detail">
          <div v-if="skillLoading" class="agent-detail__skill-loading">
            <n-spin size="medium" />
          </div>
          <SkillDetail v-else :skill="selectedSkill" />
        </div>
      </div>
    </template>

    <!-- Agent不存在 -->
    <div v-else class="agent-detail__not-found">
      <n-empty description="Agent不存在或已被删除">
        <template #extra>
          <n-button @click="$router.push('/agents')">返回Agent大厅</n-button>
        </template>
      </n-empty>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { NIcon, NSpin, NEmpty, NButton } from 'naive-ui'
import {
  ChevronForwardOutline,
  FolderOutline,
  FlashOutline
} from '@vicons/ionicons5'
import { agentApi } from '@/api/agent'
import type { AgentDetail, SkillDetail as SkillDetailType } from '@/api/agent'
import SkillList from '@/components/SkillList.vue'
import SkillDetail from '@/components/SkillDetail.vue'

const route = useRoute()

// === 状态 ===
const agentDetail = ref<AgentDetail | null>(null)
const selectedSkill = ref<SkillDetailType | null>(null)
const selectedSkillId = ref<number | null>(null)
const loading = ref(true)
const skillLoading = ref(false)

// === 方法 ===

async function onSkillSelect(skillId: number) {
  selectedSkillId.value = skillId
  skillLoading.value = true
  try {
    const res = await agentApi.getSkillDetail(skillId)
    selectedSkill.value = res.data.data
  } catch (e) {
    console.error('加载技能详情失败:', e)
    selectedSkill.value = null
  } finally {
    skillLoading.value = false
  }
}

// === 数据加载 ===

onMounted(async () => {
  const agentId = Number(route.params.id)
  if (!agentId) {
    loading.value = false
    return
  }

  try {
    const res = await agentApi.getAgentDetail(agentId)
    agentDetail.value = res.data.data

    // 默认选中第一个技能
    if (agentDetail.value?.skills?.length > 0) {
      const firstSkill = agentDetail.value.skills[0]
      await onSkillSelect(firstSkill.id)
    }
  } catch (e) {
    console.error('加载Agent详情失败:', e)
  } finally {
    loading.value = false
  }
})
</script>

<style lang="scss" scoped>
.agent-detail {
  padding: var(--spacing-6);
  min-height: 100%;
}

// 面包屑
.agent-detail__breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--spacing-1);
  font-size: var(--font-size-sm);
  margin-bottom: var(--spacing-4);
}

.agent-detail__breadcrumb-link {
  color: var(--color-text-tertiary);
  text-decoration: none;
  transition: color var(--duration-instant) var(--ease-in-out);

  &:hover {
    color: var(--color-primary);
  }
}

.agent-detail__breadcrumb-current {
  color: var(--color-text-secondary);
}

// 加载状态
.agent-detail__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 400px;
}

// Agent头部区域
.agent-detail__hero {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  margin-bottom: var(--spacing-4);
}

.agent-detail__hero-gradient {
  height: 4px;
  background: linear-gradient(
    90deg,
    var(--color-gradient-start),
    var(--color-gradient-end)
  );
}

.agent-detail__hero-content {
  padding: var(--spacing-5);
  display: flex;
  gap: var(--spacing-5);
}

.agent-detail__hero-avatar {
  width: 64px;
  height: 64px;
  border-radius: var(--radius-lg);
  overflow: hidden;
  flex-shrink: 0;
}

.agent-detail__hero-avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.agent-detail__hero-avatar-placeholder {
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
  font-size: var(--font-size-3xl);
  font-weight: var(--font-weight-bold);
}

.agent-detail__hero-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}

.agent-detail__hero-name-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
}

.agent-detail__hero-name {
  font-size: var(--font-size-2xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0;
}

.agent-detail__hero-status {
  font-size: var(--font-size-xs);
  padding: 2px 8px;
  border-radius: var(--radius-full);
  color: var(--color-text-tertiary);
  background: var(--color-elevated);

  &--active {
    color: var(--color-success);
    background: rgba(74, 222, 128, 0.1);
  }
}

.agent-detail__hero-desc {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-base);
  margin: 0;
}

.agent-detail__hero-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
  margin-top: var(--spacing-1);
}

.agent-detail__hero-tag {
  display: flex;
  align-items: center;
  gap: var(--spacing-1);
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  background: var(--color-elevated);
  padding: 2px 8px;
  border-radius: var(--radius-full);
}

// 左右分栏
.agent-detail__body {
  display: flex;
  gap: var(--spacing-4);
  min-height: 400px;
}

.agent-detail__skill-list {
  width: 300px;
  flex-shrink: 0;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.agent-detail__skill-list-header {
  padding: var(--spacing-4);
  border-bottom: 1px solid var(--color-border-light);

  h3 {
    margin: 0;
    font-size: var(--font-size-base);
    font-weight: var(--font-weight-semibold);
    color: var(--color-text-primary);
  }
}

.agent-detail__skill-detail {
  flex: 1;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: auto;
}

.agent-detail__skill-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 300px;
}

// Agent不存在
.agent-detail__not-found {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 400px;
}

// 响应式
@media (max-width: 768px) {
  .agent-detail__body {
    flex-direction: column;
  }

  .agent-detail__skill-list {
    width: 100%;
  }
}
</style>
