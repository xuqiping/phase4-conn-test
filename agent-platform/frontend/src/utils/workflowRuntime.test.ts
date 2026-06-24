import { describe, expect, it } from 'vitest'
import { applyRuntimeEventsToEdges, applyRuntimeEventsToNodes, collectAvailableVariables, collectWorkflowRunInput } from './workflowRuntime'
import type { WorkflowEdge, WorkflowNode } from '@/types/workflow'
import type { ExecutionEvent } from '@/api/execution'

describe('workflowRuntime', () => {
  it('maps runtime events to workflow node status and branch metadata', () => {
    const nodes: WorkflowNode[] = [
      node('start-1'),
      node('router-1'),
      node('agent-a'),
      node('approval-1')
    ]
    const events: ExecutionEvent[] = [
      event('NODE_COMPLETED', 'SUCCESS', 'start-1'),
      event('NODE_COMPLETED', 'SUCCESS', 'router-1', {
        selectedRoute: 'support',
        selectedTarget: 'agent-a'
      }),
      event('NODE_STARTED', 'RUNNING', 'agent-a'),
      event('WAITING_APPROVAL', 'WAITING_APPROVAL', 'approval-1', {
        approvalKey: 'deploy-prod'
      })
    ]

    const result = applyRuntimeEventsToNodes(nodes, events)

    expect(result.find((item) => item.id === 'start-1')?.data.runtimeStatus).toBe('success')
    expect(result.find((item) => item.id === 'router-1')?.data.runtimeStatus).toBe('success')
    expect(result.find((item) => item.id === 'router-1')?.data.runtimeMeta).toEqual({
      selectedRoute: 'support',
      selectedTarget: 'agent-a'
    })
    expect(result.find((item) => item.id === 'agent-a')?.data.runtimeStatus).toBe('running')
    expect(result.find((item) => item.id === 'approval-1')?.data.runtimeStatus).toBe('waiting')
    expect(result.find((item) => item.id === 'approval-1')?.data.runtimeMeta).toEqual({
      approvalKey: 'deploy-prod'
    })
  })

  it('marks failed execution node as failed', () => {
    const nodes: WorkflowNode[] = [node('agent-a')]
    const events: ExecutionEvent[] = [
      event('EXECUTION_FAILED', 'FAILED', 'agent-a', {
        failedNodeId: 'agent-a',
        errorMessage: 'forced failure'
      })
    ]

    const result = applyRuntimeEventsToNodes(nodes, events)

    expect(result[0].data.runtimeStatus).toBe('failed')
    expect(result[0].data.runtimeMeta).toEqual({
      failedNodeId: 'agent-a',
      errorMessage: 'forced failure'
    })
  })

  it('animates only edges entering the active runtime node', () => {
    const edges: WorkflowEdge[] = [
      edge('input-1', 'skill-a'),
      edge('skill-a', 'skill-b'),
      edge('skill-b', 'skill-c')
    ]
    const events: ExecutionEvent[] = [
      event('NODE_COMPLETED', 'SUCCESS', 'skill-a'),
      event('NODE_STARTED', 'RUNNING', 'skill-b')
    ]

    const result = applyRuntimeEventsToEdges(edges, events)

    expect(result.find(item => item.target === 'skill-a')?.animated).toBe(false)
    expect(result.find(item => item.target === 'skill-b')?.animated).toBe(true)
    expect(result.find(item => item.target === 'skill-b')?.style?.strokeDasharray).toBe('10 6')
    expect(result.find(item => item.target === 'skill-b')?.domAttributes?.['data-runtime-active']).toBe('true')
    expect(result.find(item => item.target === 'skill-c')?.animated).toBe(false)
  })

  it('restores an active edge to solid after the target node completes', () => {
    const edges: WorkflowEdge[] = [
      edge('skill-a', 'skill-b')
    ]

    const runningResult = applyRuntimeEventsToEdges(edges, [
      event('NODE_STARTED', 'RUNNING', 'skill-b')
    ])
    const completedResult = applyRuntimeEventsToEdges(edges, [
      event('NODE_STARTED', 'RUNNING', 'skill-b'),
      event('NODE_COMPLETED', 'SUCCESS', 'skill-b')
    ])

    expect(runningResult[0].animated).toBe(true)
    expect(completedResult[0].animated).toBe(false)
  })

  it('collects run input from input node values and defaults', () => {
    const nodes: WorkflowNode[] = [
      {
        id: 'start-1',
        type: 'start',
        position: { x: 0, y: 0 },
        data: {
          label: 'Start',
          inputKey: 's1',
          value: 'from start'
        }
      },
      {
        id: 'input-prompt',
        type: 'input',
        position: { x: 0, y: 0 },
        data: {
          label: 'Prompt input',
          inputKey: 'prompt',
          defaultValue: 'summarize this'
        }
      },
      {
        id: 'input-message',
        type: 'input',
        position: { x: 0, y: 0 },
        data: {
          label: 'Message input',
          inputKey: 'message',
          value: 'runtime message'
        }
      } as WorkflowNode
    ]

    expect(collectWorkflowRunInput(nodes)).toEqual({
      s1: 'from start',
      prompt: 'summarize this',
      message: 'runtime message',
      input: 'runtime message'
    })
  })

  it('collects input keys and upstream output keys for selected node', () => {
    const nodes: WorkflowNode[] = [
      {
        id: 'input-1',
        type: 'input',
        position: { x: 0, y: 0 },
        data: { label: 'Prompt input', inputKey: 'message' }
      },
      {
        id: 'skill-a',
        type: 'skill',
        position: { x: 0, y: 0 },
        data: { label: 'Analyze', outputKey: 'analysis' } as WorkflowNode['data']
      },
      {
        id: 'skill-b',
        type: 'skill',
        position: { x: 0, y: 0 },
        data: { label: 'Summarize', outputKey: 'summary' } as WorkflowNode['data']
      },
      {
        id: 'skill-c',
        type: 'skill',
        position: { x: 0, y: 0 },
        data: { label: 'Conclusion' }
      }
    ]
    const edges: WorkflowEdge[] = [
      edge('input-1', 'skill-a'),
      edge('skill-a', 'skill-b'),
      edge('skill-b', 'skill-c')
    ]

    expect(collectAvailableVariables(nodes, edges, 'skill-c').map(item => item.key)).toEqual([
      'message',
      'analysis',
      'summary'
    ])
    expect(collectAvailableVariables(nodes, edges, 'skill-b').map(item => item.key)).toEqual([
      'message',
      'analysis'
    ])
  })

  it('collects stable node scoped variable references', () => {
    const nodes: WorkflowNode[] = [
      {
        id: 'input-1',
        type: 'input',
        position: { x: 0, y: 0 },
        data: { label: 'Text input', nodeAlias: 'textInput', inputKey: 'message' } as WorkflowNode['data']
      },
      {
        id: 'skill-a',
        type: 'skill',
        position: { x: 0, y: 0 },
        data: { label: 'A', nodeAlias: 'summaryA', outputKey: 'summary' } as WorkflowNode['data']
      },
      {
        id: 'skill-b',
        type: 'skill',
        position: { x: 0, y: 0 },
        data: { label: 'B', nodeAlias: 'summaryB', outputKey: 'summary' } as WorkflowNode['data']
      },
      {
        id: 'skill-c',
        type: 'skill',
        position: { x: 0, y: 0 },
        data: { label: 'C' }
      }
    ]
    const edges: WorkflowEdge[] = [
      edge('input-1', 'skill-a'),
      edge('skill-a', 'skill-b'),
      edge('skill-b', 'skill-c')
    ]

    expect(collectAvailableVariables(nodes, edges, 'skill-c').map(item => item.reference)).toEqual([
      'textInput.message',
      'summaryA.summary',
      'summaryB.summary'
    ])
  })

  it('collects start node input key as an upstream variable', () => {
    const nodes: WorkflowNode[] = [
      {
        id: 'start-1',
        type: 'start',
        position: { x: 0, y: 0 },
        data: { label: '开始', nodeAlias: 'startNode', inputKey: 's1' } as WorkflowNode['data']
      },
      {
        id: 'skill-a',
        type: 'skill',
        position: { x: 0, y: 0 },
        data: { label: 'A', outputKey: 'summary' } as WorkflowNode['data']
      }
    ]
    const edges: WorkflowEdge[] = [
      edge('start-1', 'skill-a')
    ]

    expect(collectAvailableVariables(nodes, edges, 'skill-a').map(item => item.reference)).toEqual([
      'startNode.s1'
    ])
  })

  it('uses start as the default alias for start node variables', () => {
    const nodes: WorkflowNode[] = [
      {
        id: 'start-1',
        type: 'start',
        position: { x: 0, y: 0 },
        data: { label: '开始', inputKey: 'ccc' } as WorkflowNode['data']
      },
      {
        id: 'skill-a',
        type: 'skill',
        position: { x: 0, y: 0 },
        data: { label: 'A', outputKey: 'summary' } as WorkflowNode['data']
      }
    ]
    const edges: WorkflowEdge[] = [
      edge('start-1', 'skill-a')
    ]

    expect(collectAvailableVariables(nodes, edges, 'skill-a').map(item => item.reference)).toEqual([
      'start.ccc'
    ])
  })
})

function node(id: string): WorkflowNode {
  return {
    id,
    type: 'skill',
    position: { x: 0, y: 0 },
    data: { label: id }
  }
}

function edge(source: string, target: string): WorkflowEdge {
  return {
    id: `${source}-${target}`,
    source,
    target
  }
}

function event(
  type: string,
  status: string,
  nodeId: string,
  metadata: Record<string, unknown> = {}
): ExecutionEvent {
  return {
    executionId: '1',
    rootExecutionId: '1',
    nodeId,
    type,
    status,
    metadata
  }
}
