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
      </div>

      <div class="workflow-editor__topbar-right">
        <n-button size="small" @click="handleSave" :loading="saving">
          <template #icon>
            <n-icon :component="SaveOutline" />
          </template>
          保存
        </n-button>
        <n-button size="small" type="primary" @click="handleRun">
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
      </div>

      <!-- 右侧属性面板 -->
      <PropertyPanel
        :selected-node="selectedNode"
        @update-node-data="onUpdateNodeData"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage, useDialog } from 'naive-ui'
import {
  NButton,
  NIcon,
  NInput,
  NTag,
  NDivider
} from 'naive-ui'
import {
  ArrowBackOutline,
  SaveOutline,
  PlayOutline
} from '@vicons/ionicons5'
import ComponentPalette from '@/components/workflow/ComponentPalette.vue'
import FlowCanvas from '@/components/workflow/FlowCanvas.vue'
import CanvasToolbar from '@/components/workflow/CanvasToolbar.vue'
import PropertyPanel from '@/components/workflow/PropertyPanel.vue'
import { workflowApi } from '@/api/workflow'
import type { Workflow, WorkflowStatus } from '@/types/workflow'
import type { GraphNode } from '@vue-flow/core'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const dialog = useDialog()

const flowCanvasRef = ref<InstanceType<typeof FlowCanvas> | null>(null)
const selectedNode = ref<GraphNode | null>(null)

/** 工作流编辑数据 */
const workflowId = ref<number | null>(null)
const workflowName = ref('未命名工作流')
const workflowDescription = ref('')
const workflowStatus = ref<WorkflowStatus>('draft')
const saving = ref(false)

/** 状态标签 */
const statusTagType = computed(() => {
  const map: Record<string, string> = {
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
function onNodeSelected(node: GraphNode | null) {
  selectedNode.value = node
}

/** 节点变化 */
function onNodesChange() {
  // 后续可添加撤销/重做逻辑
}

/** 更新节点数据 */
function onUpdateNodeData(nodeId: string, key: string, value: string) {
  if (!flowCanvasRef.value) return
  const nodes = flowCanvasRef.value.nodes
  const node = nodes.find((n: GraphNode) => n.id === nodeId)
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
    const nodes = flowCanvasRef.value.nodes.map((n: GraphNode) => ({
      id: n.id,
      type: n.type || 'skill',
      position: { x: n.position.x, y: n.position.y },
      data: n.data
    }))

    const edges = flowCanvasRef.value.edges.map((e: any) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      sourceHandle: e.sourceHandle || undefined,
      targetHandle: e.targetHandle || undefined
    }))

    if (workflowId.value) {
      // 更新
      await workflowApi.update(workflowId.value, {
        name: workflowName.value,
        description: workflowDescription.value,
        nodes,
        edges
      })
      message.success('保存成功')
    } else {
      // 创建
      const res = await workflowApi.create({
        name: workflowName.value,
        description: workflowDescription.value,
        nodes,
        edges
      })
      workflowId.value = res.data.data.id
      message.success('创建成功')
      // 更新URL（不刷新页面）
      router.replace(`/workflow/${workflowId.value}`)
    }
  } catch {
    message.error('保存失败')
  } finally {
    saving.value = false
  }
}

/** 运行工作流 */
function handleRun() {
  dialog.info({
    title: '运行工作流',
    content: '工作流运行功能将在后续版本实现',
    positiveText: '确定'
  })
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

      // 等待画布就绪后设置节点
      await new Promise(resolve => setTimeout(resolve, 100))
      if (flowCanvasRef.value) {
        flowCanvasRef.value.addNodes(
          workflow.nodes.map(n => ({
            id: n.id,
            type: n.type,
            position: n.position,
            data: n.data
          }))
        )
        flowCanvasRef.value.addEdges(
          workflow.edges.map(e => ({
            ...e,
            type: 'smoothstep',
            animated: true,
            style: { stroke: 'var(--color-primary)', strokeWidth: 2 }
          }))
        )
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
          data: { label: '开始' }
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
</style>
