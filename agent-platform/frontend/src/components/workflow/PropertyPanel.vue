<template>
  <aside class="property-panel">
    <div class="property-panel__header">
      <span class="property-panel__title">属性面板</span>
    </div>

    <div v-if="!selectedNode" class="property-panel__empty">
      <n-icon size="32" :component="InformationCircleOutline" color="var(--color-text-tertiary)" />
      <span>选择节点后编辑配置</span>
    </div>

    <div v-else class="property-panel__content">
      <div class="property-panel__type-badge">
        <div class="property-panel__type-icon" :class="`property-panel__type-icon--${selectedNode.type}`">
          <n-icon size="16" color="#fff">
            <component :is="typeIcon" />
          </n-icon>
        </div>
        <span class="property-panel__type-name">{{ typeName }}</span>
      </div>

      <n-divider style="margin: var(--spacing-3) 0" />

      <section class="property-panel__section">
        <div class="property-panel__section-title">基础信息</div>
        <div class="property-panel__field">
          <label class="property-panel__label">节点 ID</label>
          <span class="property-panel__value property-panel__value--mono">{{ selectedNode.id }}</span>
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">节点名称</label>
          <n-input
            :value="selectedNode.data.label"
            size="small"
            placeholder="请输入节点名称"
            @update:value="(val: string) => updateNodeData('label', val)"
          />
        </div>
        <div v-if="supportsAlias" class="property-panel__field">
          <label class="property-panel__label">稳定节点别名</label>
          <n-input
            :value="selectedNode.data.nodeAlias || suggestedAlias"
            size="small"
            placeholder="如 summaryNode"
            @update:value="(val: string) => updateNodeData('nodeAlias', val)"
          />
          <span class="property-panel__hint">传参时使用 {{ aliasExample }}，别名只能包含字母、数字、下划线，且以字母开头。</span>
        </div>
        <div v-if="descriptionVisible" class="property-panel__field">
          <label class="property-panel__label">描述</label>
          <n-input
            :value="selectedNode.data.description || ''"
            type="textarea"
            size="small"
            placeholder="请输入节点描述"
            :rows="3"
            :disabled="!descriptionEditable"
            @update:value="(val: string) => updateNodeData('description', val)"
          />
        </div>
      </section>

      <section v-if="selectedNode.type === 'agent_ref'" class="property-panel__section">
        <div class="property-panel__section-title">Agent 引用</div>
        <div class="property-panel__field">
          <label class="property-panel__label">Agent</label>
          <n-select
            :value="selectedNode.data.agentId"
            :options="agentOptions"
            size="small"
            placeholder="选择 Agent"
            filterable
            @update:value="onAgentSelected"
          />
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">Agent ID</label>
          <span class="property-panel__value property-panel__value--mono">{{ selectedNode.data.agentId || '-' }}</span>
        </div>
      </section>

      <section v-if="selectedNode.type === 'workflow_ref'" class="property-panel__section">
        <div class="property-panel__section-title">工作流引用</div>
        <div class="property-panel__field">
          <label class="property-panel__label">工作流</label>
          <n-select
            :value="selectedNode.data.workflowId"
            :options="workflowOptions"
            size="small"
            placeholder="选择工作流"
            filterable
            @update:value="onWorkflowSelected"
          />
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">工作流 ID</label>
          <span class="property-panel__value property-panel__value--mono">{{ selectedNode.data.workflowId || '-' }}</span>
        </div>
      </section>

      <section v-if="selectedNode.type === 'retrieval'" class="property-panel__section">
        <div class="property-panel__section-title">知识检索</div>
        <div class="property-panel__field">
          <label class="property-panel__label">知识库</label>
          <n-select
            :value="selectedNode.data.kbIds && selectedNode.data.kbIds.length > 0 ? selectedNode.data.kbIds : (selectedNode.data.kbId ? [selectedNode.data.kbId] : [])"
            :options="knowledgeBaseOptions"
            size="small"
            placeholder="选择知识库（可多选）"
            multiple
            filterable
            @update:value="(val: number[]) => updateNodeData('kbIds', val)"
          />
        </div>
        <div class="property-panel__field property-panel__field--prompt">
          <label class="property-panel__label">查询</label>
          <n-input
            :value="selectedNode.data.query || ''"
            type="textarea"
            size="small"
            :rows="3"
            placeholder="输入检索查询，按 / 插入上游变量（支持 {{上游别名.输出变量}} 模板）"
            @input="onQueryInput"
            @keyup="onQueryKeyup"
          />
          <div v-if="queryMenuVisible" class="property-panel__variables">
            <button
              v-for="variable in availableVariables"
              :key="`${variable.sourceNodeId}-${variable.reference}`"
              class="property-panel__variable"
              type="button"
              @click="insertQueryVariable(variable.reference)"
            >
              <span class="property-panel__variable-key">{{ variable.reference }}</span>
              <span class="property-panel__variable-source">{{ variable.sourceLabel }}</span>
            </button>
            <span v-if="availableVariables.length === 0" class="property-panel__variable-empty">
              暂无可用上游变量
            </span>
          </div>
          <span class="property-panel__hint">检索词会被模板渲染：引用上游节点的输出（如 {{ aliasExampleForQuery }}），渲染失败或留空则回退到上游输入。</span>
        </div>
        <div class="property-panel__notice">
          运行时按节点绑定知识库 ∩ 当前用户可见集检索证据，证据文本注入下游节点（v6 §2.4 检索节点回调）。需工作流开启记忆/RAG 模式。
        </div>
      </section>

      <section v-if="selectedNode.type === 'input' || selectedNode.type === 'start'" class="property-panel__section">
        <div class="property-panel__section-title">输入组件</div>
        <div class="property-panel__field">
          <label class="property-panel__label">字段名</label>
          <n-input
            :value="selectedNode.data.inputKey || ''"
            size="small"
            placeholder="message"
            @update:value="(val: string) => updateNodeData('inputKey', val)"
          />
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">输入类型</label>
          <n-select
            :value="selectedNode.data.inputType || 'text'"
            :options="inputTypeOptions"
            size="small"
            @update:value="(val: string) => updateNodeData('inputType', val)"
          />
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">输入值</label>
          <n-input
            :value="selectedNode.data.value || selectedNode.data.defaultValue || ''"
            :type="selectedNode.data.inputType === 'textarea' ? 'textarea' : 'text'"
            size="small"
            :rows="selectedNode.data.inputType === 'textarea' ? 5 : 1"
            :placeholder="selectedNode.data.placeholder || '请输入值'"
            @update:value="(val: string) => updateNodeData('value', val)"
          />
        </div>
      </section>

      <section v-if="selectedNode.type === 'human_input'" class="property-panel__section">
        <div class="property-panel__section-title">人机交互</div>
        <div class="property-panel__notice">
          运行至此节点会暂停并向用户提问；用户回复后答案绑定到下方变量，工作流继续。
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">答案变量名 (inputKey)</label>
          <n-input
            :value="selectedNode.data.inputKey || ''"
            size="small"
            placeholder="如 user_name"
            @update:value="(val: string) => updateNodeData('inputKey', val)"
          />
          <span class="property-panel__hint">后端必填。答案绑定为该变量，下游节点可用模板 {{ aliasExampleForInput }} 引用。</span>
        </div>
        <div class="property-panel__field property-panel__field--prompt">
          <label class="property-panel__label">问题模板</label>
          <n-input
            :value="selectedNode.data.questionTemplate || ''"
            type="textarea"
            size="small"
            :rows="3"
            placeholder="向用户提的问题，支持 {{别名.字段}} 模板变量"
            @update:value="(val: string) => updateNodeData('questionTemplate', val)"
          />
          <span class="property-panel__hint">运行时渲染后作为助手消息流出；留空则暂停但用户看不到问题。</span>
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">输入类型</label>
          <n-select
            :value="selectedNode.data.inputType || 'text'"
            :options="humanInputTypeOptions"
            size="small"
            @update:value="(val: string) => updateNodeData('inputType', val)"
          />
        </div>
        <div v-if="(selectedNode.data.inputType || 'text') === 'select'" class="property-panel__field">
          <label class="property-panel__label">选项</label>
          <n-dynamic-tags
            :value="Array.isArray(selectedNode.data.options) ? selectedNode.data.options : []"
            size="small"
            :max="20"
            @update:value="(val: Array<string | number>) => updateNodeData('options', val.map(String))"
          />
          <span class="property-panel__hint">仅 select 型生效；逐项添加回车确认。对话 UI 会把这些选项渲染为可点按钮。</span>
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">必填</label>
          <n-switch
            :value="selectedNode.data.required !== false"
            size="small"
            @update:value="(val: boolean) => updateNodeData('required', val)"
          />
          <span class="property-panel__hint">透传到对话侧；当前运行时不强制校验，仅作提示。</span>
        </div>
      </section>

      <section v-if="selectedNode.type === 'skill'" class="property-panel__section">
        <div class="property-panel__section-title">能力信息</div>
        <div class="property-panel__field">
          <label class="property-panel__label">所属 Agent</label>
          <span class="property-panel__value">{{ selectedNode.data.agentName || '未知' }}</span>
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">技能 ID</label>
          <span class="property-panel__value property-panel__value--mono">{{ selectedNode.data.skillId || '-' }}</span>
        </div>
      </section>

      <section v-if="selectedNode.type === 'skill'" class="property-panel__section">
        <div class="property-panel__section-title">输入映射</div>
        <div class="property-panel__notice">
          左侧是能力声明的固定入参，右侧绑定上游变量或手填值，例如 {{ mappingExample }}。
        </div>
        <div
          v-for="(param, index) in inputParams"
          :key="param.key"
          class="property-panel__mapping-row"
        >
          <div class="property-panel__param">
            <div class="property-panel__param-head">
              <span class="property-panel__param-key">{{ param.key }}</span>
              <span v-if="param.required" class="property-panel__required">必填</span>
            </div>
            <span v-if="param.label" class="property-panel__param-label">{{ param.label }}</span>
            <span v-if="param.description" class="property-panel__param-desc">{{ param.description }}</span>
          </div>
          <div class="property-panel__mapping-template">
            <n-input
              :value="mappingValue(param.key)"
              size="small"
              placeholder="输入 / 选择上游变量"
              @input="(val: string) => onMappingInput(param.key, val)"
              @keyup="(event: KeyboardEvent) => onMappingKeyup(index, event)"
            />
            <div v-if="mappingMenuIndex === index" class="property-panel__variables">
              <button
                v-for="variable in availableVariables"
                :key="`${variable.sourceNodeId}-${variable.reference}`"
                class="property-panel__variable"
                type="button"
                @click="insertMappingVariable(param.key, variable.reference)"
              >
                <span class="property-panel__variable-key">{{ variable.reference }}</span>
                <span class="property-panel__variable-source">{{ variable.sourceLabel }}</span>
              </button>
              <span v-if="availableVariables.length === 0" class="property-panel__variable-empty">
                暂无可用上游变量
              </span>
            </div>
          </div>
        </div>
        <div v-if="inputParams.length === 0" class="property-panel__notice">
          该能力还没有声明公开入参规范，请由 Agent 拥有者或管理员在能力配置中维护 inputParams。
        </div>
      </section>

      <section v-if="selectedNode.type === 'skill' && promptConfigVisible" class="property-panel__section">
        <div class="property-panel__section-title">提示词配置</div>
        <div v-if="!promptConfigEditable" class="property-panel__notice">
          只有管理员或 Agent 拥有者可以查看并修改系统提示词、用户提示词和模型参数。
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">系统提示词</label>
          <n-input
            :value="selectedNode.data.systemPrompt || ''"
            type="textarea"
            size="small"
            placeholder="定义模型的角色、边界和回答原则"
            :disabled="!promptConfigEditable"
            :rows="4"
            @update:value="(val: string) => updateNodeData('systemPrompt', val)"
          />
        </div>
        <div class="property-panel__field property-panel__field--prompt">
          <label class="property-panel__label">用户提示词</label>
          <n-input
            :value="selectedNode.data.promptTemplate || ''"
            type="textarea"
            size="small"
            placeholder="输入 / 插入上游变量"
            :disabled="!promptConfigEditable"
            :rows="7"
            @input="onPromptInput"
            @keyup="onPromptKeyup"
          />
          <div v-if="showVariableMenu" class="property-panel__variables">
            <button
              v-for="variable in promptVariables"
              :key="`${variable.sourceNodeId}-${variable.reference}`"
              class="property-panel__variable"
              type="button"
              @click="insertPromptVariable(variable.reference)"
            >
              <span class="property-panel__variable-key">{{ variable.reference }}</span>
              <span class="property-panel__variable-source">{{ variable.sourceLabel }}</span>
            </button>
            <span v-if="promptVariables.length === 0" class="property-panel__variable-empty">
              暂无可用上游变量
            </span>
          </div>
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">输出变量名</label>
          <n-input
            :value="selectedNode.data.outputKey || ''"
            size="small"
            placeholder="summary"
            :disabled="!promptConfigEditable"
            @update:value="(val: string) => updateNodeData('outputKey', val)"
          />
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">模型</label>
          <n-input
            :value="selectedNode.data.model || ''"
            size="small"
            placeholder="doubao-seed-2.0-code"
            :disabled="!promptConfigEditable"
            @update:value="(val: string) => updateNodeData('model', val)"
          />
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">温度</label>
          <n-input-number
            :value="selectedNode.data.temperature"
            size="small"
            :min="0"
            :max="2"
            :step="0.1"
            :disabled="!promptConfigEditable"
            @update:value="(val: number | null) => updateNodeData('temperature', val ?? 0.7)"
          />
        </div>
      </section>

      <section v-else-if="selectedNode.type === 'skill'" class="property-panel__section">
        <div class="property-panel__section-title">提示词配置</div>
        <div class="property-panel__notice">
          当前账号没有提示词查看权限。可通过输入映射给该能力传参，但不会暴露系统提示词和用户提示词。
        </div>
      </section>

      <section class="property-panel__section">
        <div class="property-panel__section-title">位置</div>
        <div class="property-panel__field">
          <label class="property-panel__label">X</label>
          <span class="property-panel__value property-panel__value--mono">{{ Math.round(selectedNode.position?.x || 0) }}</span>
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">Y</label>
          <span class="property-panel__value property-panel__value--mono">{{ Math.round(selectedNode.position?.y || 0) }}</span>
        </div>
      </section>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { NInput, NDivider, NIcon, NSelect, NInputNumber, NSwitch, NDynamicTags } from 'naive-ui'
