import type { NodeData, WorkflowEdge, WorkflowEdgeRequest, WorkflowNode, WorkflowNodeRequest } from '@/types/workflow'

const CONFIG_KEYS: Array<keyof NodeData> = [
  'skillId',
  'agentId',
  'agentName',
  'workflowId',
  'workflowName',
  'description',
  'inputKey',
  'inputType',
  'required',
  'defaultValue',
  'value',
  'placeholder',
  'accept',
  'systemPrompt',
  'promptTemplate',
  'model',
  'temperature',
  'outputKey',
  'nodeAlias',
  'inputParams',
  'inputMappings',
  'outputAlias',
  'descriptionVisible',
  'descriptionEditable',
  'kbId',
  'kbIds',
  'query',
  'questionTemplate',
  'options'
]

const FLOW_TO_BACKEND_TYPE: Record<string, string> = {
  start: 'START',
  end: 'END',
  input: 'INPUT',
  skill: 'SKILL',
  agent_ref: 'AGENT_REF',
  workflow_ref: 'WORKFLOW_REF',
  router: 'ROUTER',
  condition: 'CONDITION',
  parallel: 'PARALLEL',
  join: 'JOIN',
  human_approval: 'HUMAN_APPROVAL',
  human_input: 'HUMAN_INPUT',
  tool_call: 'TOOL_CALL',
  retrieval: 'RETRIEVAL'
}

const BACKEND_TO_FLOW_TYPE = Object.fromEntries(
  Object.entries(FLOW_TO_BACKEND_TYPE).map(([flowType, backendType]) => [backendType, flowType])
) as Record<string, string>

export function toWorkflowNodeRequest(node: Pick<WorkflowNode, 'id' | 'type' | 'position' | 'data'>): WorkflowNodeRequest {
  const data = normalizeNodeData(node.type, node.data as NodeData, node.id)
  const config = buildConfig(data)

  return {
    nodeId: node.id,
    type: toBackendNodeType(node.type),
    positionX: node.position.x,
    positionY: node.position.y,
    label: data.label,
    config: Object.keys(config).length > 0 ? JSON.stringify(config) : undefined
  }
}

export function toFlowNode(node: WorkflowNode): WorkflowNode {
  const nodeData = node.data || { label: node.label || '', config: node.config }
  const parsedConfig = parseConfig(nodeData.config)
  const type = toFlowNodeType(node.type)
  const id = node.nodeId || node.id
  const data = normalizeNodeData(type, {
    ...nodeData,
    ...parsedConfig
  }, id)

  return {
    ...node,
    id,
    type,
    position: node.position || {
      x: node.positionX || 0,
      y: node.positionY || 0
    },
    data
  }
}

export function toFlowEdge(edge: WorkflowEdge): WorkflowEdge {
  return {
    id: String(edge.id || `edge-${edge.sourceNodeId || edge.source}-${edge.targetNodeId || edge.target}`),
    source: edge.sourceNodeId || edge.source,
    target: edge.targetNodeId || edge.target,
    sourceHandle: edge.sourceHandle || undefined,
    targetHandle: edge.targetHandle || undefined,
    type: 'smoothstep',
    animated: false,
    style: { stroke: 'var(--color-primary)', strokeWidth: 2 }
  }
}

export function toWorkflowEdgeRequest(edge: Pick<WorkflowEdge, 'source' | 'target' | 'sourceHandle' | 'targetHandle'>): WorkflowEdgeRequest {
  return {
    sourceNodeId: edge.source,
    targetNodeId: edge.target,
    sourceHandle: edge.sourceHandle || undefined,
    targetHandle: edge.targetHandle || undefined
  }
}

function toBackendNodeType(type?: string): WorkflowNodeRequest['type'] {
  const normalized = type || 'skill'
  return (FLOW_TO_BACKEND_TYPE[normalized] || normalized.toUpperCase()) as WorkflowNodeRequest['type']
}

function toFlowNodeType(type: string): WorkflowNode['type'] {
  const normalized = type.toUpperCase()
  return (BACKEND_TO_FLOW_TYPE[normalized] || type.toLowerCase()) as WorkflowNode['type']
}

function buildConfig(data: NodeData): Partial<NodeData> {
  const config: Partial<NodeData> = {}
  for (const key of CONFIG_KEYS) {
    const value = data[key]
    if (value !== undefined && value !== null && value !== '') {
      config[key] = value as never
    }
  }
  return config
}

function parseConfig(config?: string): Partial<NodeData> {
  if (!config) return {}
  try {
    const parsed = JSON.parse(config)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

function normalizeNodeData(type: string | undefined, data: NodeData, id?: string): NodeData {
  if (type !== 'start') return data
  const nodeAlias = isLegacyStartAlias(data.nodeAlias, id) ? 'start' : data.nodeAlias || 'start'
  return {
    ...data,
    nodeAlias
  }
}

function isLegacyStartAlias(alias?: string, id?: string): boolean {
  if (!alias) return false
  if (alias === 'node_776907') return true
  if (id && alias === `node_${id.replace(/[^a-zA-Z0-9_]/g, '_')}`) return true
  return /^node_start_?\d*$/i.test(alias)
}
