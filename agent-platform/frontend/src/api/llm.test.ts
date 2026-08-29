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

// 修复VIII B4（VIII-5）：导出改 POST + 密码走 body（明文 key 的导出须凭证复验，
// 密码绝不能进 URL——nginx access log 不留痕）；blob 响应触发浏览器下载。
describe('llmApi exportProviders（修复VIII B4）', () => {
  beforeEach(() => vi.clearAllMocks())

  it('POST /llm/providers/export，密码入 body、responseType blob', async () => {
    vi.mocked(request.post).mockResolvedValue({ data: new Blob() } as any)

    await llmApi.exportProviders('admin123')

    expect(request.post).toHaveBeenCalledTimes(1)
    expect(request.post).toHaveBeenCalledWith(
      '/llm/providers/export',
      { password: 'admin123' },
      { responseType: 'blob' }
    )
  })
})
