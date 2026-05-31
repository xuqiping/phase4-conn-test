// ============================================================
// 工作流类型定义
// 对应后端 Workflow 模块的 DTO 结构
// ============================================================

/** 工作流节点位置 */
export interface NodePosition {
  x: number
  y: number
}

/** 工作流节点 */
export interface WorkflowNode {
  /** 节点唯一标识 */
  id: string
  /** 节点类型：start / end / skill */
  type: string
  /** 节点位置 */
  position: NodePosition
  /** 节点数据 */
  data: NodeData
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
  /** 节点描述 */
  description?: string
  /** 节点配置JSON */
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
}

/** 工作流状态 */
export type WorkflowStatus = 'draft' | 'published' | 'archived'

/** 工作流实体 */
export interface Workflow {
  id: number
  name: string
  description: string | null
  status: WorkflowStatus
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  createdAt: string
  updatedAt: string
}

/** 创建工作流请求 */
export interface CreateWorkflowRequest {
  name: string
  description?: string
  nodes?: WorkflowNode[]
  edges?: WorkflowEdge[]
}

/** 更新工作流请求 */
export interface UpdateWorkflowRequest {
  name?: string
  description?: string
  status?: WorkflowStatus
  nodes?: WorkflowNode[]
  edges?: WorkflowEdge[]
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