import {
  InformationCircleOutline,
  PlayOutline,
  StopOutline,
  FlashOutline,
  PeopleOutline,
  GitBranchOutline,
  CloudUploadOutline,
  SearchOutline,
  ChatbubbleEllipsesOutline
} from '@vicons/ionicons5'
import type { Component } from 'vue'
import { agentApi, type Agent } from '@/api/agent'
import { workflowApi } from '@/api/workflow'
import { knowledgeApi, type KnowledgeBase } from '@/api/knowledge'
import type { SkillInputParam, WorkflowEdge, WorkflowListItem, WorkflowNode } from '@/types/workflow'
import { collectAvailableVariables } from '@/utils/workflowRuntime'
import { parseLlmStepConfig, parseSkillInputParams } from '@/utils/skillConfigForm'

const props = defineProps<{
  selectedNode: WorkflowNode | null
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  editable?: boolean
}>()

const emit = defineEmits<{
  (e: 'update-node-data', nodeId: string, key: string, value: string | number | boolean | number[] | string[] | Record<string, string> | SkillInputParam[]): void
}>()

const agents = ref<Agent[]>([])
const workflows = ref<WorkflowListItem[]>([])
const knowledgeBases = ref<KnowledgeBase[]>([])
const showVariableMenu = ref(false)
const promptSlashIndex = ref<number | null>(null)
const mappingMenuIndex = ref<number | null>(null)
const mappingSlashIndex = ref<number | null>(null)
const queryMenuVisible = ref(false)
const querySlashIndex = ref<number | null>(null)

