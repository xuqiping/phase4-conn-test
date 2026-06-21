import { describe, expect, it, vi } from 'vitest'
import { executionApi } from './execution'
import request from './request'

vi.mock('./request', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn()
  }
}))

describe('executionApi', () => {
  it('calls recovery, resume and approval endpoints', async () => {
    vi.mocked(request.get).mockResolvedValue({ data: { success: true, data: {} } })
    vi.mocked(request.post).mockResolvedValue({ data: { success: true, data: [] } })

    await executionApi.getExecution(99)
    await executionApi.listPendingApprovals()
    await executionApi.getRecoveryInfo(99)
    await executionApi.retry(99)
    await executionApi.resume('checkpoint-99')
    await executionApi.approve(99)
    await executionApi.reject(99, 'not safe')

    expect(request.get).toHaveBeenCalledWith('/executions/99')
    expect(request.get).toHaveBeenCalledWith('/executions/pending-approvals')
    expect(request.get).toHaveBeenCalledWith('/executions/99/recovery')
    expect(request.post).toHaveBeenCalledWith('/executions/99/retry')
    expect(request.post).toHaveBeenCalledWith('/executions/resume', null, {
      params: { checkpointRef: 'checkpoint-99' }
    })
    expect(request.post).toHaveBeenCalledWith('/executions/99/approve')
    expect(request.post).toHaveBeenCalledWith('/executions/99/reject', null, {
      params: { reason: 'not safe' }
    })
  })
})
