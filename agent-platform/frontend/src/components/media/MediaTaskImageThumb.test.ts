import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

const fetchMediaBlob = vi.hoisted(() => vi.fn().mockResolvedValue('blob:history-thumb'))
let intersect: ((entries: Array<{ isIntersecting: boolean }>) => void) | null = null

vi.mock('@/api/media', () => ({ fetchMediaBlob }))

import MediaTaskImageThumb from './MediaTaskImageThumb.vue'

describe('MediaTaskImageThumb', () => {
  beforeEach(() => {
    fetchMediaBlob.mockClear()
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

  it('进入可视区才拉缩略图（懒加载）', async () => {
    const wrapper = mount(MediaTaskImageThumb, { props: { downloadPath: '/api/media/tasks/1/images/0/download' } })
    expect(fetchMediaBlob).not.toHaveBeenCalled()
    intersect?.([{ isIntersecting: true }])
    await vi.waitFor(() => {
      expect(fetchMediaBlob).toHaveBeenCalledOnce()
      expect(wrapper.find('img').exists()).toBe(true)
    })
    expect(wrapper.find('img').attributes('src')).toBe('blob:history-thumb')
  })

  it('拉取失败降级「无预览」，不抛错', async () => {
    fetchMediaBlob.mockRejectedValueOnce(new Error('403'))
    const wrapper = mount(MediaTaskImageThumb, { props: { downloadPath: '/x' } })
    intersect?.([{ isIntersecting: true }])
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('无预览')
    })
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('点缩略图 emit preview 且行点击不触发（stop 冒泡由模板 .stop 保证）', async () => {
    const wrapper = mount(MediaTaskImageThumb, { props: { downloadPath: '/x' } })
    intersect?.([{ isIntersecting: true }])
    await vi.waitFor(() => expect(wrapper.find('img').exists()).toBe(true))
    await wrapper.find('.task-image-thumb').trigger('click')
    expect(wrapper.emitted('preview')?.[0]).toEqual(['blob:history-thumb'])
  })

  it('换 downloadPath 重新加载；卸载 revoke objectURL 防泄漏', async () => {
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const wrapper = mount(MediaTaskImageThumb, { props: { downloadPath: '/a' } })
    intersect?.([{ isIntersecting: true }])
    await vi.waitFor(() => expect(fetchMediaBlob).toHaveBeenCalledOnce())

    await wrapper.setProps({ downloadPath: '/b' })
    await vi.waitFor(() => expect(fetchMediaBlob).toHaveBeenCalledTimes(2))
    expect(revokeSpy).toHaveBeenCalledWith('blob:history-thumb')

    wrapper.unmount()
    expect(revokeSpy.mock.calls.length).toBeGreaterThanOrEqual(2)
    revokeSpy.mockRestore()
  })
})
