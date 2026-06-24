<template>
  <aside class="component-palette">
    <div class="component-palette__header">
      <span class="component-palette__title">组件面板</span>
    </div>

    <div class="component-palette__search">
      <n-input v-model:value="searchKeyword" placeholder="搜索技能、Agent 或组件" clearable size="small">
        <template #prefix>
          <n-icon :component="SearchOutline" />
        </template>
      </n-input>
    </div>

    <div class="component-palette__content">
      <section class="component-palette__section">
        <div class="component-palette__section-title">流程控制</div>
        <div class="component-palette__items">
          <div class="palette-item palette-item--control" draggable="true" @dragstart="onDragStart($event, 'start', '开始')">
            <div class="palette-item__icon palette-item__icon--start">
              <n-icon size="16" color="#fff"><PlayOutline /></n-icon>
            </div>
            <span class="palette-item__name">开始</span>
          </div>
          <div class="palette-item palette-item--control" draggable="true" @dragstart="onDragStart($event, 'end', '结束')">
            <div class="palette-item__icon palette-item__icon--end">
              <n-icon size="16" color="#fff"><StopOutline /></n-icon>
            </div>
            <span class="palette-item__name">结束</span>
          </div>
        </div>
      </section>

      <section class="component-palette__section">
        <div class="component-palette__section-title">
          <n-icon size="14" :component="CloudUploadOutline" />
          <span>输入组件</span>
        </div>
        <div class="component-palette__items">
          <div
            v-for="item in filteredInputComponents"
            :key="item.inputType"
            class="palette-item"
            draggable="true"
            @dragstart="onDragStart($event, 'input', item.label, {
              inputKey: item.inputKey,
              inputType: item.inputType,
              required: item.required,
              placeholder: item.placeholder,
              accept: item.accept
            })"
          >
            <div class="palette-item__icon palette-item__icon--input">
              <n-icon size="12" color="#fff"><CloudUploadOutline /></n-icon>
            </div>
            <div class="palette-item__info">
              <span class="palette-item__name">{{ item.label }}</span>
              <span class="palette-item__desc">{{ item.description }}</span>
            </div>
          </div>
        </div>
      </section>

      <div v-if="loading" class="component-palette__loading">
        <n-spin size="small" />
      </div>

      <template v-else>
        <section v-if="filteredAgentRefs.length > 0" class="component-palette__section">
          <div class="component-palette__section-title">
            <n-icon size="14" :component="PeopleOutline" />
            <span>Agent 引用</span>
          </div>
          <div class="component-palette__items">
            <div
              v-for="agent in filteredAgentRefs"
              :key="agent.id"
              class="palette-item"
              draggable="true"
              @dragstart="onDragStart($event, 'agent_ref', agent.name, {
                agentId: agent.id,
                agentName: agent.name,
                description: agent.description,
                sourceType: 'AGENT'
              })"
            >
              <div class="palette-item__icon palette-item__icon--agent-ref">
                <n-icon size="12" color="#fff"><PeopleOutline /></n-icon>
              </div>
              <div class="palette-item__info">
                <span class="palette-item__name">{{ agent.name }}</span>
                <span v-if="agent.description" class="palette-item__desc">{{ agent.description }}</span>
              </div>
            </div>
          </div>
        </section>

        <section v-if="filteredWorkflowRefs.length > 0" class="component-palette__section">
          <div class="component-palette__section-title">
            <n-icon size="14" :component="GitBranchOutline" />
            <span>工作流引用</span>
          </div>
          <div class="component-palette__items">
            <div
              v-for="workflow in filteredWorkflowRefs"
              :key="workflow.id"
              class="palette-item"
              draggable="true"
              @dragstart="onDragStart($event, 'workflow_ref', workflow.name, {
                workflowId: workflow.id,
                workflowName: workflow.name,
                sourceType: 'WORKFLOW'
              })"
            >
              <div class="palette-item__icon palette-item__icon--workflow-ref">
                <n-icon size="12" color="#fff"><GitBranchOutline /></n-icon>
              </div>
              <div class="palette-item__info">
                <span class="palette-item__name">{{ workflow.name }}</span>
                <span v-if="workflow.description" class="palette-item__desc">{{ workflow.description }}</span>
              </div>
            </div>
          </div>
        </section>

        <section v-for="group in filteredGroups" :key="group.agentId" class="component-palette__section">
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
                agentName: group.agentName,
                description: skill.description
              })"
            >
              <div class="palette-item__icon" :style="{ background: group.color }">
                <n-icon size="12" color="#fff"><FlashOutline /></n-icon>
              </div>
              <div class="palette-item__info">
                <span class="palette-item__name">{{ skill.name }}</span>
                <span v-if="skill.description" class="palette-item__desc">{{ skill.description }}</span>
              </div>
            </div>
          </div>
        </section>

        <div v-if="filteredGroups.length === 0" class="component-palette__empty">
          <span>{{ searchKeyword ? '没有匹配的技能' : '暂无可用技能' }}</span>
        </div>
      </template>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { NInput, NIcon, NSpin } from 'naive-ui'
