<!-- ============================================================
  工作流编辑器 — 三栏布局+顶部操作栏
  左: 组件面板 | 中: Vue Flow画布 | 右: 属性面板
  ============================================================ -->
<template>
  <div class="workflow-editor">
    <!-- 顶部操作栏 -->
    <div class="workflow-editor__topbar">
      <div class="workflow-editor__topbar-left">
        <n-button text @click="goBack">
          <template #icon>
            <n-icon :component="ArrowBackOutline" />
          </template>
        </n-button>
        <n-divider vertical />
        <n-input
          v-model:value="workflowName"
          size="small"
          placeholder="工作流名称"
          class="workflow-editor__name-input"
        />
        <n-tag v-if="workflowStatus" :type="statusTagType" size="small">
          {{ statusLabel }}
        </n-tag>
        <span
          class="workflow-editor__rag-toggle"
          title="开启后该工作流运行启用 RAG 证据 + 用户记忆（覆盖全局；检索节点回调受其约束）"
        >
          记忆模式
          <n-switch
            :value="workflowRagEnabled"
            size="small"
            @update:value="onWorkflowRagToggle"
          />
        </span>
      </div>

      <div class="workflow-editor__topbar-right">
        <n-button size="small" @click="handleSave" :loading="saving">
          <template #icon>
            <n-icon :component="SaveOutline" />
          </template>
          保存
        </n-button>
        <n-button size="small" type="primary" @click="handleRun" :loading="running">
          <template #icon>
            <n-icon :component="PlayOutline" />
          </template>
          运行
        </n-button>
      </div>
    </div>

    <!-- 三栏布局 -->
    <div class="workflow-editor__body">
      <!-- 左侧组件面板 -->
      <ComponentPalette />

      <!-- 中间画布区域 -->
      <div class="workflow-editor__canvas-area">
        <FlowCanvas
          ref="flowCanvasRef"
          :runtime-events="runtimeEvents"
          @node-selected="onNodeSelected"
          @nodes-change="onNodesChange"
        />
        <!-- 底部工具栏 -->
        <div class="workflow-editor__toolbar-wrapper">
          <CanvasToolbar
            @zoom-in="handleZoomIn"
            @zoom-out="handleZoomOut"
            @fit-view="handleFitView"
            @undo="handleUndo"
            @redo="handleRedo"
            @export="handleExport"
          />
        </div>

        <section v-if="runtimeEvents.length" class="workflow-editor__run-panel">
          <div class="workflow-editor__run-header">
            <div>
              <span class="workflow-editor__run-title">本次运行</span>
              <span v-if="runSummary.executionId" class="workflow-editor__run-id">
                #{{ runSummary.executionId }}
              </span>
            </div>
            <div class="workflow-editor__run-actions">
              <n-tag size="small" :type="runStatusType">{{ runSummary.status }}</n-tag>
              <n-button text size="tiny" title="关闭运行结果" @click="closeRunPanel">
                <template #icon>
                  <n-icon :component="CloseOutline" />
                </template>
              </n-button>
            </div>
          </div>

          <div class="workflow-editor__run-stats">
            <span>{{ runSummary.completedNodes }} 个节点完成</span>
            <span>{{ runSummary.totalEvents }} 个事件</span>
            <span v-if="runSummary.failedNodes">{{ runSummary.failedNodes }} 个失败</span>
          </div>

          <div class="workflow-editor__run-events">
            <div
              v-for="(event, index) in runtimeEvents"
              :key="`${event.executionId}-${index}-${event.type}-${event.nodeId || 'execution'}`"
              class="workflow-editor__run-event"
              :class="`workflow-editor__run-event--${event.status.toLowerCase()}`"
            >
              <span class="workflow-editor__run-dot" />
              <div class="workflow-editor__run-event-main">
                <div class="workflow-editor__run-event-title">
                  <strong>{{ event.type }}</strong>
                  <span>{{ runtimeEventNodeName(event) }}</span>
                </div>
                <div v-if="event.timestamp || runtimeEventDetail(event)" class="workflow-editor__run-event-meta">
                  <span v-if="event.timestamp">{{ formatRuntimeTime(event.timestamp) }}</span>
                  <span v-if="runtimeEventDetail(event)">{{ runtimeEventDetail(event) }}</span>
                </div>
                <div v-if="runtimeEventInput(event) || runtimeEventOutput(event)" class="workflow-editor__io">
                  <div v-if="runtimeEventInput(event)" class="workflow-editor__io-block">
                    <span class="workflow-editor__io-label">输入</span>
                    <pre class="workflow-editor__run-output">{{ runtimeEventInput(event) }}</pre>
                  </div>
                  <div v-if="runtimeEventOutput(event)" class="workflow-editor__io-block">
                    <span class="workflow-editor__io-label">输出</span>
                    <pre class="workflow-editor__run-output">{{ runtimeEventOutput(event) }}</pre>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>
      </div>

      <!-- 右侧属性面板 -->
        <PropertyPanel
          :selected-node="selectedNode"
          :nodes="flowCanvasRef?.nodes || []"
          :edges="flowCanvasRef?.edges || []"
          :editable="isWorkflowOwner"
          @update-node-data="onUpdateNodeData"
        />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import {
  NButton,
  NIcon,
  NInput,
  NTag,
  NDivider,
  NSwitch
} from 'naive-ui'
import {
  ArrowBackOutline,
  SaveOutline,
  PlayOutline,
  CloseOutline
} from '@vicons/ionicons5'
import ComponentPalette from '@/components/workflow/ComponentPalette.vue'
import FlowCanvas from '@/components/workflow/FlowCanvas.vue'
import CanvasToolbar from '@/components/workflow/CanvasToolbar.vue'
import PropertyPanel from '@/components/workflow/PropertyPanel.vue'
import { workflowApi } from '@/api/workflow'
import type { ExecutionEvent } from '@/api/execution'
import type { Workflow, WorkflowStatus } from '@/types/workflow'
import { toFlowEdge, toFlowNode, toWorkflowEdgeRequest, toWorkflowNodeRequest } from '@/utils/workflowMapper'
import { summarizeWorkflowRun } from '@/utils/workflowRunSummary'
import { collectWorkflowRunInput } from '@/utils/workflowRuntime'
import type { WorkflowNode } from '@/types/workflow'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const authStore = useAuthStore()

