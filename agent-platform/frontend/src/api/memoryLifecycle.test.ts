import { describe, expect, it, vi } from 'vitest'
import { memoryApi } from './memory'
import request from './request'

vi.mock('./request', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn()
  }
}))

/**
 * 计划12 · F-4b · 生命周期折叠板 API 客户端契约（总体设计 §3.7）。
 * 对齐后端 MemoryLifecycleController 4 端点路径 + 请求体（空名发 {} 走后端默认命名）。
 */
describe('memoryApi 生命周期折叠板（F-4b）', () => {
  it('listDepartedProjects → GET /chat/memory/departed-projects', async () => {
    vi.mocked(request.get).mockResolvedValue({ data: { code: 200, data: [] } })
    await memoryApi.listDepartedProjects()
    expect(request.get).toHaveBeenCalledWith('/chat/memory/departed-projects')
  })

  it('listDeletedProjects → GET /chat/memory/deleted-projects', async () => {
    vi.mocked(request.get).mockResolvedValue({ data: { code: 200, data: [] } })
    await memoryApi.listDeletedProjects()
    expect(request.get).toHaveBeenCalledWith('/chat/memory/deleted-projects')
  })

  it('copyDepartedProjectTo 带名 → POST copy-to + {projectName}', async () => {
    vi.mocked(request.post).mockResolvedValue({ data: { code: 200, data: {} } })
    await memoryApi.copyDepartedProjectTo(100, '我的拉取')
    expect(request.post).toHaveBeenCalledWith('/chat/memory/departed-projects/100/copy-to', { projectName: '我的拉取' })
  })

  it('copyDepartedProjectTo 空名 → 发 {} 走后端默认命名', async () => {
    vi.mocked(request.post).mockResolvedValue({ data: { code: 200, data: {} } })
    await memoryApi.copyDepartedProjectTo(100)
    expect(request.post).toHaveBeenCalledWith('/chat/memory/departed-projects/100/copy-to', {})
  })

  it('restoreDeletedProject 带名 → POST restore + {projectName}', async () => {
    vi.mocked(request.post).mockResolvedValue({ data: { code: 200, data: {} } })
    await memoryApi.restoreDeletedProject(7, '恢复项目')
    expect(request.post).toHaveBeenCalledWith('/chat/memory/deleted-projects/7/restore', { projectName: '恢复项目' })
  })

  it('restoreDeletedProject 空名 → 发 {} 走后端默认命名', async () => {
    vi.mocked(request.post).mockResolvedValue({ data: { code: 200, data: {} } })
    await memoryApi.restoreDeletedProject(7)
    expect(request.post).toHaveBeenCalledWith('/chat/memory/deleted-projects/7/restore', {})
  })
})