const typeName = computed(() => {
  const typeMap: Record<string, string> = {
    start: '开始',
    end: '结束',
    input: '输入',
    skill: '能力',
    agent_ref: 'Agent 引用',
    workflow_ref: '工作流引用',
    retrieval: '知识检索',
    human_input: '人机交互提问'
  }
  return typeMap[props.selectedNode?.type || ''] || '节点'
})

const typeIcon = computed(() => {
  const iconMap: Record<string, Component> = {
    start: PlayOutline,
    end: StopOutline,
    input: CloudUploadOutline,
    skill: FlashOutline,
    agent_ref: PeopleOutline,
    workflow_ref: GitBranchOutline,
    retrieval: SearchOutline,
    human_input: ChatbubbleEllipsesOutline
  }
  return iconMap[props.selectedNode?.type || ''] || FlashOutline
})

const supportsAlias = computed(() => ['start', 'input', 'skill', 'agent_ref', 'workflow_ref', 'retrieval'].includes(props.selectedNode?.type || ''))

const inputParams = computed<SkillInputParam[]>(() => {
  const params = props.selectedNode?.data.inputParams
  return Array.isArray(params) ? params.filter(param => Boolean(param.key)) : []
})

const availableVariables = computed(() => {
  if (!props.selectedNode) return []
  return collectAvailableVariables(props.nodes, props.edges, props.selectedNode.id)
})

