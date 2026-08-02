import { beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import {
  copyClipboardItem,
  copyClipboardItems,
  deleteClipboardItem,
  getClipboardItemDetail,
  getClipboardItems,
  getClipboardSettings,
  getClipboardStorageUsage,
  pasteClipboardItem,
  rememberClipboardTargetWindow,
  searchClipboardItems,
  startClipboardMonitor,
  stopClipboardMonitor,
  updateClipboardItemNote,
  updateClipboardSettings
} from '../clipboard'
import type { ClipboardSettings } from '../../types/clipboard'

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn()
}))

const mockedInvoke = vi.mocked(invoke)

describe('clipboard api', () => {
  beforeEach(() => {
    mockedInvoke.mockReset()
  })

  it('loads clipboard items with query payload', async () => {
    mockedInvoke.mockResolvedValueOnce([{ id: 'item-1', kind: 'text', title: 'hello' }])

    const result = await getClipboardItems({ query: 'hel', kind: 'text', startAt: 100, endAt: 200, limit: 20, offset: 0 })

    expect(mockedInvoke).toHaveBeenCalledWith('get_clipboard_items', {
      query: { query: 'hel', kind: 'text', startAt: 100, endAt: 200, limit: 20, offset: 0 }
    })
    expect(result[0].id).toBe('item-1')
  })

  it('searches clipboard items', async () => {
    mockedInvoke.mockResolvedValueOnce([])

    await searchClipboardItems({ query: 'ocr', limit: 10, offset: 0 })

    expect(mockedInvoke).toHaveBeenCalledWith('search_clipboard_items', {
      query: { query: 'ocr', limit: 10, offset: 0 }
    })
  })

  it('loads clipboard item detail', async () => {
    mockedInvoke.mockResolvedValueOnce({ id: 'item-1', kind: 'text', title: 'hello', text: 'hello' })

    await getClipboardItemDetail('item-1')

    expect(mockedInvoke).toHaveBeenCalledWith('get_clipboard_item_detail', { id: 'item-1' })
  })

  it('copies and pastes items with format', async () => {
    mockedInvoke.mockResolvedValue(undefined)

    await copyClipboardItem('item-1', 'plain_text')
    await pasteClipboardItem('item-1', 'markdown')

    expect(mockedInvoke).toHaveBeenNthCalledWith(1, 'copy_clipboard_item', {
      id: 'item-1',
      format: 'plain_text'
    })
    expect(mockedInvoke).toHaveBeenNthCalledWith(2, 'paste_clipboard_item', {
      id: 'item-1',
      format: 'markdown'
    })
  })

  it('copies multiple clipboard items with format', async () => {
    mockedInvoke.mockResolvedValue(undefined)

    await copyClipboardItems(['item-1', 'item-2'], 'plain_text')

    expect(mockedInvoke).toHaveBeenCalledWith('copy_clipboard_items', {
      ids: ['item-1', 'item-2'],
      format: 'plain_text'
    })
  })

  it('remembers clipboard target window', async () => {
    mockedInvoke.mockResolvedValue(undefined)

    await rememberClipboardTargetWindow()

    expect(mockedInvoke).toHaveBeenCalledWith('remember_clipboard_target_window')
  })

  it('deletes clipboard items', async () => {
    mockedInvoke.mockResolvedValue(undefined)

    await deleteClipboardItem('item-1')

    expect(mockedInvoke).toHaveBeenCalledWith('delete_clipboard_item', { id: 'item-1' })
  })

  it('updates clipboard item note', async () => {
    mockedInvoke.mockResolvedValueOnce('important').mockResolvedValueOnce(null)

    await updateClipboardItemNote('item-1', 'important')
    await updateClipboardItemNote('item-1', null)

    expect(mockedInvoke).toHaveBeenNthCalledWith(1, 'update_clipboard_item_note', { id: 'item-1', note: 'important' })
    expect(mockedInvoke).toHaveBeenNthCalledWith(2, 'update_clipboard_item_note', { id: 'item-1', note: null })
  })

  it('starts and stops monitor', async () => {
    mockedInvoke.mockResolvedValue(undefined)

    await startClipboardMonitor()
    await stopClipboardMonitor()

    expect(mockedInvoke).toHaveBeenNthCalledWith(1, 'start_clipboard_monitor')
    expect(mockedInvoke).toHaveBeenNthCalledWith(2, 'stop_clipboard_monitor')
  })

  it('loads and updates settings', async () => {
    const settings: ClipboardSettings = {
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
    mockedInvoke.mockResolvedValueOnce(settings).mockResolvedValueOnce(settings)

    await getClipboardSettings()
    await updateClipboardSettings(settings)

    expect(mockedInvoke).toHaveBeenNthCalledWith(1, 'get_clipboard_settings')
    expect(mockedInvoke).toHaveBeenNthCalledWith(2, 'update_clipboard_settings', { settings })
  })

  it('loads storage usage', async () => {
    mockedInvoke.mockResolvedValueOnce({ totalBytes: 10, limitBytes: 20, byType: [] })

    const usage = await getClipboardStorageUsage()

    expect(mockedInvoke).toHaveBeenCalledWith('get_clipboard_storage_usage')
    expect(usage.totalBytes).toBe(10)
  })
})
