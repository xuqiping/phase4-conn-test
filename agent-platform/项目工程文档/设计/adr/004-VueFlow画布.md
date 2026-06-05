# ADR-004: 工作流画布引擎选型

## 元信息

| 项目 | 内容 |
|------|------|
| 状态 | 已采纳 (Accepted) |
| 日期 | 2026-05-25 |
| 决策者 | 架构组 |
| 上下文 | 工作流可视化编辑器核心引擎选择 |

---

## 背景

多 Agent 智能体平台的工作流编辑器是核心交互界面，需要支持：

### 功能需求

1. **节点操作**：拖拽添加节点、移动节点位置、删除节点、选中节点查看/编辑配置
2. **连线操作**：从节点端口拖出连线连接到另一个节点、删除连线、条件边标注
3. **画布操作**：缩放、平移、适应屏幕、小地图导航
4. **节点类型**：起始节点、结束节点、Agent 节点、条件节点、并行节点、循环节点
5. **数据同步**：画布操作实时同步到后端（防抖保存）
6. **主题适配**：支持 3 套暗色主题切换

### 非功能需求

1. **性能**：支持 50+ 节点的工作流流畅编辑
2. **扩展性**：可以自定义节点样式和端口配置
3. **可维护性**：API 清晰，社区活跃，文档完善

---

## 候选方案

### 方案A：Vue Flow — 已采纳

Vue Flow 是基于 Vue 3 的流程图库，从 React Flow 移植而来。

#### 核心特性

- **Vue 3 原生**：使用 Composition API，与 Vue 3 生态深度集成
- **节点自定义**：通过 Vue 组件定义自定义节点，支持任意复杂度
- **边自定义**：自定义边样式、动画、标签
- **交互内置**：拖拽、缩放、平移、选中、多选开箱即用
- **小地图**：内置 MiniMap 组件
- **事件系统**：丰富的事件钩子（onNodesChange, onEdgesChange, onConnect 等）
- **状态管理**：使用 Pinia 风格的 store，可与应用 Pinia store 集成
- **TypeScript**：完整的 TypeScript 类型支持

#### 技术指标

| 指标 | 数值 |
|------|------|
| npm 周下载量 | 50K+ |
| GitHub Stars | 3K+ |
| 包体积 (gzip) | ~45KB |
| 最近更新 | 活跃维护 |
| Vue 版本要求 | Vue 3.3+ |
| License | MIT |

#### 示例代码

```vue
<template>
  <VueFlow
    v-model:nodes="nodes"
    v-model:edges="edges"
    :default-viewport="{ zoom: 1, x: 0, y: 0 }"
    :min-zoom="0.2"
    :max-zoom="2"
    @nodes-change="onNodesChange"
    @edges-change="onEdgesChange"
    @connect="onConnect"
  >
    <MiniMap />
    <Controls />
    <Background />
  </VueFlow>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { VueFlow, MiniMap, Controls, Background } from '@vue-flow/core'

const nodes = ref([
  { id: '1', type: 'start', position: { x: 250, y: 0 }, data: { label: '开始' } },
  { id: '2', type: 'agent', position: { x: 250, y: 150 }, data: { label: 'Agent', agentId: 1 } },
  { id: '3', type: 'end', position: { x: 250, y: 300 }, data: { label: '结束' } },
])

const edges = ref([
  { id: 'e1-2', source: '1', target: '2' },
  { id: 'e2-3', source: '2', target: '3' },
])
</script>
```

### 方案B：jsPlumb Community Edition

jsPlumb 是一个成熟的流程图连线库，提供 Community 和 Toolkit 两个版本。

#### 核心特性

- **框架无关**：可以与任何前端框架集成
- **连线能力强**：丰富的连线类型（直线、曲线、流程线）、端点配置
- **拖拽内置**：支持拖拽创建节点和连线
- **社区版免费**：Community Edition 开源免费
- **Toolkit 收费**：高级功能（分组、撤销重做、导入导出）需要商业许可

#### 技术指标

| 指标 | 数值 |
|------|------|
| npm 周下载量 | 100K+ |
| GitHub Stars | 2.5K+ |
| 包体积 (gzip) | ~120KB |
| 最近更新 | 维护模式 |
| 框架要求 | 无（框架无关） |
| License | MIT (Community) / Commercial (Toolkit) |

#### 与 Vue 3 集成挑战

```javascript
// jsPlumb 不提供 Vue 组件，需要手动管理 DOM 和 Vue 响应式的同步
// 以下是一个典型的集成挑战：

// 1. jsPlumb 操作 DOM，Vue 也操作 DOM → 冲突风险
instance.draggable('node-1', {
  // jsPlumb 的拖拽可能干扰 Vue 的响应式更新
})

// 2. 需要手动同步 jsPlumb 状态到 Vue 状态
instance.bind('connection', (info) => {
  // 手动将 jsPlumb 的连接信息同步到 Vue 的响应式数据
  edges.value.push({
    source: info.sourceId,
    target: info.targetId,
  })
})
```

### 方案C：自研画布引擎

基于 Canvas 或 SVG 从零开发工作流画布引擎。

#### 需要自研的模块

| 模块 | 工作量 | 复杂度 |
|------|--------|--------|
| 节点渲染（SVG/Canvas） | 2 周 | 高 |
| 连线渲染（贝塞尔曲线） | 1 周 | 中 |
| 拖拽交互 | 1 周 | 高 |
| 缩放/平移 | 1 周 | 中 |
| 选中/多选 | 0.5 周 | 中 |
| 小地图 | 0.5 周 | 低 |
| 撤销/重做 | 1 周 | 高 |
| 快捷键 | 0.5 周 | 低 |
| 无障碍访问 | 1 周 | 高 |
| 性能优化 | 1 周 | 高 |
| **合计** | **10 周** | - |