const promptVariables = computed(() => {
  const paramVariables = inputParams.value.map(param => ({
    key: param.key,
    reference: param.key,
    sourceNodeId: `input-param-${param.key}`,
    sourceLabel: param.description || param.label || '当前能力入参'
  }))
  return [...paramVariables, ...availableVariables.value]
})

const suggestedAlias = computed(() => props.selectedNode?.type === 'start'
  ? 'start'
  : slugAlias(props.selectedNode?.data.label || props.selectedNode?.id || 'node'))
const selectedAlias = computed(() => props.selectedNode?.data.nodeAlias || suggestedAlias.value)
const exampleOutputKey = computed(() => props.selectedNode?.type === 'input' || props.selectedNode?.type === 'start'
  ? (props.selectedNode?.data.inputKey || 'message')
  : (props.selectedNode?.data.outputKey || 'summary'))
const aliasExample = computed(() => `{{${selectedAlias.value}.${exampleOutputKey.value}}}`)
const aliasExampleForQuery = computed(() => availableVariables.value[0] ? `{{${availableVariables.value[0].reference}}}` : '{{start.message}}')
const mappingExample = computed(() => availableVariables.value[0] ? `{{${availableVariables.value[0].reference}}}` : '{{上游别名.输出变量}}')

const promptConfigVisible = computed(() => props.selectedNode?.data.promptConfigVisible === true)
const promptConfigEditable = computed(() => props.editable !== false && props.selectedNode?.data.promptConfigEditable === true)
const descriptionNodeTypes = ['skill', 'agent_ref']
const descriptionVisible = computed(() => {
  if (!descriptionNodeTypes.includes(props.selectedNode?.type || '')) return false
  return props.selectedNode?.data.descriptionVisible === true ||
    props.selectedNode?.data.descriptionEditable === true ||
    Boolean(props.selectedNode?.data.description)
})
const descriptionEditable = computed(() => props.editable !== false && props.selectedNode?.data.descriptionEditable === true)

