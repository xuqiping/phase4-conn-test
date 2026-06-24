import { describe, it, expect } from 'vitest'
import { toWorkflowNodeRequest, toFlowNode } from './workflowMapper'
import type { WorkflowNode } from '@/types/workflow'

describe('workflowMapper', () => {
  it('serializes agent_ref data into backend node config', () => {
    const result = toWorkflowNodeRequest({
      id: 'agent_ref-1',
      type: 'agent_ref',
      position: { x: 120, y: 240 },
      data: {
        label: '文案 Agent',
        agentId: 3,
        agentName: '文案助手'
      }
    })

    expect(result).toEqual({
      nodeId: 'agent_ref-1',
      type: 'AGENT_REF',
      positionX: 120,
      positionY: 240,
      label: '文案 Agent',
      config: JSON.stringify({ agentId: 3, agentName: '文案助手' })
    })
  })

  it('serializes workflow_ref data into backend node config', () => {
    const result = toWorkflowNodeRequest({
      id: 'workflow_ref-1',
      type: 'workflow_ref',
      position: { x: 320, y: 240 },
      data: {
        label: '审核流程',
        workflowId: 7,
        workflowName: '内容审核'
      }
    })

    expect(result.config).toBe(JSON.stringify({ workflowId: 7, workflowName: '内容审核' }))
  })

  it('keeps skill node config compatible with existing backend fields', () => {
    const result = toWorkflowNodeRequest({
      id: 'skill-1',
      type: 'skill',
      position: { x: 80, y: 160 },
      data: {
        label: '写作技能',
        skillId: 5,
        agentId: 3,
        agentName: '文案助手',
        description: '生成文案'
      }
    })

    expect(result.config).toBe(JSON.stringify({
      skillId: 5,
      agentId: 3,
      agentName: '文案助手',
      description: '生成文案'
    }))
  })

  it('serializes editable skill prompt fields into backend node config', () => {
    const result = toWorkflowNodeRequest({
      id: 'skill-1',
      type: 'skill',
      position: { x: 80, y: 160 },
      data: {
        label: '联调摘要生成',
        skillId: 1,
        agentId: 4,
        systemPrompt: '新的系统提示词',
        promptTemplate: '新的用户提示词：{{message}}',
        model: 'doubao-seed-2.0-code',
        temperature: 0.1,
        outputKey: 'summary'
      }
    })

    expect(JSON.parse(result.config || '{}')).toMatchObject({
      skillId: 1,
      agentId: 4,
      systemPrompt: '新的系统提示词',
      promptTemplate: '新的用户提示词：{{message}}',
      model: 'doubao-seed-2.0-code',
      temperature: 0.1,
      outputKey: 'summary'
    })
  })

  it('keeps public skill input parameters in node config', () => {
    const result = toWorkflowNodeRequest({
      id: 'skill-1',
      type: 'skill',
      position: { x: 80, y: 160 },
      data: {
        label: '联调摘要生成',
        skillId: 1,
        inputParams: [
          { key: 'summary', label: '摘要', description: '上游联调摘要', required: true },
          { key: 'testResult', label: '测试结果', description: '接口测试输出', required: false }
        ],
        inputMappings: {
          summary: '{{summaryA.summary}}'
        }
      }
    })

    expect(JSON.parse(result.config || '{}')).toMatchObject({
      skillId: 1,
      inputParams: [
        { key: 'summary', label: '摘要', description: '上游联调摘要', required: true },
        { key: 'testResult', label: '测试结果', description: '接口测试输出', required: false }
      ],
      inputMappings: {
        summary: '{{summaryA.summary}}'
      }
    })
  })

  it('hydrates backend node config back into flow node data', () => {
    const backendNode: WorkflowNode = {
      id: 'agent_ref-1',
      type: 'agent_ref',
      position: { x: 120, y: 240 },
      data: {
        label: '文案 Agent',
        config: JSON.stringify({ agentId: 3, agentName: '文案助手' })
      }
    }

    const result = toFlowNode(backendNode)

    expect(result.data).toEqual({
      label: '文案 Agent',
      config: JSON.stringify({ agentId: 3, agentName: '文案助手' }),
      agentId: 3,
      agentName: '文案助手'
    })
  })
})
