import { describe, expect, it } from 'vitest'
import { parseExecutionTimeline } from './executionTimeline'

describe('executionTimeline', () => {
  it('parses runtime node logs into timeline items', () => {
    const timeline = parseExecutionTimeline(JSON.stringify([
      {
        type: 'NODE_COMPLETED',
        status: 'SUCCESS',
        nodeId: 'router-1',
        metadata: {
          checkpointRef: 'checkpoint-1',
          selectedRoute: 'support',
          selectedTarget: 'agent-1'
        },
        timestamp: '2026-06-04T10:00:00Z'
      },
      {
        type: 'EXECUTION_FAILED',
        status: 'FAILED',
        nodeId: 'agent-1',
        metadata: {
          failedNodeId: 'agent-1',
          errorMessage: 'forced failure'
        }
      }
    ]))

    expect(timeline).toEqual([
      {
        key: '0-NODE_COMPLETED-router-1',
        type: 'NODE_COMPLETED',
        status: 'SUCCESS',
        nodeId: 'router-1',
        checkpointRef: 'checkpoint-1',
        selectedRoute: 'support',
        selectedTarget: 'agent-1',
        failedNodeId: null,
        errorMessage: null,
        approvalKey: null,
        inputPayload: null,
        outputPayload: null,
        outputText: null,
        agentName: null,
        selectedSkillIds: [],
        stepOutputs: [],
        timestamp: '2026-06-04T10:00:00Z'
      },
      {
        key: '1-EXECUTION_FAILED-agent-1',
        type: 'EXECUTION_FAILED',
        status: 'FAILED',
        nodeId: 'agent-1',
        checkpointRef: null,
        selectedRoute: null,
        selectedTarget: null,
        failedNodeId: 'agent-1',
        errorMessage: 'forced failure',
        approvalKey: null,
        inputPayload: null,
        outputPayload: null,
        outputText: null,
        agentName: null,
        selectedSkillIds: [],
        stepOutputs: [],
        timestamp: null
      }
    ])
  })

  it('returns an empty timeline for invalid node logs', () => {
    expect(parseExecutionTimeline('{bad-json')).toEqual([])
    expect(parseExecutionTimeline(null)).toEqual([])
  })

  it('parses agent and skill execution details from node output', () => {
    const timeline = parseExecutionTimeline(JSON.stringify([
      {
        type: 'NODE_COMPLETED',
        status: 'SUCCESS',
        nodeId: 'agent-1',
        input: {
          message: 'write docs',
          summary: 'outline'
        },
        output: {
          text: 'final docs',
          agentName: 'writer',
          selectedSkillIds: [12, 13],
          stepOutputs: [
            { skillId: 12, output: 'outline' },
            { skillId: 13, output: 'final docs' }
          ]
        }
      }
    ]))

    expect(timeline[0].inputPayload).toEqual({ message: 'write docs', summary: 'outline' })
    expect(timeline[0].outputPayload).toEqual({
      text: 'final docs',
      agentName: 'writer',
      selectedSkillIds: [12, 13],
      stepOutputs: [
        { skillId: 12, output: 'outline' },
        { skillId: 13, output: 'final docs' }
      ]
    })
    expect(timeline[0].outputText).toBe('final docs')
    expect(timeline[0].agentName).toBe('writer')
    expect(timeline[0].selectedSkillIds).toEqual([12, 13])
    expect(timeline[0].stepOutputs).toEqual([
      { skillId: 12, output: 'outline' },
      { skillId: 13, output: 'final docs' }
    ])
  })

  it('ignores malformed skill ids and step outputs without breaking timeline parsing', () => {
    const timeline = parseExecutionTimeline(JSON.stringify([
      {
        type: 'NODE_COMPLETED',
        status: 'SUCCESS',
        nodeId: 'skill-1',
        output: {
          selectedSkillIds: [12, 'bad', 13],
          stepOutputs: [{ skillId: 12, output: 'ok' }, null, 'bad']
        }
      }
    ]))

    expect(timeline[0].selectedSkillIds).toEqual([12, 13])
    expect(timeline[0].stepOutputs).toEqual([{ skillId: 12, output: 'ok' }])
  })
})
