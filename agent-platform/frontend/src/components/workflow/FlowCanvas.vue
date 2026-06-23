<template>
  <div
    ref="canvasRoot"
    class="flow-canvas"
    tabindex="0"
    @dragover.prevent="onDragOver"
    @drop="onDrop"
    @keydown.delete.prevent="deleteSelectedElement"
    @keydown.backspace.prevent="deleteSelectedElement"
  >
    <VueFlow
      v-model:nodes="nodes"
      v-model:edges="edges"
      :node-types="nodeTypes"
      :default-edge-options="defaultEdgeOptions"
      :connection-line-style="connectionLineStyle"
      :snap-to-grid="true"
      :snap-grid="[16, 16]"
      fit-view-on-init
      @node-click="onNodeClick"
      @node-context-menu="onNodeContextMenu"
      @edge-click="onEdgeClick"
      @edge-context-menu="onEdgeContextMenu"
      @pane-click="onPaneClick"
      @connect="onConnect"
    >
      <Background :gap="20" :size="1" pattern-color="rgba(255,255,255,0.05)" />
    </VueFlow>

    <div
      v-if="contextMenu.visible"
      class="flow-canvas__context-overlay"
      @click="closeContextMenu"
      @contextmenu.prevent="closeContextMenu"
    >
      <div
        class="flow-canvas__context-menu"
        :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
      >
        <div class="flow-canvas__menu-item" @click="handleDelete">
          <n-icon size="14" :component="TrashOutline" />
          <span>{{ contextMenu.kind === 'edge' ? '删除连线' : '删除节点' }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { markRaw, ref, watch } from 'vue'
import { Background } from '@vue-flow/background'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import type { Connection, EdgeMouseEvent, NodeMouseEvent, NodeTypesObject } from '@vue-flow/core'
import { NIcon } from 'naive-ui'
import { TrashOutline } from '@vicons/ionicons5'
import StartNode from './StartNode.vue'
import EndNode from './EndNode.vue'
import InputNode from './InputNode.vue'
import SkillNode from './SkillNode.vue'
import AgentRefNode from './AgentRefNode.vue'
import WorkflowRefNode from './WorkflowRefNode.vue'
import RetrievalNode from './RetrievalNode.vue'
import type { ExecutionEvent } from '@/api/execution'
import type { WorkflowEdge, WorkflowNode } from '@/types/workflow'
import { applyRuntimeEventsToEdges, applyRuntimeEventsToNodes } from '@/utils/workflowRuntime'

const props = defineProps<{
  runtimeEvents?: ExecutionEvent[]
}>()

const nodeTypes = {
  start: markRaw(StartNode),
  end: markRaw(EndNode),
  input: markRaw(InputNode),
  skill: markRaw(SkillNode),
  agent_ref: markRaw(AgentRefNode),
  workflow_ref: markRaw(WorkflowRefNode),
  retrieval: markRaw(RetrievalNode)
} as unknown as NodeTypesObject

const defaultEdgeOptions = {
  type: 'smoothstep',
  animated: false,
  style: { stroke: 'var(--color-primary)', strokeWidth: 2 }
}

const connectionLineStyle = { stroke: 'var(--color-primary)', strokeWidth: 2 }

const emit = defineEmits<{
  (e: 'node-selected', node: WorkflowNode | null): void
  (e: 'nodes-change'): void
}>()

const {
  project,
  vueFlowRef
} = useVueFlow({
  id: 'workflow-editor'
})

const nodes = ref<WorkflowNode[]>([])
const edges = ref<WorkflowEdge[]>([])
const selectedNodeId = ref('')
const selectedEdgeId = ref('')
const canvasRoot = ref<HTMLElement | null>(null)

const contextMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  kind: 'node' as 'node' | 'edge',
  nodeId: '',
  edgeId: ''
})

function addNodes(nextNodes: WorkflowNode[]) {
  nodes.value = [...nodes.value, ...nextNodes]
}

function addEdges(nextEdges: WorkflowEdge[]) {
  edges.value = [...edges.value, ...nextEdges]
}

function removeNodes(nodeIds: string[]) {
  const removeSet = new Set(nodeIds)
  nodes.value = nodes.value.filter(node => !removeSet.has(node.id))
  edges.value = edges.value.filter(edge => {
    return !removeSet.has(edge.source || '') && !removeSet.has(edge.target || '')
  })
  if (removeSet.has(selectedNodeId.value)) {
    selectedNodeId.value = ''
  }
}