const agentOptions = computed(() =>
  agents.value.map(agent => ({
    label: agent.name,
    value: agent.id
  }))
)

const workflowOptions = computed(() =>
  workflows.value.map(workflow => ({
    label: workflow.name,
    value: workflow.id
  }))
)

const knowledgeBaseOptions = computed(() =>
  knowledgeBases.value.map(kb => ({
    label: kb.name,
    value: kb.id
  }))
)

const inputTypeOptions = [
  { label: '文本', value: 'text' },
  { label: '多行文本', value: 'textarea' }
]

const humanInputTypeOptions = [
  { label: '文本', value: 'text' },
  { label: '多行文本', value: 'textarea' },
  { label: '选项', value: 'select' }
]

const aliasExampleForInput = computed(() => {
  const node = props.selectedNode
  if (!node) return '{{别名.变量}}'
  const alias = node.data.nodeAlias || '别名'
  const key = node.data.inputKey || '变量'
  return `{{${alias}.${key}}}`
})

function updateNodeData(key: string, value: string | number | boolean | number[] | string[] | Record<string, string>) {
  const promptKeys = ['systemPrompt', 'promptTemplate', 'outputKey', 'model', 'temperature']
  if (promptKeys.includes(key) && !promptConfigEditable.value) {
    return
  }
  if (key === 'description' && !descriptionEditable.value) {
    return
  }
  if (props.selectedNode) {
    emit('update-node-data', props.selectedNode.id, key, value)
  }
}