const flowCanvasRef = ref<InstanceType<typeof FlowCanvas> | null>(null)
const selectedNode = ref<WorkflowNode | null>(null)

/** 工作流编辑数据 */
const workflowId = ref<number | null>(null)
const workflowName = ref('未命名工作流')
const workflowDescription = ref('')
const workflowStatus = ref<WorkflowStatus>('draft')
const workflowOwnerId = ref<number | null>(null)
/** 记忆模式开关（null=继承全局 → UI 显 off；true/false 显式覆盖） */
const workflowRagEnabled = ref(false)
const saving = ref(false)
const running = ref(false)
const runtimeEvents = ref<ExecutionEvent[]>([])
const runtimeReplayVersion = ref(0)
const runAbortController = ref<AbortController | null>(null)
const runSummary = computed(() => summarizeWorkflowRun(runtimeEvents.value))
const runStatusType = computed(() => {
  if (runSummary.value.status === 'SUCCESS') return 'success'
  if (runSummary.value.status === 'FAILED') return 'error'
  if (runSummary.value.status === 'WAITING_APPROVAL') return 'warning'
  return 'info'
})
const isWorkflowOwner = computed(() => {
  return workflowOwnerId.value == null || workflowOwnerId.value === authStore.userInfo?.id
})

/** 状态标签 */
const statusTagType = computed(() => {
  const map: Record<string, 'default' | 'success' | 'warning'> = {
    draft: 'default',
    published: 'success',
    archived: 'warning'
  }
  return map[workflowStatus.value] || 'default'
})

const statusLabel = computed(() => {
  const map: Record<string, string> = {
    draft: '草稿',
    published: '已发布',
    archived: '已归档'
  }
  return map[workflowStatus.value] || '未知'
})

/** 返回列表页 */
function goBack() {
  router.push('/workflow')
}

/** 节点选中 */
function onNodeSelected(node: WorkflowNode | null) {
  selectedNode.value = node
}

/** 节点变化 */
function onNodesChange() {
  // 后续可添加撤销/重做逻辑
}

/** 更新节点数据 */
function onUpdateNodeData(nodeId: string, key: string, value: string | number | boolean | number[] | Record<string, string>) {
  if (!flowCanvasRef.value) return
  const nodes = flowCanvasRef.value.nodes
  const node = nodes.find((n: WorkflowNode) => n.id === nodeId)
  if (node) {
    node.data = { ...node.data, [key]: value }
  }
}

/** 工具栏操作 */
function handleZoomIn() {
  // Vue Flow缩放由组件内部处理
}

function handleZoomOut() {
  // Vue Flow缩放由组件内部处理
}

function handleFitView() {
  // 适应画布
}

function handleUndo() {
  // 撤销
}

function handleRedo() {
  // 重做
}