function removeEdges(edgeIds: string[]) {
  const removeSet = new Set(edgeIds)
  edges.value = edges.value.filter(edge => !removeSet.has(edge.id))
  if (removeSet.has(selectedEdgeId.value)) {
    selectedEdgeId.value = ''
  }
}

watch(
  () => props.runtimeEvents,
  (events) => {
    if (!events) return
    nodes.value = applyRuntimeEventsToNodes(nodes.value as WorkflowNode[], events)
    edges.value = applyRuntimeEventsToEdges(edges.value as WorkflowEdge[], events)
  },
  { deep: true }
)

function onNodeClick({ node }: NodeMouseEvent) {
  selectedNodeId.value = node.id
  selectedEdgeId.value = ''
  canvasRoot.value?.focus()
  emit('node-selected', node as WorkflowNode)
}

function onNodeContextMenu({ event, node }: NodeMouseEvent) {
  event.preventDefault()
  event.stopPropagation()
  selectedNodeId.value = node.id
  selectedEdgeId.value = ''
  canvasRoot.value?.focus()
  contextMenu.value = {
    visible: true,
    x: 'clientX' in event ? event.clientX : 0,
    y: 'clientY' in event ? event.clientY : 0,
    kind: 'node',
    nodeId: node.id,
    edgeId: ''
  }
}

function onEdgeClick({ edge }: EdgeMouseEvent) {
  selectedNodeId.value = ''
  selectedEdgeId.value = edge.id
  canvasRoot.value?.focus()
  emit('node-selected', null)
}

function onEdgeContextMenu({ event, edge }: EdgeMouseEvent) {
  event.preventDefault()
  event.stopPropagation()
  selectedNodeId.value = ''
  selectedEdgeId.value = edge.id
  canvasRoot.value?.focus()
  contextMenu.value = {
    visible: true,
    x: 'clientX' in event ? event.clientX : 0,
    y: 'clientY' in event ? event.clientY : 0,
    kind: 'edge',
    nodeId: '',
    edgeId: edge.id
  }
}

function onPaneClick() {
  selectedNodeId.value = ''
  selectedEdgeId.value = ''
  emit('node-selected', null)
  closeContextMenu()
}

function onConnect(connection: Connection) {
  if (!connection.source || !connection.target) return
  const edge: WorkflowEdge = {
    id: `edge-${connection.source}-${connection.target}-${Date.now()}`,
    source: connection.source,
    target: connection.target,
    sourceHandle: connection.sourceHandle || undefined,
    targetHandle: connection.targetHandle || undefined,
    type: 'smoothstep',
    animated: false,
    style: { stroke: 'var(--color-primary)', strokeWidth: 2 }
  }
  edges.value = [...edges.value, edge]
}

function onDragOver(event: DragEvent) {
  event.dataTransfer!.dropEffect = 'move'
}

function onDrop(event: DragEvent) {
  const data = event.dataTransfer?.getData('application/vueflow')
  if (!data) return

  const parsed = JSON.parse(data)
  const { left, top } = (vueFlowRef.value as HTMLElement).getBoundingClientRect()
  const position = project({
    x: event.clientX - left,
    y: event.clientY - top
  })

  addNodes([{
    id: `${parsed.nodeType}-${Date.now()}`,
    type: parsed.nodeType,
    position,
    data: {
      label: parsed.label,
      skillId: parsed.skillId,
      agentId: parsed.agentId,
      agentName: parsed.agentName,
      description: parsed.description,
      workflowId: parsed.workflowId,
      workflowName: parsed.workflowName,
      inputKey: parsed.inputKey,
      inputType: parsed.inputType,
      required: parsed.required,
      defaultValue: parsed.defaultValue,
      placeholder: parsed.placeholder,
      accept: parsed.accept,
      kbId: parsed.kbId,
      kbIds: parsed.kbIds,
      query: parsed.query
    }
  }])
  emit('nodes-change')
}

function handleDelete() {
  if (contextMenu.value.kind === 'edge' && contextMenu.value.edgeId) {
    removeEdges([contextMenu.value.edgeId])
  } else if (contextMenu.value.nodeId) {
    removeNodes([contextMenu.value.nodeId])
  }
  closeContextMenu()
}

