<template>
  <div class="agent-hall">
    <!-- 顶部区域：标题 + 筛选 -->
    <div class="agent-hall__header">
      <div class="agent-hall__title-row">
        <h1 class="agent-hall__title">全部 Agent</h1>
        <span class="agent-hall__count">{{ filteredAgents.length }} 个</span>
        <div style="flex:1" />
        <n-button
          v-if="canCreate"
          type="primary"
          @click="showCreateModal = true"
        >
          + 新建 Agent
        </n-button>
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
      <div
        v-for="agent in filteredAgents"
        :key="agent.id"
        class="agent-card-wrap"
      >
        <AgentCard :agent="agent" />
        <div v-if="canManage" class="agent-card-wrap__actions">
          <n-button size="tiny" quaternary @click.stop="openEdit(agent)">编辑</n-button>
          <n-button
            v-if="agent.status === 'DRAFT'"
            size="tiny"
            quaternary
            type="success"
            @click.stop="publishAgent(agent)"
          >发布</n-button>
          <n-button
            v-if="agent.status === 'PUBLISHED'"
            size="tiny"
            quaternary
            type="warning"
            @click.stop="offlineAgent(agent)"
          >下线</n-button>
          <n-button size="tiny" quaternary type="error" @click.stop="confirmDelete(agent)">删除</n-button>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else class="agent-hall__empty">
      <n-empty description="没有找到匹配的 Agent">
        <template #extra>
          <n-button v-if="keyword || selectedGroupId" size="small" @click="clearFilters">
            清除筛选
          </n-button>
          <n-button v-if="canCreate" size="small" type="primary" @click="showCreateModal = true">
            创建第一个 Agent
          </n-button>
        </template>
      </n-empty>
    </div>

    <!-- 创建/编辑弹窗 -->
    <AgentFormModal
      v-model:show="showCreateModal"
      :groups="groups"
      :edit-data="editingAgent"
      @created="onDataChange"
      @updated="onDataChange"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { NSelect, NInput, NIcon, NSpin, NEmpty, NButton, useMessage, useDialog } from 'naive-ui'
import { SearchOutline } from '@vicons/ionicons5'
import { agentApi, type Agent, type AgentGroup } from '@/api/agent'
import { useAuthStore } from '@/stores/auth'
import AgentCard from '@/components/AgentCard.vue'
import AgentFormModal from '@/components/AgentFormModal.vue'

const authStore = useAuthStore()
const message = useMessage()
const dialog = useDialog()

const agents = ref<Agent[]>([])
const groups = ref<AgentGroup[]>([])
const loading = ref(true)
const selectedGroupId = ref<number | null>(null)
const keyword = ref('')
const showCreateModal = ref(false)
const editingAgent = ref<{ id: number; name: string; description: string | null; avatar: string | null; groupId: number | null } | null>(null)

const canCreate = computed(() => authStore.hasPermission('agent:create'))
const canManage = computed(() =>
  authStore.hasPermission('agent:update') || authStore.hasPermission('agent:delete') || authStore.hasPermission('agent:publish')
)

const groupOptions = computed(() =>
  groups.value.map((g) => ({
    label: `${g.name} (${g.agentCount})`,
    value: g.id
  }))
)

const filteredAgents = computed(() => {
  let result = agents.value
  if (selectedGroupId.value !== null) {
    result = result.filter((a) => a.groupId === selectedGroupId.value)
  }
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

function clearFilters() {
  selectedGroupId.value = null
  keyword.value = ''
}

function openEdit(agent: Agent) {
  editingAgent.value = { id: agent.id, name: agent.name, description: agent.description, avatar: agent.avatar, groupId: agent.groupId }
  showCreateModal.value = true
}

function confirmDelete(agent: Agent) {
  dialog.warning({
    title: '确认删除',
    content: `确定要删除Agent「${agent.name}」吗？此操作不可恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await agentApi.deleteAgent(agent.id)
        message.success('删除成功')
        await loadData()
      } catch {
        message.error('删除失败')
      }
    }
  })
}

async function publishAgent(agent: Agent) {
  try {
    await agentApi.updateAgentStatus(agent.id, 'PUBLISHED')
    message.success('发布成功')
    await loadData()
  } catch { message.error('发布失败') }
}

async function offlineAgent(agent: Agent) {
  try {
    await agentApi.updateAgentStatus(agent.id, 'OFFLINE')
    message.success('已下线')
    await loadData()
  } catch { message.error('操作失败') }
}

async function loadData() {
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
}

async function onDataChange() {
  editingAgent.value = null
  await loadData()
}

onMounted(loadData)
</script>

<style lang="scss" scoped>
.agent-hall {
  padding: var(--spacing-6);
  min-height: 100%;
}

.agent-hall__header {
  margin-bottom: var(--spacing-6);
}

.agent-hall__title-row {
  display: flex;
  align-items: center;
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

.agent-hall__group-select { width: 200px; }
.agent-hall__search { width: 280px; }

.agent-hall__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 300px;
}

.agent-hall__grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--spacing-4);

  @media (max-width: 1200px) { grid-template-columns: repeat(3, 1fr); }
  @media (max-width: 900px) { grid-template-columns: repeat(2, 1fr); }
  @media (max-width: 600px) { grid-template-columns: 1fr; }
}

.agent-hall__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 300px;
}

.agent-card-wrap {
  position: relative;

  &__actions {
    position: absolute;
    top: 8px;
    right: 8px;
    display: flex;
    gap: 2px;
    opacity: 0;
    transition: opacity var(--duration-fast);
    z-index: 2;
  }

  &:hover &__actions {
    opacity: 1;
  }
}
</style>
