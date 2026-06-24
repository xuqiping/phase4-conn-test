import { describe, expect, it } from 'vitest'
import { summarizeWorkflowRun } from './workflowRunSummary'
import type { ExecutionEvent } from '@/api/execution'

describe('workflowRunSummary', () => {
  it('summarizes completed workflow run events', () => {
    const events: ExecutionEvent[] = [
      event('1', 'EXECUTION_STARTED', 'RUNNING', null),
      event('1', 'NODE_STARTED', 'RUNNING', 'start-1'),
      event('1', 'NODE_COMPLETED', 'SUCCESS', 'start-1'),
      event('1', 'EXECUTION_COMPLETED', 'SUCCESS', null)
    ]

    expect(summarizeWorkflowRun(events)).toEqual({
      executionId: '1',
      status: 'SUCCESS',
      totalEvents: 4,
      nodeEvents: 2,
      completedNodes: 1,
      failedNodes: 0
    })
  })

  it('summarizes failed workflow run events', () => {
    const events: ExecutionEvent[] = [
      event('2', 'NODE_STARTED', 'RUNNING', 'agent-1'),
      event('2', 'EXECUTION_FAILED', 'FAILED', 'agent-1')
    ]

    expect(summarizeWorkflowRun(events)).toEqual({
      executionId: '2',
      status: 'FAILED',
      totalEvents: 2,
      nodeEvents: 1,
      completedNodes: 0,
      failedNodes: 1
    })
  })
})

function event(
  executionId: string,
  type: string,
  status: string,
  nodeId: string | null
): ExecutionEvent {
  return {
    executionId,
    rootExecutionId: executionId,
    nodeId,
    type,
    status,
    metadata: {}
  }
}
