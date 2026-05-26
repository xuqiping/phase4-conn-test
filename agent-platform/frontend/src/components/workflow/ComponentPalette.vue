<!-- ============================================================
  组件面板 — 左侧280px，搜索框+按Agent分组的技能列表+拖拽
  ============================================================ -->
<template>
  <div class="component-palette">
    <div class="component-palette__header">
      <span class="component-palette__title">组件面板</span>
    </div>

    <!-- 搜索框 -->
    <div class="component-palette__search">
      <n-input
        v-model:value="searchKeyword"
        placeholder="搜索技能..."
        clearable
        size="small"
      >
        <template #prefix>
          <n-icon :component="SearchOutline" />
        </template>
      </n-input>
    </div>

    <div class="component-palette__content">
      <!-- 流程控制节点 -->
      <div class="component-palette__section">
        <div class="component-palette__section-title">流程控制</div>
        <div class="component-palette__items">
          <div
            class="palette-item palette-item--control"
            draggable="true"
            @dragstart="onDragStart($event, 'start', '开始')"
          >
            <div class="palette-item__icon palette-item__icon--start">
              <n-icon size="16" color="#fff">
                <PlayOutline />
              </n-icon>
            </div>
            <span class="palette-item__name">开始</span>
          </div>
          <div
            class="palette-item palette-item--control"
            draggable="true"
            @dragstart="onDragStart($event, 'end', '结束')"
          >
            <div class="palette-item__icon palette-item__icon--end">
              <n-icon size="16" color="#fff">
                <StopOutline />
              </n-icon>
            </div>
            <span class="palette-item__name">结束</span>
          </div>
        </div>
      </div>

      <!-- 按Agent分组的技能列表 -->
      <div v-if="loading" class="component-palette__loading">
        <n-spin size="small" />
      </div>
      <template v-else>
        <div
          v-for="group in filteredGroups"
          :key="group.agentId"
          class="component-palette__section"
        >
          <div class="component-palette__section-title">
            <n-icon size="14" :component="FlashOutline" />
            <span>{{ group.agentName }}</span>
          </div>
          <div class="component-palette__items">
            <div
              v-for="skill in group.skills"
              :key="skill.id"
              class="palette-item"
              draggable="true"
              @dragstart="onDragStart($event, 'skill', skill.name, {
                skillId: skill.id,
                agentId: group.agentId,
                agentName: group.agentName
              })"
            >
              <div class="palette-item__icon" :style="{ background: group.color }">
                <n-icon size="12" color="#fff">
                  <FlashOutline />
                </n-icon>
              </div>
              <div class="palette-item__info">
                <span class="palette-item__name">{{ skill.name }}</span>
                <span v-if="skill.description" class="palette-item__desc">{{ skill.description }}</span>
              </div>
            </div>
          </div>
        </div>
        <div v-if="filteredGroups.length === 0" class="component-palette__empty">
          <span>{{ searchKeyword ? '未找到匹配技能' : '暂无可用技能' }}</span>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { NInput, NIcon, NSpin } from 'naive-ui'
import { SearchOutline, PlayOutline, StopOutline, FlashOutline } from '@vicons/ionicons5'
import { agentApi, type AgentDetail } from '@/api/agent'

/** 技能分组 */
interface SkillGroup {
  agentId: number
  agentName: string
  color: string
  skills: Array<{
    id: number
    name: string
    description: string | null
  }>
}

const emit = defineEmits<{
  (e: 'drag-start', event: DragEvent, nodeType: string, label: string, data?: Record<string, unknown>): void
}>()

const searchKeyword = ref('')
const loading = ref(false)
const groups = ref<SkillGroup[]>([])

/** Agent主题色列表 */
const agentColors = [
  '#4F7CFF', '#9333EA', '#F59E0B',
  '#10B981', '#EF4444', '#EC4899',
  '#6366F1', '#14B8A6'
]

/** 根据关键词过滤分组 */
const filteredGroups = computed(() => {
  if (!searchKeyword.value) return groups.value
  const keyword = searchKeyword.value.toLowerCase()
  return groups.value
    .map(group => ({
      ...group,
      skills: group.skills.filter(
        s => s.name.toLowerCase().includes(keyword) ||
             (s.description && s.description.toLowerCase().includes(keyword))
      )
    }))
    .filter(group => group.skills.length > 0)
})

/** 拖拽开始 */
function onDragStart(event: DragEvent, nodeType: string, label: string, extraData?: Record<string, unknown>) {
  const payload = JSON.stringify({ nodeType, label, ...extraData })
  event.dataTransfer!.setData('application/vueflow', payload)
  event.dataTransfer!.effectAllowed = 'move'
}

/** 加载Agent及其技能 */
async function loadAgentsWithSkills() {
  loading.value = true
  try {
    const res = await agentApi.listAgents()
    const agents = res.data.data
    const groupList: SkillGroup[] = []

    for (let i = 0; i < agents.length; i++) {
      const agent = agents[i]
      try {
        const detailRes = await agentApi.getAgentDetail(agent.id)
        const detail: AgentDetail = detailRes.data.data
        if (detail.skills && detail.skills.length > 0) {
          groupList.push({
            agentId: detail.id,
            agentName: detail.name,
            color: agentColors[i % agentColors.length],
            skills: detail.skills.map(s => ({
              id: s.id,
              name: s.name,
              description: s.description
            }))
          })
        }
      } catch {
        // 单个Agent加载失败不影响其他
      }
    }

    groups.value = groupList
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadAgentsWithSkills()
})
</script>

<style lang="scss" scoped>
.component-palette {
  width: 280px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--color-surface);
  border-right: 1px solid var(--color-border);
  overflow: hidden;
}

.component-palette__header {
  padding: var(--spacing-3) var(--spacing-4);
  border-bottom: 1px solid var(--color-border);
}

.component-palette__title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.component-palette__search {
  padding: var(--spacing-2) var(--spacing-3);
  border-bottom: 1px solid var(--color-border-light);
}

.component-palette__content {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-2) 0;
}

.component-palette__loading,
.component-palette__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-8);
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}

.component-palette__section {
  margin-bottom: var(--spacing-2);
}

.component-palette__section-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-1);
  padding: var(--spacing-1) var(--spacing-3);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.component-palette__items {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0 var(--spacing-2);
}

.palette-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: var(--spacing-2) var(--spacing-2);
  border-radius: var(--radius-base);
  cursor: grab;
  transition: background var(--duration-fast) var(--ease-in-out);

  &:hover {
    background: var(--color-elevated);
  }

  &:active {
    cursor: grabbing;
  }
}

.palette-item--control {
  .palette-item__name {
    font-size: var(--font-size-sm);
    color: var(--color-text-primary);
  }
}

.palette-item__icon {
  width: 24px;
  height: 24px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: var(--color-primary);
}

.palette-item__icon--start {
  background: linear-gradient(135deg, #4ADE80, #22C55E);
}

.palette-item__icon--end {
  background: linear-gradient(135deg, #F87171, #EF4444);
}

.palette-item__info {
  display: flex;
  flex-direction: column;
  gap: 1px;
  overflow: hidden;
}

.palette-item__name {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.palette-item__desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
