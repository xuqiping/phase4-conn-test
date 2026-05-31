import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import ClipboardManagement from '../ClipboardManagement.vue'
import ClipboardItemRow from '../ClipboardItemRow.vue'
import ClipboardPreview from '../ClipboardPreview.vue'
import ClipboardList from '../ClipboardList.vue'
import ClipboardToolbar from '../ClipboardToolbar.vue'
import { useClipboardStore } from '../../stores/clipboardStore'
import * as filesApi from '../../api/files'
import * as openerApi from '@tauri-apps/plugin-opener'
import type { ClipboardItemDetail, ClipboardItemSummary } from '../../types/clipboard'

vi.mock('../../api/files', () => ({
  openFile: vi.fn(),
  showInFolder: vi.fn()
}))

vi.mock('@tauri-apps/plugin-opener', () => ({
  openUrl: vi.fn()
}))

const toolbarProps = {
  searchQuery: '',
  kind: 'all',
  datePreset: 'all' as const,
  customStartDate: '',
  customEndDate: ''
}

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

function detail(overrides: Partial<ClipboardItemDetail> = {}): ClipboardItemDetail {
  return {
    ...item(overrides),
    availableFormats: ['original'],
    ...overrides
  }
}

describe('clipboard components', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
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
        selected: true,
        checked: false
      }
    })

    expect(wrapper.text()).toContain('截图')
    expect(wrapper.text()).toContain('图片')
    expect(wrapper.text()).toContain('SnippingTool.exe')
  })

  it('emits select from item row', async () => {
    const wrapper = mount(ClipboardItemRow, { props: { item: item(), selected: false, checked: false } })

    await wrapper.trigger('click')

    expect(wrapper.emitted('select')?.[0]).toEqual(['item-1'])
  })

  it('clicking list row selects and toggles it', async () => {
    const wrapper = mount(ClipboardList, {
      props: {
        items: [item()],
        selectedItemId: null,
        selectedIds: new Set<string>()
      }
    })

    await wrapper.getComponent(ClipboardItemRow).trigger('click')

    expect(wrapper.emitted('select')?.[0]).toEqual(['item-1'])
    expect(wrapper.emitted('toggleSelected')?.[0]).toEqual(['item-1'])
  })

  it('emits toggle selection from row checkbox', async () => {
    const wrapper = mount(ClipboardItemRow, { props: { item: item(), selected: false, checked: false } })

    await wrapper.get('input[type="checkbox"]').trigger('click')

    expect(wrapper.emitted('toggleSelected')?.[0]).toEqual(['item-1'])
  })

  it('emits copy from item row double click', async () => {
    const wrapper = mount(ClipboardItemRow, { props: { item: item(), selected: false, checked: true } })

    await wrapper.trigger('dblclick')

    expect(wrapper.emitted('copy')?.[0]).toEqual(['item-1'])
  })

  it('highlights checked item row', () => {
    const wrapper = mount(ClipboardItemRow, { props: { item: item(), selected: false, checked: true } })

    expect(wrapper.classes()).toContain('border-primary/30')
  })

  it('renders note preview in item row', () => {
    const wrapper = mount(ClipboardItemRow, { props: { item: item({ note: '重要资料' }), selected: false, checked: false } })

    expect(wrapper.text()).toContain('备注：重要资料')
  })

  it('renders preview empty state', () => {
    const wrapper = mount(ClipboardPreview, { props: { item: null, detail: null } })

    expect(wrapper.text()).toContain('选择一条历史记录')
  })

  it('emits note save and clear from preview', async () => {
    const wrapper = mount(ClipboardPreview, {
      props: {
        item: item({ note: '旧备注' }),
        detail: { ...item({ note: '旧备注' }), availableFormats: ['original'] }
      }
    })

    await wrapper.get('textarea').setValue('新备注')
    await wrapper.findAll('button').find(button => button.text() === '保存备注')?.trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '清空')?.trigger('click')

    expect(wrapper.emitted('saveNote')?.[0]).toEqual(['item-1', '新备注'])
    expect(wrapper.emitted('saveNote')?.[1]).toEqual(['item-1', ''])
  })

  it('resets note draft when selected item changes before detail loads', async () => {
    const firstItem = item({ id: 'item-1', note: '旧备注' })
    const secondItem = item({ id: 'item-2', title: 'second', summary: 'second summary' })
    const wrapper = mount(ClipboardPreview, {
      props: {
        item: firstItem,
        detail: { ...firstItem, availableFormats: ['original'] }
      }
    })

    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('旧备注')

    await wrapper.setProps({ item: secondItem, detail: { ...firstItem, availableFormats: ['original'] } })

    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('')
  })

  it('focuses note textarea when note focus key changes', async () => {
    const wrapper = mount(ClipboardPreview, {
      attachTo: document.body,
      props: {
        item: item({ note: '旧备注' }),
        detail: { ...item({ note: '旧备注' }), availableFormats: ['original'] },
        noteFocusKey: 0
      }
    })

    await wrapper.setProps({ noteFocusKey: 1 })
    await wrapper.vm.$nextTick()

    expect(document.activeElement).toBe(wrapper.get('textarea').element)
    wrapper.unmount()
  })

  it('shows visible note editing state in preview', () => {
    const wrapper = mount(ClipboardPreview, {
      props: {
        item: item({ note: '旧备注' }),
        detail: { ...item({ note: '旧备注' }), availableFormats: ['original'] },
        noteEditing: true
      }
    })

    expect(wrapper.text()).toContain('正在编辑备注')
  })

  it('debounces toolbar search while typing', async () => {
    vi.useFakeTimers()
    const wrapper = mount(ClipboardToolbar, { props: toolbarProps })

    await wrapper.get('input').setValue('hello')
    expect(wrapper.emitted('update:searchQuery')?.[0]).toEqual(['hello'])
    expect(wrapper.emitted('search')).toBeUndefined()

    vi.advanceTimersByTime(249)
    expect(wrapper.emitted('search')).toBeUndefined()

    vi.advanceTimersByTime(1)
    expect(wrapper.emitted('search')).toHaveLength(1)
  })

  it('searches immediately from toolbar enter key', async () => {
    vi.useFakeTimers()
    const wrapper = mount(ClipboardToolbar, { props: toolbarProps })

    await wrapper.get('input').setValue('hello')
    await wrapper.get('input').trigger('keydown.enter')

    expect(wrapper.emitted('search')).toHaveLength(1)
    vi.advanceTimersByTime(250)
    expect(wrapper.emitted('search')).toHaveLength(1)
  })

  it('emits date preset changes from toolbar', async () => {
    const wrapper = mount(ClipboardToolbar, { props: toolbarProps })
    const dateSelect = wrapper.findAll('select')[1]

    await dateSelect.setValue('today')

    expect(wrapper.emitted('update:datePreset')?.[0]).toEqual(['today'])
    expect(wrapper.emitted('search')).toHaveLength(1)
  })

  it('emits settings action from toolbar', async () => {
    const wrapper = mount(ClipboardToolbar, { props: toolbarProps })

    await wrapper.get('[data-test="clipboard-settings-button"]').trigger('click')

    expect(wrapper.emitted('openSettings')).toHaveLength(1)
  })

  it('shows custom date inputs and emits changes', async () => {
    const wrapper = mount(ClipboardToolbar, { props: { ...toolbarProps, datePreset: 'custom' } })
    const dateInputs = wrapper.findAll('input[type="date"]')

    await dateInputs[0].setValue('2026-05-01')
    await dateInputs[1].setValue('2026-05-30')

    expect(wrapper.emitted('update:customStartDate')?.[0]).toEqual(['2026-05-01'])
    expect(wrapper.emitted('update:customEndDate')?.[0]).toEqual(['2026-05-30'])
    expect(wrapper.emitted('search')).toHaveLength(2)
  })

  it('always shows batch selection toolbar', () => {
    const wrapper = mount(ClipboardList, {
      props: {
        items: [item()],
        selectedItemId: null,
        selectedIds: new Set<string>()
      }
    })

    expect(wrapper.text()).toContain('共 1 条记录')
    expect(wrapper.text()).toContain('全选')
    expect(wrapper.text()).toContain('反选')
    expect(wrapper.text()).toContain('批量复制')
    expect(wrapper.text()).toContain('删除选中')
  })

  it('emits batch selection actions', async () => {
    const wrapper = mount(ClipboardList, {
      props: {
        items: [item()],
        selectedItemId: 'item-1',
        selectedIds: new Set(['item-1'])
      }
    })

    const buttons = wrapper.findAll('button')
    await buttons.find(button => button.text() === '全选')?.trigger('click')
    await buttons.find(button => button.text() === '反选')?.trigger('click')
    await buttons.find(button => button.text() === '批量复制')?.trigger('click')

    expect(wrapper.emitted('selectAll')).toHaveLength(1)
    expect(wrapper.emitted('invertSelection')).toHaveLength(1)
    expect(wrapper.emitted('copySelected')).toHaveLength(1)
  })

  it('emits copy and delete from context menu', async () => {
    const wrapper = mount(ClipboardList, {
      props: {
        items: [item()],
        selectedItemId: null,
        selectedIds: new Set<string>()
      }
    })

    await wrapper.getComponent(ClipboardItemRow).trigger('contextmenu')
    const buttons = wrapper.findAll('button')
    await buttons.find(button => button.text() === '复制')?.trigger('click')

    await wrapper.getComponent(ClipboardItemRow).trigger('contextmenu')
    await wrapper.findAll('button').find(button => button.text() === '删除')?.trigger('click')

    expect(wrapper.emitted('copy')?.[0]).toEqual(['item-1'])
    expect(wrapper.emitted('delete')?.[0]).toEqual(['item-1'])
  })

  it('shows open url action for url context menu', async () => {
    const wrapper = mount(ClipboardList, {
      props: {
        items: [item({ kind: 'url' })],
        selectedItemId: null,
        selectedIds: new Set<string>()
      }
    })

    await wrapper.getComponent(ClipboardItemRow).trigger('contextmenu')
    await wrapper.findAll('button').find(button => button.text() === '打开链接')?.trigger('click')

    expect(wrapper.emitted('openUrl')?.[0]).toEqual(['item-1'])
  })

  it('shows file actions for file context menu', async () => {
    const wrapper = mount(ClipboardList, {
      props: {
        items: [item({ kind: 'file' })],
        selectedItemId: null,
        selectedIds: new Set<string>()
      }
    })

    await wrapper.getComponent(ClipboardItemRow).trigger('contextmenu')
    expect(wrapper.text()).toContain('复制文件')
    expect(wrapper.text()).toContain('打开文件')
    expect(wrapper.text()).toContain('打开文件所在目录')
    expect(wrapper.text()).toContain('复制文件路径')

    await wrapper.findAll('button').find(button => button.text() === '打开文件')?.trigger('click')
    await wrapper.getComponent(ClipboardItemRow).trigger('contextmenu')
    await wrapper.findAll('button').find(button => button.text() === '打开文件所在目录')?.trigger('click')
    await wrapper.getComponent(ClipboardItemRow).trigger('contextmenu')
    await wrapper.findAll('button').find(button => button.text() === '复制文件路径')?.trigger('click')

    expect(wrapper.emitted('openFile')?.[0]).toEqual(['item-1'])
    expect(wrapper.emitted('openFolder')?.[0]).toEqual(['item-1'])
    expect(wrapper.emitted('copyFilePath')?.[0]).toEqual(['item-1'])
  })

  it('emits edit note from context menu', async () => {
    const wrapper = mount(ClipboardList, {
      props: {
        items: [item()],
        selectedItemId: null,
        selectedIds: new Set<string>()
      }
    })

    await wrapper.getComponent(ClipboardItemRow).trigger('contextmenu')
    await wrapper.findAll('button').find(button => button.text() === '编辑备注')?.trigger('click')

    expect(wrapper.emitted('editNote')?.[0]).toEqual(['item-1'])
  })

  it('copies selected items from page-level ctrl c shortcut and shows success notice', async () => {
    const wrapper = mount(ClipboardManagement, {
      global: {
        plugins: [createTestingPinia({ createSpy: vi.fn })],
        stubs: {
          ClipboardToolbar: true,
          ClipboardList: true,
          ClipboardPreview: true,
          ClipboardSettings: true,
          ClipboardStorageUsage: true,
          ClipboardSecurityEvents: true
        }
      }
    })
    const store = useClipboardStore()
    store.selectedIds = new Set(['item-1'])
    const copySelectedItems = vi.spyOn(store, 'copySelectedItems').mockResolvedValue(1)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'c', ctrlKey: true }))
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(copySelectedItems).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('已复制 1 条记录到剪贴板')
    wrapper.unmount()
  })

  it('does not intercept ctrl c from toolbar input', async () => {
    const wrapper = mount(ClipboardManagement, {
      global: {
        plugins: [createTestingPinia({ createSpy: vi.fn })],
        stubs: {
          ClipboardToolbar: false,
          ClipboardList: true,
          ClipboardPreview: true,
          ClipboardSettings: true,
          ClipboardStorageUsage: true,
          ClipboardSecurityEvents: true
        }
      }
    })
    const store = useClipboardStore()
    store.selectedIds = new Set(['item-1'])
    const copySelectedItems = vi.spyOn(store, 'copySelectedItems').mockResolvedValue(1)
    const input = wrapper.get('input').element

    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'c', ctrlKey: true, bubbles: true }))

    expect(copySelectedItems).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('shows success notice after list batch copy event', async () => {
    const wrapper = mount(ClipboardManagement, {
      global: {
        plugins: [createTestingPinia({ createSpy: vi.fn })],
        stubs: {
          ClipboardToolbar: true,
          ClipboardList: false,
          ClipboardPreview: true,
          ClipboardSettings: true,
          ClipboardStorageUsage: true,
          ClipboardSecurityEvents: true
        }
      }
    })
    const store = useClipboardStore()
    store.items = [item()]
    store.selectedIds = new Set(['item-1'])
    const copySelectedItems = vi.spyOn(store, 'copySelectedItems').mockResolvedValue(1)

    wrapper.getComponent(ClipboardList).vm.$emit('copySelected')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(copySelectedItems).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('已复制 1 条记录到剪贴板')
    wrapper.unmount()
  })

  it('shows error notice after copy failure', async () => {
    const wrapper = mount(ClipboardManagement, {
      global: {
        plugins: [createTestingPinia({ createSpy: vi.fn })],
        stubs: {
          ClipboardToolbar: true,
          ClipboardList: false,
          ClipboardPreview: true,
          ClipboardSettings: true,
          ClipboardStorageUsage: true,
          ClipboardSecurityEvents: true
        }
      }
    })
    const store = useClipboardStore()
    store.items = [item()]
    store.selectedIds = new Set(['item-1'])
    vi.spyOn(store, 'copySelectedItems').mockRejectedValue(new Error('没有可复制内容'))

    wrapper.getComponent(ClipboardList).vm.$emit('copySelected')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('复制失败：没有可复制内容')
    wrapper.unmount()
  })

  it('shows note editing feedback immediately from management edit note event', async () => {
    const wrapper = mount(ClipboardManagement, {
      global: {
        plugins: [createTestingPinia({ createSpy: vi.fn })],
        stubs: {
          ClipboardToolbar: true,
          ClipboardList: false,
          ClipboardPreview: false,
          ClipboardSettings: true,
          ClipboardStorageUsage: true,
          ClipboardSecurityEvents: true
        }
      }
    })
    const store = useClipboardStore()
    store.items = [item({ note: '旧备注' })]
    store.selectedItemId = 'item-1'
    vi.spyOn(store, 'loadDetail').mockReturnValue(new Promise(() => undefined) as never)

    wrapper.getComponent(ClipboardList).vm.$emit('editNote', 'item-1')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('已打开备注编辑')
    expect(wrapper.text()).toContain('正在编辑备注')
    wrapper.unmount()
  })

  it('handles url and file context actions from management', async () => {
    const wrapper = mount(ClipboardManagement, {
      global: {
        plugins: [createTestingPinia({ createSpy: vi.fn })],
        stubs: {
          ClipboardToolbar: true,
          ClipboardList: false,
          ClipboardPreview: true,
          ClipboardSettings: true,
          ClipboardStorageUsage: true,
          ClipboardSecurityEvents: true
        }
      }
    })
    const store = useClipboardStore()
    store.items = [item({ kind: 'file' })]
    const loadDetail = vi.spyOn(store, 'loadDetail')
    loadDetail.mockResolvedValueOnce(detail({ kind: 'url', url: 'https://example.com' }))
    loadDetail.mockResolvedValueOnce(detail({ kind: 'file', files: [{ name: 'a.txt', originalPath: 'C:/tmp/a.txt', sizeBytes: 1, isDirectory: false, copyState: 'reference_only' }] }))
    loadDetail.mockResolvedValueOnce(detail({ kind: 'file', files: [{ name: 'a.txt', originalPath: 'C:/tmp/a.txt', sizeBytes: 1, isDirectory: false, copyState: 'reference_only' }] }))
    const copyItem = vi.spyOn(store, 'copyItem').mockResolvedValue()

    wrapper.getComponent(ClipboardList).vm.$emit('openUrl', 'item-1')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    wrapper.getComponent(ClipboardList).vm.$emit('openFile', 'item-1')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    wrapper.getComponent(ClipboardList).vm.$emit('openFolder', 'item-1')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    wrapper.getComponent(ClipboardList).vm.$emit('copyFilePath', 'item-1')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(openerApi.openUrl).toHaveBeenCalledWith('https://example.com')
    expect(filesApi.openFile).toHaveBeenCalledWith('C:/tmp/a.txt')
    expect(filesApi.showInFolder).toHaveBeenCalledWith('C:/tmp/a.txt')
    expect(copyItem).toHaveBeenCalledWith('item-1', 'plain_text')
    wrapper.unmount()
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
    expect(wrapper.text()).toContain('剪贴板设置')
    expect(wrapper.text()).toContain('缓存空间')
  })
})
