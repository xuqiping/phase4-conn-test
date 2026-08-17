import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMocks = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('./request', () => ({
  default: requestMocks
}))

import { clearFilePreviewCache, fetchFilePreview } from './file'

/** jsdom 无 URL.createObjectURL 实现，stub 成计数 URL 断言「每次新建」。 */
let urlSeq = 0
const createObjectUrlSpy = vi.fn(() => `blob:test-${++urlSeq}`)

beforeEach(() => {
  vi.clearAllMocks()
  urlSeq = 0
  clearFilePreviewCache()
  // 测试桩覆盖（jsdom 未实现 createObjectURL）
  URL.createObjectURL = createObjectUrlSpy
})

function okBlob(size = 10) {
  return { data: new Blob([new Uint8Array(size)]) }
}

describe('fetchFilePreview（6x#2 会话内 LRU）', () => {
  it('未命中：带鉴权拉 blob 并缓存', async () => {
    requestMocks.get.mockResolvedValue(okBlob())
    await fetchFilePreview('f1')
    expect(requestMocks.get).toHaveBeenCalledWith('/files/f1', { responseType: 'blob', _background: true })
  })

  it('命中：不发二次请求，且每次返回新 objectURL（调用方各 revoke 各的）', async () => {
    requestMocks.get.mockResolvedValue(okBlob())
    const u1 = await fetchFilePreview('f1')
    const u2 = await fetchFilePreview('f1')
    expect(requestMocks.get).toHaveBeenCalledTimes(1)
    expect(u1).toBe('blob:test-1')
    expect(u2).toBe('blob:test-2') // 同 Blob 两次 createObjectURL
    expect(createObjectUrlSpy).toHaveBeenCalledTimes(2)
  })

  it('不同 fileId 各自请求', async () => {
    requestMocks.get.mockResolvedValue(okBlob())
    await fetchFilePreview('f1')
    await fetchFilePreview('f2')
    expect(requestMocks.get).toHaveBeenCalledTimes(2)
  })

  it('请求失败不缓存（下次重试真请求）', async () => {
    requestMocks.get.mockRejectedValueOnce(new Error('403'))
    await expect(fetchFilePreview('f1')).rejects.toThrow('403')
    requestMocks.get.mockResolvedValue(okBlob())
    await fetchFilePreview('f1')
    expect(requestMocks.get).toHaveBeenCalledTimes(2)
  })
})
