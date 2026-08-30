import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

const fetchVideoBlob = vi.hoisted(() => vi.fn().mockResolvedValue('blob:history-video'))
let intersect: ((entries: Array<{ isIntersecting: boolean }>) => void) | null = null

vi.mock('@/api/media', () => ({ fetchVideoBlob }))

import MediaTaskVideoPreview from './MediaTaskVideoPreview.vue'

describe('MediaTaskVideoPreview', () => {
  beforeEach(() => {
    fetchVideoBlob.mockClear()
    intersect = null
    globalThis.IntersectionObserver = class {
      constructor(cb: typeof intersect) { intersect = cb }
      observe() {}
      disconnect() {}
      unobserve() {}
      takeRecords() { return [] }
      root = null
      rootMargin = ''
      thresholds = []
    } as unknown as typeof IntersectionObserver
  })

  it('AC-V3-04 进入可视区后才拉历史视频', async () => {
    const wrapper = mount(MediaTaskVideoPreview, { props: { downloadPath: '/api/media/tasks/1/download' } })
    expect(fetchVideoBlob).not.toHaveBeenCalled()
    intersect?.([{ isIntersecting: true }])
    await vi.waitFor(() => {
      expect(fetchVideoBlob).toHaveBeenCalledOnce()
      expect(wrapper.find('video').exists()).toBe(true)
    })
    expect(wrapper.find('video').attributes('src')).toBe('blob:history-video')
  })
})