/** 导出工作流JSON */
async function handleExport() {
  if (!workflowId.value) {
    message.warning('请先保存工作流')
    return
  }
  try {
    const res = await workflowApi.exportJson(workflowId.value)
    const json = JSON.stringify(res.data.data, null, 2)
    const blob = new Blob([json], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${workflowName.value}.json`
    a.click()
    URL.revokeObjectURL(url)
    message.success('导出成功')
  } catch {
    message.error('导出失败')
  }
}

/** 保存工作流 */
async function handleSave() {
  if (!flowCanvasRef.value) return

  saving.value = true
  try {
    await saveWorkflow()
    message.success('保存成功')
  } catch {
    message.error('保存失败')
  } finally {
    saving.value = false
  }
}

/** 记忆模式开关：乐观更新 + 调 rag-enabled 端点，失败回滚 */
async function onWorkflowRagToggle(val: boolean) {
  const prev = workflowRagEnabled.value
  workflowRagEnabled.value = val
  if (workflowId.value == null) return
  try {
    await workflowApi.setRagEnabled(workflowId.value, val)
    message.success(val ? '已开启工作流记忆模式' : '已关闭工作流记忆模式')
  } catch {
    workflowRagEnabled.value = prev
    message.error('设置失败')
  }
}

async function saveWorkflow() {
  if (!flowCanvasRef.value) return

  const nodes = flowCanvasRef.value.nodes.map((n: WorkflowNode) => toWorkflowNodeRequest(n))
  const edges = flowCanvasRef.value.edges.map((e) => toWorkflowEdgeRequest(e))

  if (workflowId.value) {
    await workflowApi.update(workflowId.value, {
      name: workflowName.value,
      description: workflowDescription.value,
      nodes,
      edges
    })
    return
  }

  const res = await workflowApi.create({
    name: workflowName.value,
    description: workflowDescription.value,
    nodes,
    edges
  })
  workflowId.value = res.data.data.id
  router.replace(`/workflow/${workflowId.value}`)
}

/** 运行工作流 */
async function handleRun() {
  if (!workflowId.value) {
    message.warning('请先保存工作流')
    return
  }
  running.value = true
  runtimeReplayVersion.value += 1
  runAbortController.value?.abort()
  runAbortController.value = new AbortController()
  runtimeEvents.value = []
  try {
    await saveWorkflow()
    for await (const event of workflowApi.runStream(
      workflowId.value,
      collectWorkflowRunInput(flowCanvasRef.value?.nodes || []),
      runAbortController.value.signal
    )) {
      runtimeEvents.value = [...runtimeEvents.value, event]
      if (event.type === 'EXECUTION_FAILED') {
        message.error('运行失败')
      }
    }
    message.success('运行完成，画布已更新运行态')
  } catch (error) {
    if ((error as Error).name !== 'AbortError') {
      message.error('运行失败')
    }
  } finally {
    running.value = false
    runAbortController.value = null
  }
}

function closeRunPanel() {
  runtimeReplayVersion.value += 1
  runAbortController.value?.abort()
  runtimeEvents.value = []
}

function runtimeEventDetail(event: ExecutionEvent) {
  const metadata = event.metadata || {}
  const detail = metadata.errorMessage || metadata.selectedTarget || metadata.selectedRoute || metadata.checkpointRef
  return detail ? String(detail) : ''
}

function runtimeEventNodeName(event: ExecutionEvent) {
  if (!event.nodeId) return '工作流'
  const node = flowCanvasRef.value?.nodes.find((item: WorkflowNode) => item.id === event.nodeId)
  return node?.data?.label || event.nodeId
}

function runtimeEventInput(event: ExecutionEvent) {
  const input = event.input || inputForNode(event.nodeId)
  return formatRuntimePayload(input)
}

function runtimeEventOutput(event: ExecutionEvent) {
  return formatRuntimePayload(event.output)
}

function inputForNode(nodeId: string | null) {
  if (!nodeId) return undefined
  const startedEvent = runtimeEvents.value.find(event =>
    event.nodeId === nodeId && event.type === 'NODE_STARTED' && event.input
  )
  return startedEvent?.input
}

function formatRuntimePayload(payload: Record<string, unknown> | undefined) {
  const output = payload
  if (!output || Object.keys(output).length === 0) return ''
  const text = output.text
  if (typeof text === 'string' && text.trim()) return text
  return JSON.stringify(output, null, 2)
}

function formatRuntimeTime(timestamp: string) {
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return timestamp
  return date.toLocaleTimeString('zh-CN', { hour12: false })
}

/** 初始化：新建或加载 */
async function initWorkflow() {
  const id = route.params.id as string

  if (id && id !== 'new') {
    // 编辑现有工作流
    const numId = parseInt(id, 10)
    if (isNaN(numId)) {
      message.error('无效的工作流ID')
      router.push('/workflow')
      return
    }

    try {
      const res = await workflowApi.getDetail(numId)
      const workflow: Workflow = res.data.data
      workflowId.value = workflow.id
      workflowName.value = workflow.name
      workflowDescription.value = workflow.description || ''
      workflowStatus.value = workflow.status
      workflowOwnerId.value = workflow.ownerId || null
      workflowRagEnabled.value = workflow.ragEnabled === true

      // 等待画布就绪后设置节点
      await new Promise(resolve => setTimeout(resolve, 100))
      if (flowCanvasRef.value) {
        flowCanvasRef.value.addNodes(
          workflow.nodes.map(n => {
            const flowNode = toFlowNode(n)
            return {
              id: flowNode.id,
              type: flowNode.type,
              position: flowNode.position,
              data: flowNode.data
            }
          })
        )
        flowCanvasRef.value.addEdges(workflow.edges.map(toFlowEdge))
      }
    } catch {
      message.error('加载工作流失败')
      router.push('/workflow')
    }
  } else {
    // 新建工作流：自动创建开始+结束节点
    await new Promise(resolve => setTimeout(resolve, 100))
    if (flowCanvasRef.value) {
      flowCanvasRef.value.addNodes([
        {
          id: 'start-1',
          type: 'start',
          position: { x: 300, y: 50 },
          data: { label: '开始', nodeAlias: 'start' }
        },
        {
          id: 'end-1',
          type: 'end',
          position: { x: 300, y: 400 },
          data: { label: '结束' }
        }
      ])
    }
  }
}

onMounted(() => {
  initWorkflow()
})
</script>

<style lang="scss" scoped>
.workflow-editor {
  display: flex;
  flex-direction: column;
  height: calc(100vh - var(--header-height));
  margin: calc(-1 * var(--spacing-6));
  background: var(--color-bg);
}

.workflow-editor__topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-2) var(--spacing-4);
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
  min-height: 48px;
}

.workflow-editor__topbar-left {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
}

.workflow-editor__name-input {
  width: 240px;
}

.workflow-editor__topbar-right {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
}

.workflow-editor__body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.workflow-editor__canvas-area {
  flex: 1;
  position: relative;
  overflow: hidden;
}

.workflow-editor__toolbar-wrapper {
  position: absolute;
  bottom: var(--spacing-4);
  left: 50%;
  transform: translateX(-50%);
  z-index: 10;
}

.workflow-editor__run-panel {
  position: absolute;
  right: var(--spacing-4);
  bottom: var(--spacing-4);
  z-index: 11;
  width: min(420px, calc(100% - var(--spacing-8)));
  max-height: min(460px, calc(100% - var(--spacing-8)));
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  box-shadow: var(--shadow-lg);
}

.workflow-editor__run-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-3);
  padding: var(--spacing-3) var(--spacing-4);
  border-bottom: 1px solid var(--color-border);
}

.workflow-editor__run-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
}

.workflow-editor__run-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.workflow-editor__run-id {
  margin-left: var(--spacing-2);
  color: var(--color-text-tertiary);
  font-size: var(--font-size-xs);
}

.workflow-editor__run-stats {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-2);
  padding: var(--spacing-2) var(--spacing-4);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  border-bottom: 1px solid var(--color-border);
}

.workflow-editor__run-events {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-2);
}

.workflow-editor__run-event {
  display: flex;
  gap: var(--spacing-2);
  padding: var(--spacing-2);
  border-radius: var(--radius-base);
}

.workflow-editor__run-event + .workflow-editor__run-event {
  margin-top: var(--spacing-1);
}

.workflow-editor__run-dot {
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: 999px;
  background: var(--color-text-tertiary);
  flex: 0 0 auto;
}

.workflow-editor__run-event--success .workflow-editor__run-dot {
  background: #22c55e;
}

.workflow-editor__run-event--failed .workflow-editor__run-dot {
  background: #ef4444;
}

.workflow-editor__run-event--running .workflow-editor__run-dot,
.workflow-editor__run-event--waiting_approval .workflow-editor__run-dot {
  background: #f59e0b;
}

.workflow-editor__run-event-main {
  min-width: 0;
  flex: 1;
}

.workflow-editor__run-event-title,
.workflow-editor__run-event-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-2);
}

.workflow-editor__run-event-title {
  color: var(--color-text-primary);
  font-size: var(--font-size-xs);
}

.workflow-editor__run-event-meta {
  margin-top: 2px;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-xs);
}

.workflow-editor__run-output {
  margin: 0;
  max-height: 120px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--color-text-secondary);
  font-family: var(--font-family-code);
  font-size: var(--font-size-xs);
  line-height: 1.5;
}

.workflow-editor__io {
  display: grid;
  gap: var(--spacing-2);
  margin-top: var(--spacing-2);
}

.workflow-editor__io-block {
  display: grid;
  gap: 4px;
}

.workflow-editor__io-label {
  width: fit-content;
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
  background: var(--color-elevated);
  font-size: var(--font-size-xs);
}

@media (max-width: 720px) {
  .workflow-editor__run-panel {
    right: var(--spacing-2);
    left: var(--spacing-2);
    bottom: calc(var(--spacing-4) + 48px);
    width: auto;
  }
}
</style>

