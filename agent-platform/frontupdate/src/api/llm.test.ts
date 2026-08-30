import { beforeEach, describe, expect, it, vi } from 'vitest'
import request from './request'
import { llmApi } from './llm'

vi.mock('./request', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}))

describe('llmApi rerank diagnostics', () => {
  beforeEach(() => vi.clearAllMocks())

  it('posts to the dedicated rerank test endpoint', async () => {
    vi.mocked(request.post).mockResolvedValue({ data: { data: { success: true } } } as any)

    await llmApi.testProviderRerank(7)

    expect(request.post).toHaveBeenCalledWith('/llm/providers/7/test-rerank')
  })
})