function onPromptInput(value: string) {
  if (!promptConfigEditable.value) return
  updateNodeData('promptTemplate', value)
}

function onPromptKeyup(event: KeyboardEvent) {
  if (!promptConfigEditable.value) return
  if (event.key === 'Escape') {
    showVariableMenu.value = false
    promptSlashIndex.value = null
    return
  }
  if (event.key !== '/') return
  promptSlashIndex.value = slashIndexFromEvent(event)
  showVariableMenu.value = true
}

function insertPromptVariable(reference: string) {
  if (!promptConfigEditable.value || !props.selectedNode) return
  const current = props.selectedNode.data.promptTemplate || ''
  const next = insertVariableAtSlash(current, reference, promptSlashIndex.value)
  updateNodeData('promptTemplate', next)
  showVariableMenu.value = false
  promptSlashIndex.value = null
}

function onQueryInput(value: string) {
  updateNodeData('query', value)
}

function onQueryKeyup(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    queryMenuVisible.value = false
    querySlashIndex.value = null
    return
  }
  if (event.key !== '/') return
  querySlashIndex.value = slashIndexFromEvent(event)
  queryMenuVisible.value = true
}

function insertQueryVariable(reference: string) {
  if (!props.selectedNode) return
  const current = props.selectedNode.data.query || ''
  const next = insertVariableAtSlash(current, reference, querySlashIndex.value)
  updateNodeData('query', next)
  queryMenuVisible.value = false
  querySlashIndex.value = null
}

function mappingValue(key: string) {
  return props.selectedNode?.data.inputMappings?.[key] || ''
}

function onMappingInput(key: string, value: string) {
  updateMappingValue(key, value)
}

function onMappingKeyup(index: number, event: KeyboardEvent) {
  if (event.key === 'Escape') {
    mappingMenuIndex.value = null
    mappingSlashIndex.value = null
    return
  }
  if (event.key !== '/') return
  mappingSlashIndex.value = slashIndexFromEvent(event)
  mappingMenuIndex.value = index
}

function insertMappingVariable(key: string, reference: string) {
  const current = mappingValue(key)
  const next = insertVariableAtSlash(current, reference, mappingSlashIndex.value)
  updateMappingValue(key, next)
  mappingMenuIndex.value = null
  mappingSlashIndex.value = null
}

function slashIndexFromEvent(event: KeyboardEvent) {
  const target = event.target as HTMLTextAreaElement | HTMLInputElement | null
  const cursor = typeof target?.selectionStart === 'number'
    ? target.selectionStart
    : String(target?.value || '').length
  return Math.max(0, cursor - 1)
}

function insertVariableAtSlash(value: string, reference: string, slashIndex: number | null) {
  const token = `{{${reference}}}`
  if (slashIndex === null || slashIndex < 0 || slashIndex >= value.length || value[slashIndex] !== '/') {
    return `${value}${token}`
  }
  return `${value.slice(0, slashIndex)}${token}${value.slice(slashIndex + 1)}`
}

