import { describe, expect, it, vi } from 'vitest'
import { chatTargetApi } from './chatTarget'
import request from './request'

vi.mock('./request', () => ({
  default: {
    get: vi.fn()
  }
}))

describe('chatTargetApi', () => {
  it('loads available chat targets', async () => {
    vi.mocked(request.get).mockResolvedValue({ data: { success: true, data: [] } })

    await chatTargetApi.listTargets()

    expect(request.get).toHaveBeenCalledWith('/chat/targets')
  })
})
