import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import ClipboardManagement from '../ClipboardManagement.vue'
import ClipboardItemRow from '../ClipboardItemRow.vue'
import ClipboardPreview from '../ClipboardPreview.vue'
import type { ClipboardItemSummary } from '../../types/clipboard'

function item(overrides: Partial<ClipboardItemSummary> = {}): ClipboardItemSummary {
  return {
    id: 'item-1',
    kind: 'text',
    title: 'hello',
    summary: 'hello summary',
    createdAt: Date.now(),
    useCount: 0,
    isFavorite: false,
    isPinned: false,
    cacheBytes: 0,
    cacheState: 'none',
    ...overrides
  }
}

describe('clipboard components', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })
  it('renders management shell', () => {
    const wrapper = mount(ClipboardManagement, {
      global: { plugins: [createTestingPinia({ createSpy: vi.fn })] }
    })

    expect(wrapper.text()).toContain('剪贴板')
    expect(wrapper.text()).toContain('全部')
  })

  it('renders item row by kind and source', () => {
    const wrapper = mount(ClipboardItemRow, {
      props: {
        item: item({
          kind: 'image',
          title: '截图',
          sourceApp: { processName: 'SnippingTool.exe', windowTitle: '截图工具' }
        }),
        selected: true
      }
    })

    expect(wrapper.text()).toContain('截图')
    expect(wrapper.text()).toContain('图片')
    expect(wrapper.text()).toContain('SnippingTool.exe')
  })

  it('emits select from item row', async () => {
    const wrapper = mount(ClipboardItemRow, { props: { item: item(), selected: false } })

    await wrapper.trigger('click')

    expect(wrapper.emitted('select')?.[0]).toEqual(['item-1'])
  })

  it('renders preview empty state', () => {
    const wrapper = mount(ClipboardPreview, { props: { item: null, detail: null } })

    expect(wrapper.text()).toContain('选择一条历史记录')
  })

  it('renders management page with list, preview, settings, and storage usage together', async () => {
    const wrapper = mount(ClipboardManagement, {
      global: {
        plugins: [createTestingPinia({ createSpy: vi.fn })],
        stubs: {
          ClipboardToolbar: false,
          ClipboardList: false,
          ClipboardPreview: false,
          ClipboardSettings: false,
          ClipboardStorageUsage: false,
          ClipboardSecurityEvents: false
        }
      }
    })

    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('剪贴板')
    expect(wrapper.text()).toContain('搜索')
    expect(wrapper.text()).toContain('缓存空间')
  })
})
