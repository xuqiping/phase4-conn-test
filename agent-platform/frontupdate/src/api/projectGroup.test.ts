import { beforeEach, describe, expect, it, vi } from 'vitest'
import request from './request'
import { projectGroupApi } from './projectGroup'

vi.mock('./request', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}))

describe('projectGroupApi 成员权限端点（17x#2 V139）', () => {
  beforeEach(() => vi.clearAllMocks())

  it('任免角色 PUT /{id}/members/{uid}/role', async () => {
    vi.mocked(request.put).mockResolvedValue({ data: { data: null } } as never)
    await projectGroupApi.updateMemberRole(10, 2, 'MANAGER')
    expect(request.put).toHaveBeenCalledWith('/project-groups/10/members/2/role', { role: 'MANAGER' })
  })

  it('功能开关 PUT /{id}/members/{uid}/kinds（null=不限 / []=全禁透传）', async () => {
    vi.mocked(request.put).mockResolvedValue({ data: { data: null } } as never)
    await projectGroupApi.updateMemberKinds(10, 2, ['CHAT', 'VIDEO'])
    expect(request.put).toHaveBeenCalledWith('/project-groups/10/members/2/kinds', { allowedKinds: ['CHAT', 'VIDEO'] })
    await projectGroupApi.updateMemberKinds(10, 2, null)
    expect(request.put).toHaveBeenCalledWith('/project-groups/10/members/2/kinds', { allowedKinds: null })
  })

  it('成员级可见性 PUT /{id}/members/{uid}/visibility-overrides（{}=清空）', async () => {
    vi.mocked(request.put).mockResolvedValue({ data: { data: null } } as never)
    await projectGroupApi.updateMemberVisibility(10, 2, { VIDEO: 'ALL' })
    expect(request.put).toHaveBeenCalledWith('/project-groups/10/members/2/visibility-overrides',
      { overrides: { VIDEO: 'ALL' } })
  })
})
