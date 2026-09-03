import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import ClipboardQuickPanel from '../ClipboardQuickPanel.vue'
import { useClipboardStore } from '../../stores/clipboardStore'

describe('ClipboardQuickPanel', () => {
  it('does not render when closed', () => {
    const wrapper = mount(ClipboardQuickPanel, {
      global: { plugins: [createTestingPinia({ createSpy: vi.fn })] }
    })

    expect(wrapper.text()).toBe('')
  })

  it('renders search panel when open', async () => {
    const wrapper = mount(ClipboardQuickPanel, {
      global: { plugins: [createTestingPinia({ createSpy: vi.fn, stubActions: false })] }
    })
    const store = useClipboardStore()
    vi.spyOn(store, 'loadQuickPanelItems').mockResolvedValue()
    store.quickPanelItems = [{
      id: '1',
      kind: 'text',
      title: 'hello',
      summary: 'hello',
      createdAt: Date.now(),
      useCount: 0,
      isFavorite: false,
      isPinned: false,
      cacheBytes: 0,
      cacheState: 'none'
    }]
    store.isQuickPanelOpen = true
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('hello')
    expect(wrapper.find('input').exists()).toBe(true)
  })

  it('uses Enter to paste selected item', async () => {
    const wrapper = mount(ClipboardQuickPanel, {
      global: { plugins: [createTestingPinia({ createSpy: vi.fn, stubActions: false })] }
    })
    const store = useClipboardStore()
    vi.spyOn(store, 'loadQuickPanelItems').mockResolvedValue()
    store.quickPanelItems = [{
      id: '1',
      kind: 'text',
      title: 'hello',
      summary: 'hello',
      createdAt: Date.now(),
      useCount: 0,
      isFavorite: false,
      isPinned: false,
      cacheBytes: 0,
      cacheState: 'none'
    }]
    const pasteSpy = vi.spyOn(store, 'pasteItem').mockResolvedValue()
    store.isQuickPanelOpen = true
    await wrapper.vm.$nextTick()

    await wrapper.find('input').trigger('keydown.enter')

    expect(pasteSpy).toHaveBeenCalledWith('1', 'original')
    expect(store.isQuickPanelOpen).toBe(false)
  })

  it('uses Escape to close', async () => {
    const wrapper = mount(ClipboardQuickPanel, {
      global: { plugins: [createTestingPinia({ createSpy: vi.fn, stubActions: false })] }
    })
    const store = useClipboardStore()
    vi.spyOn(store, 'loadQuickPanelItems').mockResolvedValue()
    store.isQuickPanelOpen = true
    await wrapper.vm.$nextTick()

    await wrapper.find('input').trigger('keydown.escape')

    expect(store.isQuickPanelOpen).toBe(false)
  })

  it('uses Shift+Enter for plain text', async () => {
    const wrapper = mount(ClipboardQuickPanel, {
      global: { plugins: [createTestingPinia({ createSpy: vi.fn, stubActions: false })] }
    })
    const store = useClipboardStore()
    vi.spyOn(store, 'loadQuickPanelItems').mockResolvedValue()
    store.quickPanelItems = [{
      id: '1',
      kind: 'text',
      title: 'hello',
      summary: 'hello',
      createdAt: Date.now(),
      useCount: 0,
      isFavorite: false,
      isPinned: false,
      cacheBytes: 0,
      cacheState: 'none'
    }]
    const pasteSpy = vi.spyOn(store, 'pasteItem').mockResolvedValue()
    store.isQuickPanelOpen = true
    await wrapper.vm.$nextTick()

    await wrapper.find('input').trigger('keydown.enter', { shiftKey: true })

    expect(pasteSpy).toHaveBeenCalledWith('1', 'plain_text')
  })

  it('closes quick panel after successful paste', async () => {
    const wrapper = mount(ClipboardQuickPanel, {
      global: { plugins: [createTestingPinia({ createSpy: vi.fn, stubActions: false })] }
    })
    const store = useClipboardStore()
    vi.spyOn(store, 'loadQuickPanelItems').mockResolvedValue()
    store.quickPanelItems = [{
      id: 'item-1',
      kind: 'text',
      title: 'hello',
      summary: 'hello',
      createdAt: Date.now(),
      useCount: 0,
      isFavorite: false,
      isPinned: false,
      cacheBytes: 0,
      cacheState: 'none'
    }]
    const pasteSpy = vi.spyOn(store, 'pasteItem').mockResolvedValue()
    store.isQuickPanelOpen = true
    await wrapper.vm.$nextTick()

    await wrapper.find('input').trigger('keydown.enter')

    expect(pasteSpy).toHaveBeenCalledWith('item-1', 'original')
    expect(store.isQuickPanelOpen).toBe(false)
  })
})
