import { describe, expect, it } from 'vitest'
import { toFlowNode, toWorkflowEdgeRequest, toWorkflowNodeRequest } from './workflowMapper'
import type { WorkflowNode } from '@/types/workflow'

describe('workflowMapper node type mapping', () => {
  it('serializes Vue Flow node types to backend runtime node types', () => {
    expect(toWorkflowNodeRequest({
      id: 'start-1',
      type: 'start',
      position: { x: 0, y: 0 },
      data: { label: 'Start' }
    }).type).toBe('START')

    expect(toWorkflowNodeRequest({
      id: 'skill-1',
      type: 'skill',
      position: { x: 0, y: 0 },
      data: { label: 'Skill', skillId: 1 }
    }).type).toBe('SKILL')

    expect(toWorkflowNodeRequest({
      id: 'agent-ref-1',
      type: 'agent_ref',
      position: { x: 0, y: 0 },
      data: { label: 'Agent', agentId: 1 }
    }).type).toBe('AGENT_REF')

    expect(toWorkflowNodeRequest({
      id: 'workflow-ref-1',
      type: 'workflow_ref',
      position: { x: 0, y: 0 },
      data: { label: 'Workflow', workflowId: 2 }
    }).type).toBe('WORKFLOW_REF')

    const inputRequest = toWorkflowNodeRequest({
      id: 'input-prompt',
      type: 'input',
      position: { x: 0, y: 0 },
      data: {
        label: 'Prompt',
        inputKey: 'prompt',
        inputType: 'textarea',
        required: true
      }
    })
    expect(inputRequest.type).toBe('INPUT')
    expect(JSON.parse(inputRequest.config || '{}')).toMatchObject({
      inputKey: 'prompt',
      inputType: 'textarea',
      required: true
    })
  })

  it('hydrates backend runtime node types to Vue Flow node types', () => {
    const backendNode: WorkflowNode = {
      id: 'agent-ref-1',
      type: 'AGENT_REF',
      position: { x: 0, y: 0 },
      data: { label: 'Agent', config: '{"agentId":1}' }
    }

    expect(toFlowNode(backendNode).type).toBe('agent_ref')
  })

  it('hydrates backend input nodes with input config', () => {
    const backendNode: WorkflowNode = {
      id: 'input-prompt',
      type: 'INPUT',
      position: { x: 0, y: 0 },
      data: {
        label: 'Prompt',
        config: '{"inputKey":"prompt","inputType":"textarea","required":true}'
      }
    }

    const flowNode = toFlowNode(backendNode)

    expect(flowNode.type).toBe('input')
    expect(flowNode.data.inputKey).toBe('prompt')
    expect(flowNode.data.inputType).toBe('textarea')
    expect(flowNode.data.required).toBe(true)
  })

  it('serializes human_input nodes with question config', () => {
    const request = toWorkflowNodeRequest({
      id: 'human-1',
      type: 'human_input',
      position: { x: 0, y: 0 },
      data: {
        label: '收集姓名',
        inputKey: 'user_name',
        inputType: 'text',
        questionTemplate: '你叫什么名字？',
        required: true
      }
    })

    expect(request.type).toBe('HUMAN_INPUT')
    expect(JSON.parse(request.config || '{}')).toMatchObject({
      inputKey: 'user_name',
      inputType: 'text',
      questionTemplate: '你叫什么名字？',
      required: true
    })
  })

  it('serializes human_input select options as string array', () => {
    const request = toWorkflowNodeRequest({
      id: 'human-2',
      type: 'human_input',
      position: { x: 0, y: 0 },
      data: {
        label: '优先级',
        inputKey: 'priority',
        inputType: 'select',
        questionTemplate: '优先级？',
        options: ['低', '中', '高']
      }
    })

    const config = JSON.parse(request.config || '{}')
    expect(config.options).toEqual(['低', '中', '高'])
  })

  it('hydrates backend HUMAN_INPUT nodes to human_input with config', () => {
    const backendNode: WorkflowNode = {
      id: 'human-1',
      type: 'HUMAN_INPUT',
      position: { x: 0, y: 0 },
      data: {
        label: '收集姓名',
        config: '{"inputKey":"user_name","inputType":"select","questionTemplate":"优先级？","options":["低","中","高"]}'
      }
    }

    const flowNode = toFlowNode(backendNode)

    expect(flowNode.type).toBe('human_input')
    expect(flowNode.data.inputKey).toBe('user_name')
    expect(flowNode.data.inputType).toBe('select')
    expect(flowNode.data.questionTemplate).toBe('优先级？')
    expect(flowNode.data.options).toEqual(['低', '中', '高'])
  })

  it('normalizes legacy start aliases to start', () => {
    const backendNode: WorkflowNode = {
      id: 'start-1',
      type: 'START',
      position: { x: 0, y: 0 },
      data: {
        label: 'Start',
        config: '{"nodeAlias":"node_start_1","inputKey":"ccc"}'
      }
    }

    const flowNode = toFlowNode(backendNode)
    const request = toWorkflowNodeRequest(flowNode)

    expect(flowNode.data.nodeAlias).toBe('start')
    expect(JSON.parse(request.config || '{}')).toMatchObject({
      nodeAlias: 'start',
      inputKey: 'ccc'
    })
  })

  it('serializes Vue Flow edges to backend edge fields', () => {
    expect(toWorkflowEdgeRequest({
      source: 'start-1',
      target: 'skill-1',
      sourceHandle: 'out',
      targetHandle: 'in'
    })).toEqual({
      sourceNodeId: 'start-1',
      targetNodeId: 'skill-1',
      sourceHandle: 'out',
      targetHandle: 'in'
    })
  })
})
