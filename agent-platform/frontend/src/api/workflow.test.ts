import { beforeEach, describe, expect, it, vi } from 'vitest'
import { workflowApi } from './workflow'
import request from './request'
import { setStorage, STORAGE_KEYS } from '@/utils/storage'

vi.mock('./request', () => ({
  default: {
    post: vi.fn()
  }
}))

describe('workflowApi', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('calls workflow run endpoint with input', async () => {
    vi.mocked(request.post).mockResolvedValue({ data: { success: true, data: [] } })

    await workflowApi.run(10, { message: 'hello' })

    expect(request.post).toHaveBeenCalledWith('/workflows/10/run', { message: 'hello' }, { timeout: 120000 })
  })

  it('sends authorization header when streaming workflow runs', async () => {
    setStorage(STORAGE_KEYS.ACCESS_TOKEN, 'at-123')
    const body = new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('data: {"type":"EXECUTION_STARTED","status":"RUNNING"}\n\n'))
        controller.close()
      }
    })
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(body, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' }
      })
    )

    const events = []
    for await (const event of workflowApi.runStream(8, { message: 'hello' })) {
      events.push(event)
    }

    expect(events).toHaveLength(1)
    expect(fetchMock).toHaveBeenCalledWith('/api/workflows/8/run/stream', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({
        Authorization: 'Bearer at-123'
      })
    }))
  })
})
