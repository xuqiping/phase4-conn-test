import type { ExecutionEvent } from '@/api/execution'
import type { WorkflowEdge, WorkflowNode } from '@/types/workflow'

type RuntimeStatus = NonNullable<WorkflowNode['data']['runtimeStatus']>

const STATUS_BY_EVENT: Record<string, RuntimeStatus | undefined> = {
  NODE_STARTED: 'running',
  NODE_COMPLETED: 'success',
  EXECUTION_FAILED: 'failed',
  WAITING_APPROVAL: 'waiting'
}

const META_KEYS = [
  'selectedRoute',
  'selectedTarget',
  'reason',
  'confidence',
  'failedNodeId',
  'errorMessage',
  'approvalKey',
  'approvalCheckpointRef'
]

export function applyRuntimeEventsToNodes(
  nodes: WorkflowNode[],
  events: ExecutionEvent[]
): WorkflowNode[] {
  const runtimeByNodeId = new Map<string, { status: RuntimeStatus; meta: Record<string, unknown> }>()

  for (const event of events) {
    const nodeId = event.nodeId || stringMeta(event, 'failedNodeId')
    const status = STATUS_BY_EVENT[event.type]
    if (!nodeId || !status) continue
    runtimeByNodeId.set(nodeId, {
      status,
      meta: runtimeMeta(event)
    })
  }

  return nodes.map((node) => {
    const runtime = runtimeByNodeId.get(node.id)
    if (!runtime) {
      return {
        ...node,
        class: undefined,
        data: {
          ...node.data,
          runtimeStatus: undefined,
          runtimeMeta: undefined
        }
      }
    }
    return {
      ...node,
      class: `workflow-node--${runtime.status}`,
      data: {
        ...node.data,
        runtimeStatus: runtime.status,
        runtimeMeta: runtime.meta
      }
    }
  })
}

export function applyRuntimeEventsToEdges(
  edges: WorkflowEdge[],
  events: ExecutionEvent[]
): WorkflowEdge[] {
  const activeNodeIds = collectActiveRuntimeNodeIds(events)

  return edges.map(edge => {
    const active = activeNodeIds.has(edge.target)
    const style = active
      ? {
          ...edge.style,
          stroke: '#38bdf8',
          strokeWidth: 3,
          strokeDasharray: '10 6',
          strokeDashoffset: 16
        }
      : {
          ...withoutRuntimeEdgeStyle(edge.style),
          stroke: 'var(--color-primary)',
          strokeWidth: 2
        }
    return {
      ...edge,
      animated: active,
      class: active ? 'workflow-edge--active' : undefined,
      domAttributes: active ? { 'data-runtime-active': 'true' } : undefined,
      style
    }
  })
}

export interface WorkflowVariable {
  key: string
  reference: string
  nodeAlias: string
  sourceNodeId: string
  sourceLabel: string
  sourceType: string
  kind: 'input' | 'output'
}

export function collectWorkflowRunInput(nodes: WorkflowNode[]): Record<string, unknown> {
  const input: Record<string, unknown> = {}
  for (const node of nodes) {
    if (node.type !== 'input' && node.type !== 'start') continue
    const key = node.data.inputKey || node.id
    const value = node.data.value ?? node.data.defaultValue
    if (value !== undefined && value !== null && value !== '') {
      input[key] = value
      if (key === 'message') {
        input.input = value
      }
    }
  }
  return input
}

export function collectAvailableVariables(
  nodes: WorkflowNode[],
  edges: WorkflowEdge[],
  selectedNodeId: string
): WorkflowVariable[] {
  const nodesById = new Map(nodes.map(node => [node.id, node]))
  const upstreamIds = collectUpstreamNodeIds(edges, selectedNodeId)
  const variables: WorkflowVariable[] = []
  const seen = new Set<string>()

  for (const node of nodes) {
    if (!upstreamIds.has(node.id)) continue
    const isInputSource = node.type === 'input' || node.type === 'start'
    const key = isInputSource ? node.data.inputKey : node.data.outputKey
    if (!key) continue
    const nodeAlias = variableNodeAlias(node)
    const reference = `${nodeAlias}.${key}`
    if (seen.has(reference)) continue
    seen.add(reference)
    variables.push({
      key,
      reference,
      nodeAlias,
      sourceNodeId: node.id,
      sourceLabel: node.data.label || node.label || node.id,
      sourceType: String(node.type),
      kind: isInputSource ? 'input' : 'output'
    })
  }

  return variables.sort((left, right) => {
    const leftNode = nodesById.get(left.sourceNodeId)
    const rightNode = nodesById.get(right.sourceNodeId)
    return nodes.indexOf(leftNode as WorkflowNode) - nodes.indexOf(rightNode as WorkflowNode)
  })
}

function variableNodeAlias(node: WorkflowNode): string {
  if (node.type === 'start') {
    return node.data.nodeAlias || 'start'
  }
  return node.data.nodeAlias || slugAlias(node.data.label || node.label || node.id)
}

function slugAlias(value: string): string {
  const normalized = value
    .normalize('NFD')
    .replace(/[^A-Za-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
  if (!normalized || !/^[A-Za-z]/.test(normalized)) {
    return `node_${Math.abs(hashString(value))}`
  }
  return normalized.charAt(0).toLowerCase() + normalized.slice(1)
}

function hashString(value: string): number {
  let hash = 0
  for (let index = 0; index < value.length; index++) {
    hash = ((hash << 5) - hash) + value.charCodeAt(index)
    hash |= 0
  }
  return hash
}

function collectUpstreamNodeIds(edges: WorkflowEdge[], selectedNodeId: string): Set<string> {
  const incomingByTarget = new Map<string, string[]>()
  for (const edge of edges) {
    const list = incomingByTarget.get(edge.target) || []
    list.push(edge.source)
    incomingByTarget.set(edge.target, list)
  }

  const result = new Set<string>()
  const stack = [...(incomingByTarget.get(selectedNodeId) || [])]
  while (stack.length > 0) {
    const nodeId = stack.pop() as string
    if (result.has(nodeId)) continue
    result.add(nodeId)
    stack.push(...(incomingByTarget.get(nodeId) || []))
  }
  return result
}

function runtimeMeta(event: ExecutionEvent): Record<string, unknown> {
  const output = event.metadata || {}
  const meta: Record<string, unknown> = {}
  for (const key of META_KEYS) {
    if (output[key] !== undefined) {
      meta[key] = output[key]
    }
  }
  return meta
}

function stringMeta(event: ExecutionEvent, key: string): string | null {
  const value = event.metadata?.[key]
  return typeof value === 'string' ? value : null
}

function collectActiveRuntimeNodeIds(events: ExecutionEvent[]): Set<string> {
  const statusByNodeId = new Map<string, RuntimeStatus>()

  for (const event of events) {
    const nodeId = event.nodeId || stringMeta(event, 'failedNodeId')
    const status = STATUS_BY_EVENT[event.type]
    if (!nodeId || !status) continue
    statusByNodeId.set(nodeId, status)
  }

  const activeNodeIds = new Set<string>()
  for (const [nodeId, status] of statusByNodeId.entries()) {
    if (status === 'running' || status === 'waiting') {
      activeNodeIds.add(nodeId)
    }
  }
  return activeNodeIds
}

function withoutRuntimeEdgeStyle(style: WorkflowEdge['style'] = {}): WorkflowEdge['style'] {
  const {
    strokeDasharray,
    strokeDashoffset,
    ...rest
  } = style
  void strokeDasharray
  void strokeDashoffset
  return rest
}