#### 优势

- 完全可控，可以按需定制
- 不依赖第三方库，无版本升级风险
- 可以针对特定场景做极致优化

#### 劣势

- 开发周期长（10+ 周）
- 需要处理大量边界情况（浏览器兼容、触摸设备、高 DPI 屏幕等）
- 后续维护成本持续存在
- 缺少社区测试和反馈

---

## 对比决策矩阵

| 评估维度 | 权重 | Vue Flow | jsPlumb CE | 自研 |
|---------|------|---------|-----------|------|
| Vue 3 集成度 | 20% | 10 | 4 | 10 |
| 节点自定义能力 | 15% | 9 | 7 | 10 |
| 开箱即用度 | 15% | 9 | 6 | 1 |
| 包体积 | 10% | 8 | 5 | 10 |
| 社区活跃度 | 10% | 8 | 5 | 0 |
| 文档质量 | 10% | 8 | 7 | 0 |
| 开发周期 | 10% | 10 | 7 | 1 |
| 性能 | 5% | 8 | 7 | 9 |
| 长期维护成本 | 5% | 9 | 6 | 2 |
| **加权总分** | 100% | **9.00** | **5.75** | **5.75** |

---

## 决策理由

选择 Vue Flow 的核心理由：

### 1. Vue 3 原生集成

Vue Flow 使用 Vue 3 Composition API 编写，节点就是 Vue 组件。这意味着：
- 自定义节点可以直接使用 Vue 的响应式系统、组件生命周期
- 不需要手动同步 DOM 状态和 Vue 状态
- 可以直接在节点中使用 Element Plus 组件

### 2. 开发效率最高

Vue Flow 内置了工作流编辑器需要的所有核心功能：
- 节点拖拽、连线、缩放、平移 → 零配置可用
- 小地图、控制面板 → 内置组件
- 事件系统 → 完整的钩子 API

预估集成工作量：**1-2 周**（vs jsPlumb 3-4 周，自研 10+ 周）

### 3. 自定义节点方案

```vue
<!-- AgentNode.vue — 自定义 Agent 类型节点 -->
<template>
  <Handle type="target" :position="Position.Left" />
  <div class="agent-node">
    <div class="agent-header">
      <img :src="data.avatar" class="agent-avatar" />
      <span class="agent-name">{{ data.label }}</span>
    </div>
    <div class="agent-body">
      <el-tag v-for="skill in data.skills" :key="skill.id" size="small">
        {{ skill.name }}
      </el-tag>
    </div>
  </div>
  <Handle type="source" :position="Position.Right" />
</template>

<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'
defineProps<{ data: AgentNodeData }>()
</script>
```

### 4. 主题适配

Vue Flow 的样式可以通过 CSS 变量覆盖，与设计系统的主题切换机制天然兼容：

```scss
// 主题变量覆盖 Vue Flow 默认样式
.vue-flow {
  --vf-node-bg: var(--color-surface);
  --vf-node-color: var(--color-text-primary);
  --vf-handle: var(--color-primary);
  --vf-edge: var(--color-border);
}
```

---

## 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| Vue Flow 版本升级 API 不兼容 | 中 | 中 | 锁定版本，升级前测试 |
| 大型工作流（100+ 节点）性能 | 低 | 中 | 节点虚拟化、懒渲染 |
| 自定义节点样式不满足需求 | 低 | 低 | 可以通过 slot 完全自定义 |
| 社区活跃度下降 | 低 | 高 | 必要时 fork 维护，代码量可控 |

---

## 画布数据结构设计

### 前端数据结构（Vue Flow 格式）

```typescript
// 节点数据类型
interface WorkflowNode {
  id: string              // UUID
  type: 'start' | 'end' | 'agent' | 'condition' | 'parallel' | 'loop'
  position: { x: number, y: number }
  data: NodeData
}

// 不同类型节点的 data
interface StartNodeData { label: string }
interface EndNodeData { label: string }
interface AgentNodeData {
  label: string
  agentId: number
  agentName: string
  avatar: string
  skills: { id: number, name: string }[]
}
interface ConditionNodeData {
  label: string
  expression: string  // JavaScript 表达式
}

// 边数据类型
interface WorkflowEdge {
  id: string
  source: string       // 源节点 ID
  target: string       // 目标节点 ID
  sourceHandle?: string  // 源端口 ID
  targetHandle?: string  // 目标端口 ID
  label?: string        // 边标签（条件边显示表达式）
  type?: 'default' | 'condition'
  data?: { condition?: string }
}
```

### 前后端数据映射

```typescript
// 前端 → 后端
function toBackendNode(node: WorkflowNode): WorkflowNodeDTO {
  return {
    nodeId: node.id,
    type: node.type,
    positionX: node.position.x,
    positionY: node.position.y,
    label: node.data.label,
    config: JSON.stringify(node.data),  // 完整 data 存入 config JSONB
  }
}

// 后端 → 前端
function toFrontendNode(dto: WorkflowNodeDTO): WorkflowNode {
  const data = JSON.parse(dto.config)
  return {
    id: dto.nodeId,
    type: dto.type as any,
    position: { x: dto.positionX, y: dto.positionY },
    data,
  }
}
```
