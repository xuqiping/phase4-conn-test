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

  it('deletes item and removes it from state', async () => {
    mockedApi.deleteClipboardItem.mockResolvedValueOnce()
    const store = useClipboardStore()
    store.items = [item('1', 'hello')]
    store.selectedItemId = '1'

    await store.deleteItem('1')

    expect(store.items).toEqual([])
    expect(store.selectedItemId).toBeNull()
  })

  it('loads and updates settings', async () => {
    const current = settings()
    const updated = { ...current, autoPaste: true }
    mockedApi.getClipboardSettings.mockResolvedValueOnce(current)
    mockedApi.updateClipboardSettings.mockResolvedValueOnce(updated)
    const store = useClipboardStore()

    await store.loadSettings()
    await store.updateSettings({ autoPaste: true })

    expect(store.settings.autoPaste).toBe(true)
    expect(mockedApi.updateClipboardSettings).toHaveBeenCalledWith(updated)
  })
})
