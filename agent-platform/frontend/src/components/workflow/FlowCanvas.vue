<!-- ============================================================
  Vue Flow画布 — 核心，深色网格背景+缩放/平移+拖入节点+连线+右键删除
  ============================================================ -->
<template>
  <div
    class="flow-canvas"
    @dragover.prevent="onDragOver"
    @drop="onDrop"
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
      @pane-click="onPaneClick"
      @connect="onConnect"
    >
      <Background :gap="20" :size="1" pattern-color="rgba(255,255,255,0.05)" />

      <!-- 右键菜单 -->
      <template #node-context-menu="nodeProps">
        <div class="flow-canvas__context-menu" :style="contextMenuStyle">
          <div class="flow-canvas__menu-item" @click="deleteNode(nodeProps.id)">
            <n-icon size="14" :component="TrashOutline" />
            <span>删除节点</span>
          </div>
        </div>
      </template>
    </VueFlow>

    <!-- 自定义右键菜单覆盖层 -->
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
          <span>删除节点</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, markRaw } from 'vue'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import type { Connection, GraphNode } from '@vue-flow/core'
import { NIcon } from 'naive-ui'
import { TrashOutline } from '@vicons/ionicons5'
import StartNode from './StartNode.vue'
import EndNode from './EndNode.vue'
import SkillNode from './SkillNode.vue'

/** 注册自定义节点类型 */
const nodeTypes = {
  start: markRaw(StartNode),
  end: markRaw(EndNode),
  skill: markRaw(SkillNode)
}

/** 默认连线样式：贝塞尔曲线 */
const defaultEdgeOptions = {
  type: 'smoothstep',
  animated: true,
  style: { stroke: 'var(--color-primary)', strokeWidth: 2 }
}

const connectionLineStyle = { stroke: 'var(--color-primary)', strokeWidth: 2 }

const emit = defineEmits<{
  (e: 'node-selected', node: GraphNode | null): void
  (e: 'nodes-change'): void
}>()

const {
  nodes,
  edges,
  addNodes,
  addEdges,
  removeNodes,
  project,
  vueFlowRef
} = useVueFlow({
  id: 'workflow-editor'
})

/** 右键菜单状态 */
const contextMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  nodeId: ''
})

/** 节点点击 — 通知选中 */
function onNodeClick({ node }: { node: GraphNode }) {
  emit('node-selected', node)
}

/** 画布空白点击 — 取消选中 */
function onPaneClick() {
  emit('node-selected', null)
  closeContextMenu()
}

/** 连线完成 */
function onConnect(connection: Connection) {
  const edge = {
    id: `edge-${connection.source}-${connection.target}`,
    source: connection.source,
    target: connection.target,
    type: 'smoothstep',
    animated: true,
    style: { stroke: 'var(--color-primary)', strokeWidth: 2 }
  }
  addEdges([edge])
}

/** 拖拽经过画布 */
function onDragOver(event: DragEvent) {
  event.dataTransfer!.dropEffect = 'move'
}

/** 拖放创建节点 */
function onDrop(event: DragEvent) {
  const data = event.dataTransfer?.getData('application/vueflow')
  if (!data) return

  const parsed = JSON.parse(data)
  const { left, top } = (vueFlowRef.value as HTMLElement).getBoundingClientRect()
  const position = project({
    x: event.clientX - left,
    y: event.clientY - top
  })

  const newNode = {
    id: `${parsed.nodeType}-${Date.now()}`,
    type: parsed.nodeType,
    position,
    data: {
      label: parsed.label,
      skillId: parsed.skillId,
      agentId: parsed.agentId,
      agentName: parsed.agentName
    }
  }

  addNodes([newNode])
  emit('nodes-change')
}

/** 右键菜单相关 */
const contextMenuStyle = ref({})

function handleDelete() {
  if (contextMenu.value.nodeId) {
    removeNodes([contextMenu.value.nodeId])
  }
  closeContextMenu()
}

function deleteNode(nodeId: string) {
  removeNodes([nodeId])
}

function closeContextMenu() {
  contextMenu.value.visible = false
}

/** 暴露给父组件的方法 */
defineExpose({
  nodes,
  edges,
  addNodes,
  removeNodes,
  addEdges,
  fitView: () => {
    // VueFlow实例方法通过useVueFlow获取
  }
})
</script>

<style lang="scss" scoped>
.flow-canvas {
  flex: 1;
  height: 100%;
  position: relative;
  background: var(--color-bg);

  // Vue Flow全局样式覆盖
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
}

.flow-canvas__context-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
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
