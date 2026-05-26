<template>
  <div class="agent-hall">
    <!-- 顶部区域：标题 + 筛选 -->
    <div class="agent-hall__header">
      <div class="agent-hall__title-row">
        <h1 class="agent-hall__title">全部 Agent</h1>
        <span class="agent-hall__count">{{ filteredAgents.length }} 个</span>
      </div>
      <div class="agent-hall__filters">
        <n-select
          v-model:value="selectedGroupId"
          :options="groupOptions"
          placeholder="全部分组"
          clearable
          class="agent-hall__group-select"
        />
        <n-input
          v-model:value="keyword"
          placeholder="搜索 Agent..."
          clearable
          class="agent-hall__search"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>
      </div>
    </div>

    <!-- 加载状态 -->
    <div v-if="loading" class="agent-hall__loading">
      <n-spin size="large" />
    </div>

    <!-- Agent卡片网格 -->
    <div v-else-if="filteredAgents.length > 0" class="agent-hall__grid">
      <AgentCard
        v-for="agent in filteredAgents"
        :key="agent.id"
        :agent="agent"
      />
    </div>

    <!-- 空状态 -->
    <div v-else class="agent-hall__empty">
      <n-empty description="没有找到匹配的 Agent">
        <template #extra>
          <n-button v-if="keyword || selectedGroupId" size="small" @click="clearFilters">
            清除筛选
          </n-button>
        </template>
      </n-empty>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { NSelect, NInput, NIcon, NSpin, NEmpty, NButton } from 'naive-ui'
import { SearchOutline } from '@vicons/ionicons5'
import { agentApi } from '@/api/agent'
import type { Agent, AgentGroup } from '@/api/agent'
import AgentCard from '@/components/AgentCard.vue'

// === 状态 ===
const agents = ref<Agent[]>([])
const groups = ref<AgentGroup[]>([])
const loading = ref(true)
const selectedGroupId = ref<number | null>(null)
const keyword = ref('')

// === 计算属性 ===

/** 分组下拉选项 */
const groupOptions = computed(() =>
  groups.value.map((g) => ({
    label: `${g.name} (${g.agentCount})`,
    value: g.id
  }))
)

/** 筛选后的Agent列表 */
const filteredAgents = computed(() => {
  let result = agents.value

  // 按分组筛选
  if (selectedGroupId.value !== null) {
    result = result.filter((a) => a.groupId === selectedGroupId.value)
  }

  // 按关键词搜索
  if (keyword.value.trim()) {
    const kw = keyword.value.trim().toLowerCase()
    result = result.filter(
      (a) =>
        a.name.toLowerCase().includes(kw) ||
        (a.description && a.description.toLowerCase().includes(kw))
    )
  }

  return result
})

// === 方法 ===

function clearFilters() {
  selectedGroupId.value = null
  keyword.value = ''
}

// === 数据加载 ===

onMounted(async () => {
  try {
    const [agentsRes, groupsRes] = await Promise.all([
      agentApi.listAgents(),
      agentApi.getGroups()
    ])
    agents.value = agentsRes.data.data || []
    groups.value = groupsRes.data.data || []
  } catch (e) {
    console.error('加载Agent数据失败:', e)
  } finally {
    loading.value = false
  }
})
</script>

<style lang="scss" scoped>
.agent-hall {
  padding: var(--spacing-6);
  min-height: 100%;
}

// 顶部区域
.agent-hall__header {
  margin-bottom: var(--spacing-6);
}

.agent-hall__title-row {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-2);
  margin-bottom: var(--spacing-4);
}

.agent-hall__title {
  font-size: var(--font-size-2xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0;
}

.agent-hall__count {
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
}

.agent-hall__filters {
  display: flex;
  gap: var(--spacing-3);
}

.agent-hall__group-select {
  width: 200px;
}

.agent-hall__search {
  width: 280px;
}

// 加载状态
.agent-hall__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 300px;
}

// Agent卡片网格
.agent-hall__grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--spacing-4);

  @media (max-width: 1200px) {
    grid-template-columns: repeat(3, 1fr);
  }

  @media (max-width: 900px) {
    grid-template-columns: repeat(2, 1fr);
  }

  @media (max-width: 600px) {
    grid-template-columns: 1fr;
  }
}

// 空状态
.agent-hall__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 300px;
}
</style>
