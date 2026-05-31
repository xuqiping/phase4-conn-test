import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useClipboardStore } from '../clipboardStore'
import * as clipboardApi from '../../api/clipboard'
import type { ClipboardItemSummary, ClipboardSettings } from '../../types/clipboard'

vi.mock('../../api/clipboard')

const mockedApi = vi.mocked(clipboardApi)

function item(id: string, title: string): ClipboardItemSummary {
  return {
    id,
    kind: 'text',
    title,
    summary: title,
    createdAt: Date.now(),
    useCount: 0,
    isFavorite: false,
    isPinned: false,
    cacheBytes: 0,
    cacheState: 'none'
  }
}

function settings(): ClipboardSettings {
  return {
    monitorEnabled: true,
    quickPanelShortcut: 'CommandOrControl+Shift+V',
    autoPaste: false,
    protectSensitiveContent: true,
    enableOcr: true,
    enableLinkPreview: false,
    totalNonTextLimitMb: 2048,
    itemSizeLimitMb: 200,
    typeLimitsMb: { image: 1024, file: 2048, html: 500, linkPreview: 200 },
    fileSaveMode: 'backup',
    backupDirectory: null,
    fileExtensionMode: 'allow_all',
    fileExtensions: [],
    excludedApps: []
  }
}

describe('clipboardStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('loads items with default query', async () => {
    mockedApi.getClipboardItems.mockResolvedValueOnce([item('1', 'hello')])
    const store = useClipboardStore()

    await store.loadItems()

    expect(mockedApi.getClipboardItems).toHaveBeenCalledWith({
      query: '',
      kind: 'all',
      favoriteOnly: false,
      limit: 100,
      offset: 0
    })
    expect(store.items).toHaveLength(1)
  })

  it('builds today date filter in local time', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-30T12:00:00'))
    mockedApi.getClipboardItems.mockResolvedValueOnce([])
    const store = useClipboardStore()
    store.datePreset = 'today'

    await store.loadItems()

    expect(mockedApi.getClipboardItems).toHaveBeenCalledWith(expect.objectContaining({
      startAt: new Date('2026-05-30T00:00:00').getTime(),
      endAt: new Date('2026-05-30T23:59:59.999').getTime()
    }))
    vi.useRealTimers()
  })

  it('builds custom date filter and swaps reversed dates', async () => {
    mockedApi.getClipboardItems.mockResolvedValueOnce([])
    const store = useClipboardStore()
    store.datePreset = 'custom'
    store.customStartDate = '2026-05-30'
    store.customEndDate = '2026-05-01'

    await store.loadItems()

    expect(mockedApi.getClipboardItems).toHaveBeenCalledWith(expect.objectContaining({
      startAt: new Date('2026-05-01T00:00:00').getTime(),
      endAt: new Date('2026-05-30T23:59:59.999').getTime()
    }))
  })

  it('searches when query is set', async () => {
    mockedApi.searchClipboardItems.mockResolvedValueOnce([item('2', 'world')])
    const store = useClipboardStore()
    store.searchQuery = 'world'

    await store.searchItems()

    expect(mockedApi.searchClipboardItems).toHaveBeenCalledWith({
      query: 'world',
      kind: 'all',
      favoriteOnly: false,
      limit: 100,
      offset: 0
    })
    expect(store.items[0].title).toBe('world')
  })

  it('copies and pastes selected item', async () => {
    mockedApi.copyClipboardItem.mockResolvedValueOnce()
    mockedApi.pasteClipboardItem.mockResolvedValueOnce()
    const store = useClipboardStore()

    await store.copyItem('1', 'plain_text')
    await store.pasteItem('1', 'original')

    expect(mockedApi.copyClipboardItem).toHaveBeenCalledWith('1', 'plain_text')
    expect(mockedApi.pasteClipboardItem).toHaveBeenCalledWith('1', 'original')
  })

  it('copies selected visible items in list order', async () => {
    mockedApi.copyClipboardItems.mockResolvedValueOnce()
    const store = useClipboardStore()
    store.items = [item('1', 'hello'), item('2', 'world'), item('3', 'again')]
    store.selectedIds = new Set(['3', '1'])

    const copiedCount = await store.copySelectedItems('plain_text')

    expect(copiedCount).toBe(2)
    expect(mockedApi.copyClipboardItems).toHaveBeenCalledWith(['1', '3'], 'plain_text')
  })

  it('deletes item and removes it from state', async () => {
    mockedApi.deleteClipboardItem.mockResolvedValueOnce()
    const store = useClipboardStore()
    store.items = [item('1', 'hello')]
    store.selectedItemId = '1'

    await store.deleteItem('1')

    expect(store.items).toEqual([])
    expect(store.selectedItemId).toBeNull()
  })

  it('updates item note in list and selected detail', async () => {
    mockedApi.updateClipboardItemNote.mockResolvedValueOnce('重要备注')
    const store = useClipboardStore()
    store.items = [item('1', 'hello')]
    store.selectedDetail = { ...item('1', 'hello'), availableFormats: ['original'] }

    const result = await store.updateItemNote('1', '  重要备注  ')

    expect(result).toBe('重要备注')
    expect(mockedApi.updateClipboardItemNote).toHaveBeenCalledWith('1', '  重要备注  ')
    expect(store.items[0].note).toBe('重要备注')
    expect(store.selectedDetail?.note).toBe('重要备注')
  })

  it('clears preview when selected item is unchecked', () => {
    const store = useClipboardStore()
    store.items = [item('1', 'hello')]
    store.selectedItemId = '1'
    store.selectedDetail = { ...item('1', 'hello'), availableFormats: ['original'] }
    store.selectedIds = new Set(['1'])

    store.toggleSelected('1')

    expect(store.selectedIds.size).toBe(0)
    expect(store.selectedItemId).toBeNull()
    expect(store.selectedDetail).toBeNull()
  })

  it('clears preview when all selections are cleared', () => {
    const store = useClipboardStore()
    store.items = [item('1', 'hello')]
    store.selectedItemId = '1'
    store.selectedDetail = { ...item('1', 'hello'), availableFormats: ['original'] }
    store.selectedIds = new Set(['1'])

    store.clearSelection()

    expect(store.selectedIds.size).toBe(0)
    expect(store.selectedItemId).toBeNull()
    expect(store.selectedDetail).toBeNull()
  })

  it('resolves context action ids from multi-selection', () => {
    const store = useClipboardStore()
    store.items = [item('1', 'hello'), item('2', 'world'), item('3', 'again')]
    store.selectedIds = new Set(['1', '3'])

    expect(store.selectedIdsForAction('2')).toEqual(['1', '3'])
  })

  it('uses context item when only one visible item is selected', () => {
    const store = useClipboardStore()
    store.items = [item('1', 'hello'), item('2', 'world')]
    store.selectedIds = new Set(['1'])

    expect(store.selectedIdsForAction('2')).toEqual(['2'])
  })

  it('selects all visible clipboard items', () => {
    const store = useClipboardStore()
    store.items = [item('1', 'hello'), item('2', 'world')]

    store.selectAllVisible()

    expect(Array.from(store.selectedIds).sort()).toEqual(['1', '2'])
  })

  it('inverts visible clipboard selection', () => {
    const store = useClipboardStore()
    store.items = [item('1', 'hello'), item('2', 'world'), item('3', 'again')]
    store.selectedIds = new Set(['1', 'outside'])

    store.invertVisibleSelection()

    expect(Array.from(store.selectedIds).sort()).toEqual(['2', '3', 'outside'])
  })

  it('loads and updates settings', async () => {
    const current = settings()
    const updated = { ...current, autoPaste: true, backupDirectory: 'D:/ClipboardBackup' }
    mockedApi.getClipboardSettings.mockResolvedValueOnce(current)
    mockedApi.updateClipboardSettings.mockResolvedValueOnce(updated)
    const store = useClipboardStore()

    await store.loadSettings()
    await store.updateSettings({ autoPaste: true, backupDirectory: 'D:/ClipboardBackup' })

    expect(store.settings.autoPaste).toBe(true)
    expect(store.settings.backupDirectory).toBe('D:/ClipboardBackup')
    expect(mockedApi.updateClipboardSettings).toHaveBeenCalledWith(updated)
  })
})
