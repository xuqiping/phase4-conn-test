import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))

vi.mock('./request', () => ({
  default: requestMocks
}))

import { mediaApi } from './media'

describe('mediaApi.listTasks', () => {
  beforeEach(() => vi.clearAllMocks())

  it('AC-V3-02 发送提示词、半开时间范围和 limit 查询参数', () => {
    mediaApi.listTasks({
      q: 'Cat%_视频',
      from: '2026-08-01T00:00:00.000Z',
      to: '2026-08-11T00:00:00.000Z',
      limit: 25
    })

    expect(requestMocks.get).toHaveBeenCalledWith('/media/tasks', {
      params: {
        q: 'Cat%_视频',
        from: '2026-08-01T00:00:00.000Z',
        to: '2026-08-11T00:00:00.000Z',
        limit: 25
      }
    })
  })

  it('兼容原有数字 limit 调用', () => {
    mediaApi.listTasks(30)
    expect(requestMocks.get).toHaveBeenCalledWith('/media/tasks', { params: { limit: 30 } })
  })
})
