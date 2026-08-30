import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))

vi.mock('./request', () => ({
  default: requestMocks
}))

import { buildHistoryQuery, mediaApi } from './media'

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

  it('4x#2 分页参数 page/pageSize 原样透传', () => {
    mediaApi.listTasks({ q: '猫', kind: 'VIDEO', page: 3, pageSize: 20 })
    expect(requestMocks.get).toHaveBeenCalledWith('/media/tasks', {
      params: { q: '猫', kind: 'VIDEO', page: 3, pageSize: 20 }
    })
  })
})

describe('buildHistoryQuery（图片/视频两页共享拼装，4x#2）', () => {
  it('空白 q 归一 undefined；page/pageSize 拼入', () => {
    const q = buildHistoryQuery({ q: '   ', kind: 'IMAGE', rangeType: 'day', page: 2, pageSize: 5 })
    expect(q.q).toBeUndefined()
    expect(q).toMatchObject({ kind: 'IMAGE', page: 2, pageSize: 5 })
  })

  it('day 型区间含尾日全天（to=尾日 23:59:59.999，图片页 daterange）', () => {
    const day = Date.parse('2026-08-10T00:00:00+08:00')
    const q = buildHistoryQuery({ range: [day, day], kind: 'IMAGE', rangeType: 'day' })
    expect(q.from).toBe(new Date(day).toISOString())
    expect(q.to).toBe(new Date(day + 24 * 3600 * 1000 - 1).toISOString())
  })

  it('datetime 型区间 to 原样（视频页 datetimerange 精确尾时）', () => {
    const a = Date.parse('2026-08-10T08:00:00Z')
    const b = Date.parse('2026-08-10T10:30:00Z')
    const q = buildHistoryQuery({ range: [a, b], kind: 'VIDEO', rangeType: 'datetime' })
    expect(q.from).toBe(new Date(a).toISOString())
    expect(q.to).toBe(new Date(b).toISOString())
  })

  it('无区间时 from/to 均不发送', () => {
    const q = buildHistoryQuery({ kind: 'VIDEO', rangeType: 'datetime' })
    expect(q.from).toBeUndefined()
    expect(q.to).toBeUndefined()
  })
})