import { SearchOutline, PlayOutline, StopOutline, FlashOutline, PeopleOutline, GitBranchOutline, CloudUploadOutline } from '@vicons/ionicons5'
import { agentApi, type Agent, type AgentDetail } from '@/api/agent'
import { workflowApi } from '@/api/workflow'
import type { WorkflowListItem } from '@/types/workflow'

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

const searchKeyword = ref('')
const loading = ref(false)
const groups = ref<SkillGroup[]>([])
const agentRefs = ref<Agent[]>([])
const workflowRefs = ref<WorkflowListItem[]>([])

const inputComponents = [
  { label: '文本输入', inputKey: 'message', inputType: 'text', required: false, placeholder: '请输入文本', description: '单行文本输入' },
  { label: '提示词输入', inputKey: 'prompt', inputType: 'textarea', required: true, placeholder: '请输入提示词', description: '多行提示词输入' },
  { label: '图片输入', inputKey: 'image', inputType: 'image', required: false, placeholder: '', accept: 'image/*', description: '上传本地图片' },
  { label: '视频输入', inputKey: 'video', inputType: 'video', required: false, placeholder: '', accept: 'video/*', description: '上传本地视频' },
  { label: '文件输入', inputKey: 'file', inputType: 'file', required: false, placeholder: '', accept: '*/*', description: '上传本地文件' }
]

const agentColors = [
  '#4F7CFF', '#9333EA', '#F59E0B',
  '#10B981', '#EF4444', '#EC4899',
  '#6366F1', '#14B8A6'
]

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

const filteredAgentRefs = computed(() => {
  if (!searchKeyword.value) return agentRefs.value
  const keyword = searchKeyword.value.toLowerCase()
  return agentRefs.value.filter(agent =>
    agent.name.toLowerCase().includes(keyword) ||
    (agent.description && agent.description.toLowerCase().includes(keyword))
  )
})

const filteredWorkflowRefs = computed(() => {
  if (!searchKeyword.value) return workflowRefs.value
  const keyword = searchKeyword.value.toLowerCase()
  return workflowRefs.value.filter(workflow =>
    workflow.name.toLowerCase().includes(keyword) ||
    (workflow.description && workflow.description.toLowerCase().includes(keyword))
  )
})

const filteredInputComponents = computed(() => {
  if (!searchKeyword.value) return inputComponents
  const keyword = searchKeyword.value.toLowerCase()
  return inputComponents.filter(item =>
    item.label.toLowerCase().includes(keyword) ||
    item.description.toLowerCase().includes(keyword) ||
    item.inputKey.toLowerCase().includes(keyword)
  )
})

function onDragStart(event: DragEvent, nodeType: string, label: string, extraData?: Record<string, unknown>) {
  const payload = JSON.stringify({ nodeType, label, ...extraData })
  event.dataTransfer!.setData('application/vueflow', payload)
  event.dataTransfer!.effectAllowed = 'move'
}

async function loadAgentsWithSkills() {
  loading.value = true
  try {
    const res = await agentApi.listAgents()
    const agents = res.data.data
    agentRefs.value = agents
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
        // 单个 Agent 加载失败不影响其它组件。
      }
    }

    groups.value = groupList
    await loadWorkflowRefs()
  } finally {
    loading.value = false
  }
}

async function loadWorkflowRefs() {
  try {
    const res = await workflowApi.list()
    workflowRefs.value = res.data.data
  } catch {
    workflowRefs.value = []
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
  padding: var(--spacing-2);
  border-radius: var(--radius-base);
  cursor: grab;
  transition: background var(--duration-fast) var(--ease-in-out);
}

.palette-item:hover {
  background: var(--color-elevated);
}

.palette-item:active {
  cursor: grabbing;
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
  background: linear-gradient(135deg, #4ade80, #22c55e);
}

.palette-item__icon--end {
  background: linear-gradient(135deg, #f87171, #ef4444);
}

.palette-item__icon--agent-ref {
  background: #14b8a6;
}

.palette-item__icon--workflow-ref {
  background: #f59e0b;
}

.palette-item__icon--input {
  background: #38bdf8;
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