function updateMappingValue(key: string, value: string) {
  const mappings = {
    ...(props.selectedNode?.data.inputMappings || {}),
    [key]: value
  }
  updateNodeData('inputMappings', mappings)
}

function onAgentSelected(agentId: number) {
  const agent = agents.value.find(item => item.id === agentId)
  updateNodeData('agentId', agentId)
  updateNodeData('agentName', agent?.name || '')
  updateNodeData('label', agent?.name || 'Agent 引用')
}

function onWorkflowSelected(workflowId: number) {
  const workflow = workflows.value.find(item => item.id === workflowId)
  updateNodeData('workflowId', workflowId)
  updateNodeData('workflowName', workflow?.name || '')
  updateNodeData('label', workflow?.name || '工作流引用')
}

function slugAlias(value: string) {
  const normalized = value.normalize('NFD')
    .replace(/[^A-Za-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
  if (!normalized || !/^[A-Za-z]/.test(normalized)) {
    return `node_${Math.abs(hashString(value))}`
  }
  return normalized.slice(0, 1).toLowerCase() + normalized.slice(1)
}

function hashString(value: string) {
  return Array.from(value).reduce((hash, char) => ((hash << 5) - hash + char.charCodeAt(0)) | 0, 0)
}

async function loadReferenceOptions() {
  try {
    const [agentRes, workflowRes] = await Promise.all([
      agentApi.listAgents(),
      workflowApi.list()
    ])
    agents.value = agentRes.data.data
    workflows.value = workflowRes.data.data
  } catch {
    agents.value = []
    workflows.value = []
  }
  try {
    const kbRes = await knowledgeApi.listBases()
    knowledgeBases.value = kbRes.data.data
  } catch {
    knowledgeBases.value = []
  }
}

watch(() => props.selectedNode?.id, () => {
  showVariableMenu.value = false
  promptSlashIndex.value = null
  mappingMenuIndex.value = null
  mappingSlashIndex.value = null
  queryMenuVisible.value = false
  querySlashIndex.value = null
  void hydrateSelectedNode()
})

/**
 * 选中技能节点时，从后端补齐节点缺失的能力元数据：
 * - 技能顶层 config.inputParams（能力公开入参规范）
 * - 技能 LLM_CALL step 的 systemPrompt/promptTemplate/model/temperature/outputKey
 * - AgentAccess 决定的 promptConfigVisible/promptConfigEditable/descriptionEditable 权限标志
 * 拖拽落点与加载已存工作流两条路径都不会带这些字段，统一在此回填并写回 node.data（随保存持久化）。
 * 用 promptConfigVisible !== undefined 作为已回填标记，避免重复请求。
 */
async function hydrateSelectedNode() {
  const node = props.selectedNode
  if (!node || node.type !== 'skill') return
  if (node.data.skillId == null) return
  if (node.data.promptConfigVisible !== undefined) return

  const skillId = node.data.skillId
  const agentId = node.data.agentId
  try {
    const [skillRes, accessRes] = await Promise.all([
      agentApi.getSkillDetail(skillId),
      agentId != null
        ? agentApi.getAgentAccess(agentId)
        : Promise.resolve(null)
    ])
    const skill = skillRes.data.data
    const inputParams = parseSkillInputParams(skill.config)
    const llmStep = (skill.steps || []).find(step => step.action === 'LLM_CALL') || (skill.steps || [])[0]
    const llm = parseLlmStepConfig(llmStep?.config)
    const access = accessRes?.data?.data
    const canReadPrompt = Boolean(access?.canReadPrompt)
    const canManage = Boolean(access?.canManage)

    emit('update-node-data', node.id, 'inputParams', inputParams)
    emit('update-node-data', node.id, 'systemPrompt', llm.systemPrompt)
    emit('update-node-data', node.id, 'promptTemplate', llm.promptTemplate)
    emit('update-node-data', node.id, 'model', llm.model)
    emit('update-node-data', node.id, 'temperature', llm.temperature)
    emit('update-node-data', node.id, 'outputKey', llm.outputKey)
    emit('update-node-data', node.id, 'promptConfigVisible', canReadPrompt)
    emit('update-node-data', node.id, 'promptConfigEditable', canManage)
    emit('update-node-data', node.id, 'descriptionVisible', true)
    emit('update-node-data', node.id, 'descriptionEditable', canManage)
    if (skill.description) {
      emit('update-node-data', node.id, 'description', skill.description)
    }
  } catch {
    // 能力元数据加载失败时保持默认渲染，不阻塞属性面板使用。
  }
}

onMounted(() => {
  loadReferenceOptions()
})
</script>

<style lang="scss" scoped>
.property-panel {
  width: 340px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--color-surface);
  border-left: 1px solid var(--color-border);
  overflow: hidden;
}

.property-panel__header {
  padding: var(--spacing-3) var(--spacing-4);
  border-bottom: 1px solid var(--color-border);
}

.property-panel__title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.property-panel__empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-3);
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}

