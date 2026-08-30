import { describe, expect, it, vi } from 'vitest'
import { agentApi, type AgentPermissionSaveRequest, type SkillSaveRequest } from './agent'
import request from './request'

vi.mock('./request', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}))

describe('agentApi skill management', () => {
  it('calls skill create, update and delete endpoints', async () => {
    const payload: SkillSaveRequest = {
      name: '需求分析',
      description: '分析用户需求',
      type: 'SEQUENCE',
      config: '{}',
      sortOrder: 1,
      steps: [
        {
          stepOrder: 1,
          name: '读取输入',
          action: 'parse_input',
          config: '{}'
        }
      ]
    }
    vi.mocked(request.post).mockResolvedValue({ data: { code: 200, data: {} } })
    vi.mocked(request.put).mockResolvedValue({ data: { code: 200, data: {} } })
    vi.mocked(request.delete).mockResolvedValue({ data: { code: 200, data: null } })

    await agentApi.createSkill(3, payload)
    await agentApi.updateSkill(9, payload)
    await agentApi.deleteSkill(9)

    expect(request.post).toHaveBeenCalledWith('/agents/3/skills', payload)
    expect(request.put).toHaveBeenCalledWith('/skills/9', payload)
    expect(request.delete).toHaveBeenCalledWith('/skills/9')
  })
})

describe('agentApi permissions', () => {
  it('calls agent access and permission endpoints', async () => {
    const payload: AgentPermissionSaveRequest[] = [
      {
        userId: 7,
        canUse: true,
        canReadPrompt: true,
        canCopy: false
      }
    ]
    vi.mocked(request.get).mockResolvedValue({ data: { code: 200, data: {} } })
    vi.mocked(request.put).mockResolvedValue({ data: { code: 200, data: [] } })

    await agentApi.getAgentAccess(3)
    await agentApi.listAgentPermissions(3)
    await agentApi.saveAgentPermissions(3, payload)

    expect(request.get).toHaveBeenCalledWith('/agents/3/access')
    expect(request.get).toHaveBeenCalledWith('/agents/3/permissions')
    expect(request.put).toHaveBeenCalledWith('/agents/3/permissions', payload)
  })

  it('calls agent copy endpoint', async () => {
    const payload = {
      name: 'Copied Agent',
      description: 'copy for editing',
      groupId: 2
    }
    vi.mocked(request.post).mockResolvedValue({ data: { code: 200, data: {} } })

    await agentApi.copyAgent(3, payload)

    expect(request.post).toHaveBeenCalledWith('/agents/3/copy', payload)
  })
})
