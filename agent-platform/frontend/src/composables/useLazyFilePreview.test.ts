import { describe, expect, it, beforeEach, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { useLazyFilePreview, __resetPreviewCacheForTest } from './useLazyFilePreview'

// fetchFilePreview 打桩：默认成功，按需切失败
const fetchMock = vi.fn<(id: string) => Promise<string>>()
vi.mock('@/api/file', () => ({
  fetchFilePreview: (id: string) => fetchMock(id)
}))

// IntersectionObserver polyfill：捕获 callback，手动触发 intersect
type IOCB = (entries: { isIntersecting: boolean }[]) => void
let ioCbs: IOCB[] = []
beforeEach(() => {
  ioCbs = []
  fetchMock.mockReset()
  fetchMock.mockImplementation(async id => `blob:${id}`)
  __resetPreviewCacheForTest()
  ;(globalThis as unknown as { IntersectionObserver: unknown }).IntersectionObserver = class {
    constructor(cb: IOCB) {
      ioCbs.push(cb)
    }
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})
function intersect(v: boolean) {
  for (const cb of ioCbs) cb([{ isIntersecting: v }])
}

/**
 * host 组件包裹组合式（触发 onMounted 真实生命周期，模板 ref 绑定）。
 * 暴露 url/failed 供断言；props.fileId/enabled 走 getter 注入。
 */
function mkHost(fileIdRef: { value: string | null }, enabledRef: { value: boolean }) {
  return defineComponent({
    name: 'HostPreview',
    setup() {
      const target = ref<HTMLElement | null>(null)
      const r = useLazyFilePreview(
        target,
        () => fileIdRef.value,
        () => enabledRef.value
      )
      return () => h('div', { ref: target }, r.url.value ?? (r.failed.value ? 'FAIL' : ''))
    }
  })
}

describe('useLazyFilePreview (C2)', () => {
  it('视口外不拉，进入视口拉一次', async () => {
    const fid = ref<string | null>('fid-1')
    const en = ref(true)
    const wrapper = mount(mkHost(fid, en))
    await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()

    intersect(true)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toBe('blob:fid-1')
  })

  it('同 fileId 多卡去重（单次 fetch，复用 url）', async () => {
    const fid = ref<string | null>('shared')
    const en = ref(true)
    const Host = mkHost(fid, en)
    const w1 = mount(Host)
    const w2 = mount(Host)
    await flushPromises()
    intersect(true)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(w1.text()).toBe('blob:shared')
    expect(w2.text()).toBe('blob:shared')
  })

  it('失败 → failed=true（渲 FAIL 标记，不抛穿透）', async () => {
    fetchMock.mockRejectedValueOnce(new Error('403'))
    const fid = ref<string | null>('bad')
    const en = ref(true)
    const wrapper = mount(mkHost(fid, en))
    await flushPromises()
    intersect(true)
    await flushPromises()
    expect(wrapper.text()).toBe('FAIL')
  })

  it('enabled=false 不拉取', async () => {
    const fid = ref<string | null>('fid-9')
    const en = ref(false)
    mount(mkHost(fid, en))
    await flushPromises()
    intersect(true)
    await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('fileId 缺失不拉取', async () => {
    const fid = ref<string | null>(null)
    const en = ref(true)
    mount(mkHost(fid, en))
    await flushPromises()
    intersect(true)
    await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