.property-panel__content {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-4);
}

.property-panel__type-badge {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
}

.property-panel__type-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-base);
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-primary);
}

.property-panel__type-icon--start {
  background: #22c55e;
}

.property-panel__type-icon--end {
  background: #ef4444;
}

.property-panel__type-icon--agent_ref {
  background: #14b8a6;
}

.property-panel__type-icon--workflow_ref {
  background: #f59e0b;
}

.property-panel__type-icon--input {
  background: #38bdf8;
}

.property-panel__type-icon--retrieval {
  background: #8b5cf6;
}

.property-panel__type-icon--human_input {
  background: #f59e0b;
}

.property-panel__type-name {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.property-panel__section {
  margin-bottom: var(--spacing-4);
}

.property-panel__section-title {
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-tertiary);
  margin-bottom: var(--spacing-2);
}

.property-panel__field {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-1);
  margin-bottom: var(--spacing-3);
}

.property-panel__field--prompt,
.property-panel__mapping-template {
  position: relative;
}

.property-panel__notice,
.property-panel__hint {
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  line-height: 1.5;
}

.property-panel__notice {
  margin-bottom: var(--spacing-3);
  padding: var(--spacing-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-base);
  background: var(--color-elevated);
}

.property-panel__label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.property-panel__value {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.property-panel__value--mono {
  font-family: var(--font-family-code);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.property-panel__mapping-row {
  display: grid;
  grid-template-columns: minmax(96px, 38%) minmax(0, 1fr);
  gap: var(--spacing-2);
  align-items: start;
  margin-bottom: var(--spacing-2);
}

.property-panel__param {
  min-width: 0;
  padding: 4px 0;
}

.property-panel__param-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-1);
}

.property-panel__param-key {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-family-code);
  font-size: var(--font-size-xs);
  color: var(--color-text-primary);
}

.property-panel__required {
  flex: 0 0 auto;
  padding: 1px 4px;
  border-radius: var(--radius-sm);
  color: #dc2626;
  background: rgba(220, 38, 38, 0.08);
  font-size: 10px;
}

.property-panel__param-label,
.property-panel__param-desc {
  display: block;
  margin-top: 2px;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-xs);
  line-height: 1.4;
  word-break: break-word;
}

.property-panel__variables {
  position: absolute;
  left: 0;
  right: 0;
  top: calc(100% + 4px);
  z-index: 20;
  max-height: 180px;
  overflow-y: auto;
  padding: var(--spacing-1);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-base);
  background: var(--color-surface);
  box-shadow: var(--shadow-lg);
}

.property-panel__variable {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-2);
  padding: var(--spacing-2);
  border: 0;
  border-radius: var(--radius-sm);
  color: var(--color-text-primary);
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.property-panel__variable:hover {
  background: var(--color-elevated);
}

.property-panel__variable-key {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-family-code);
  font-size: var(--font-size-xs);
}

.property-panel__variable-source,
.property-panel__variable-empty {
  flex: 0 0 auto;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-xs);
}

.property-panel__variable-empty {
  display: block;
  padding: var(--spacing-2);
}
</style>
