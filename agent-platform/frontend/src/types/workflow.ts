// ============================================================
// 工作流类型定义
// 对应后端 Workflow 模块的 DTO 结构
// ============================================================

/** 工作流节点位置 */
export interface NodePosition {
  x: number
  y: number
}

/** 工作流节点类型 */
export type WorkflowNodeType =
  | 'start'
  | 'end'
  | 'input'
  | 'skill'
  | 'agent_ref'
  | 'workflow_ref'
  | 'router'
  | 'condition'
  | 'parallel'
  | 'join'
  | 'human_approval'
  | 'tool_call'
  | 'retrieval'
  | (string & {})

/** 工作流节点 */
export interface WorkflowNode {
  /** 节点唯一标识 */
  id: string
  nodeId?: string
  label?: string
  config?: string
  /** 节点类型 */
  type: WorkflowNodeType
  /** 节点位置 */
  position: NodePosition
  positionX?: number
  positionY?: number
  /** 节点数据 */
  data: NodeData
  /** Vue Flow 节点样式类 */
  class?: string
}

/** 节点数据 */
export interface NodeData {
  /** 节点标签 */
  label: string
  /** 关联的技能ID（仅skill类型） */
  skillId?: number
  /** 关联的Agent ID（仅skill类型） */
  agentId?: number
  /** 关联的Agent名称（仅skill类型） */
  agentName?: string
  /** 关联的工作流ID（仅workflow_ref类型） */
  workflowId?: number
  /** 关联的工作流名称（仅workflow_ref类型） */
  workflowName?: string
  /** 引用来源类型 */
  sourceType?: string
  /** 节点描述 */
  description?: string
  /** 节点配置JSON */
  config?: string
  inputKey?: string
  inputType?: 'text' | 'textarea' | 'image' | 'video' | 'file' | string
  required?: boolean
  defaultValue?: string
  value?: string
  placeholder?: string
  accept?: string
  systemPrompt?: string
  promptTemplate?: string
  model?: string
  temperature?: number
  outputKey?: string
  nodeAlias?: string
  inputParams?: SkillInputParam[]
  inputMappings?: Record<string, string>
  outputAlias?: Record<string, string>
  descriptionVisible?: boolean
  descriptionEditable?: boolean
  promptConfigVisible?: boolean
  promptConfigEditable?: boolean
  /** 检索节点（retrieval）：知识库绑定 + 查询（阶段5 RAG，v6 §2.4） */
  kbId?: number
  kbIds?: number[]
  query?: string
  /** 运行态状态 */
  runtimeStatus?: 'running' | 'success' | 'failed' | 'waiting'
  /** 运行态元数据 */
  runtimeMeta?: Record<string, unknown>
}

export interface SkillInputParam {
  key: string
  label?: string
  description?: string
  required?: boolean
  type?: string
  defaultValue?: string
}

/** 后端工作流节点请求结构 */
export interface WorkflowNodeRequest {
  nodeId: string
  type: WorkflowNodeType
  positionX: number
  positionY: number
  label: string
  config?: string
}

/** 工作流连线 */
export interface WorkflowEdge {
  /** 连线唯一标识 */
  id: string
  /** 源节点ID */
  source: string
  /** 目标节点ID */
  target: string
  /** 源端口 */
  sourceHandle?: string
  /** 目标端口 */
  targetHandle?: string
  sourceNodeId?: string
  targetNodeId?: string
  type?: string
  animated?: boolean
  style?: Record<string, string | number>
  class?: string
  domAttributes?: Record<string, string | number | boolean>
}

export interface WorkflowEdgeRequest {
  sourceNodeId: string
  targetNodeId: string
  sourceHandle?: string
  targetHandle?: string
  label?: string
  condition?: string
}

/** 工作流状态 */
export type WorkflowStatus = 'draft' | 'published' | 'archived'

/** 工作流实体 */
export interface Workflow {
  id: number
  name: string
  description: string | null
  status: WorkflowStatus
  ownerId?: number
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  createdAt: string
  updatedAt: string
}

/** 创建工作流请求 */
export interface CreateWorkflowRequest {
  name: string
  description?: string
  nodes?: WorkflowNodeRequest[]
  edges?: WorkflowEdgeRequest[]
}

/** 更新工作流请求 */
export interface UpdateWorkflowRequest {
  name?: string
  description?: string
  status?: WorkflowStatus
  nodes?: WorkflowNodeRequest[]
  edges?: WorkflowEdgeRequest[]
}

/** 工作流列表项 */
export interface WorkflowListItem {
  id: number
  name: string
  description: string | null
  status: WorkflowStatus
  nodeCount: number
  createdAt: string
  updatedAt: string
}