function deleteSelectedElement() {
  if (selectedNodeId.value) {
    removeNodes([selectedNodeId.value])
  } else if (selectedEdgeId.value) {
    removeEdges([selectedEdgeId.value])
  }
  closeContextMenu()
}

function closeContextMenu() {
  contextMenu.value.visible = false
}

defineExpose({
  nodes,
  edges,
  addNodes,
  removeNodes,
  removeEdges,
  addEdges,
  fitView: () => {
  }
})
</script>

<style lang="scss" scoped>
.flow-canvas {
  flex: 1;
  height: 100%;
  position: relative;
  background: var(--color-bg);

  :deep(.vue-flow) {
    background: transparent;
  }

  :deep(.vue-flow__edge-textbg) {
    fill: var(--color-card);
  }

  :deep(.vue-flow__edge-text) {
    fill: var(--color-text-primary);
    font-size: var(--font-size-xs);
  }

  :deep(.vue-flow__edge.selected .vue-flow__edge-path) {
    stroke: #f59e0b;
    stroke-width: 3;
    filter: drop-shadow(0 0 8px rgba(245, 158, 11, 0.45));
  }

  :deep(.workflow-edge--active .vue-flow__edge-path) {
    stroke: #38bdf8;
    stroke-width: 3;
    stroke-dasharray: 10 6;
    animation: workflow-edge-flow 0.75s linear infinite;
    filter: drop-shadow(0 0 8px rgba(56, 189, 248, 0.42));
  }

  :deep(.vue-flow__edge[data-runtime-active="true"] .vue-flow__edge-path) {
    stroke: #38bdf8;
    stroke-width: 3;
    stroke-dasharray: 10 6;
    animation: workflow-edge-flow 0.75s linear infinite;
    filter: drop-shadow(0 0 8px rgba(56, 189, 248, 0.42));
  }

  :deep(.vue-flow__node-input),
  :deep(.vue-flow__node-input.selected),
  :deep(.vue-flow__node-input:focus),
  :deep(.vue-flow__node-input:focus-visible),
  :deep(.vue-flow__node-input.selectable:hover) {
    background: transparent;
    border: 0;
    box-shadow: none;
    color: inherit;
    padding: 0;
  }

  :deep(.vue-flow__handle) {
    width: 12px;
    height: 12px;
    border: 2px solid var(--color-bg);
    background: var(--color-primary);
    box-shadow: 0 0 0 2px rgba(var(--color-primary-rgb), 0.28);
    opacity: 0.95;
  }

  :deep(.vue-flow__handle:hover) {
    box-shadow: 0 0 0 4px rgba(var(--color-primary-rgb), 0.32);
  }

  :deep(.vue-flow__minimap) {
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
  }

  :deep(.vue-flow__controls) {
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    overflow: hidden;

    button {
      background: var(--color-surface);
      border-bottom: 1px solid var(--color-border);
      color: var(--color-text-secondary);
      fill: var(--color-text-secondary);

      &:hover {
        background: var(--color-elevated);
      }
    }
  }

  :deep(.workflow-node--running > div) {
    border-color: #38bdf8;
    box-shadow: 0 0 0 2px rgba(56, 189, 248, 0.22), 0 0 18px rgba(56, 189, 248, 0.18);
  }

  :deep(.workflow-node--success > div) {
    border-color: #22c55e;
    box-shadow: 0 0 0 2px rgba(34, 197, 94, 0.18);
  }

  :deep(.workflow-node--failed > div) {
    border-color: #ef4444;
    box-shadow: 0 0 0 2px rgba(239, 68, 68, 0.22);
  }

  :deep(.workflow-node--waiting > div) {
    border-color: #f59e0b;
    box-shadow: 0 0 0 2px rgba(245, 158, 11, 0.2);
  }
}

@keyframes workflow-edge-flow {
  from {
    stroke-dashoffset: 16;
  }

  to {
    stroke-dashoffset: 0;
  }
}

.flow-canvas__context-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
}

.flow-canvas__context-menu {
  position: absolute;
  background: var(--color-elevated);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--spacing-1) 0;
  min-width: 140px;
  box-shadow: var(--shadow-lg);
  z-index: 101;
}

.flow-canvas__menu-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: var(--spacing-2) var(--spacing-3);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-in-out);

  &:hover {
    background: rgba(248, 113, 113, 0.1);
    color: var(--color-danger);
  }
}
</style>
