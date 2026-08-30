import type { ExecutionEvent } from '@/api/execution'

export interface WorkflowRunSummary {
  executionId: string | null
  status: string
  totalEvents: number
  nodeEvents: number
  completedNodes: number
  failedNodes: number
}

export function summarizeWorkflowRun(events: ExecutionEvent[]): WorkflowRunSummary {
  const lastEvent = events[events.length - 1]
  const nodeEvents = events.filter(event => event.type.startsWith('NODE_'))
  return {
    executionId: lastEvent?.executionId || events[0]?.executionId || null,
    status: lastEvent?.status || 'UNKNOWN',
    totalEvents: events.length,
    nodeEvents: nodeEvents.length,
    completedNodes: events.filter(event => event.type === 'NODE_COMPLETED').length,
    failedNodes: events.filter(event => event.type === 'EXECUTION_FAILED').length
  }
}
