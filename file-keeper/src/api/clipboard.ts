import { invoke } from '@tauri-apps/api/core'
import type {
  ClipboardItemDetail,
  ClipboardItemSummary,
  ClipboardPasteFormat,
  ClipboardQuery,
  ClipboardSettings,
  ClipboardStorageUsage
} from '../types/clipboard'

export async function startClipboardMonitor(): Promise<void> {
  await invoke('start_clipboard_monitor')
}

export async function stopClipboardMonitor(): Promise<void> {
  await invoke('stop_clipboard_monitor')
}

export async function getClipboardItems(query: ClipboardQuery): Promise<ClipboardItemSummary[]> {
  return await invoke<ClipboardItemSummary[]>('get_clipboard_items', { query })
}

export async function searchClipboardItems(query: ClipboardQuery): Promise<ClipboardItemSummary[]> {
  return await invoke<ClipboardItemSummary[]>('search_clipboard_items', { query })
}

export async function getClipboardItemDetail(id: string): Promise<ClipboardItemDetail> {
  return await invoke<ClipboardItemDetail>('get_clipboard_item_detail', { id })
}

export async function copyClipboardItem(id: string, format: ClipboardPasteFormat): Promise<void> {
  await invoke('copy_clipboard_item', { id, format })
}

export async function pasteClipboardItem(id: string, format: ClipboardPasteFormat): Promise<void> {
  await invoke('paste_clipboard_item', { id, format })
}

export async function rememberClipboardTargetWindow(): Promise<void> {
  await invoke('remember_clipboard_target_window')
}

export async function deleteClipboardItem(id: string): Promise<void> {
  await invoke('delete_clipboard_item', { id })
}

export async function clearClipboardHistory(scope: 'all' | 'non_text_cache' | 'security_events'): Promise<void> {
  await invoke('clear_clipboard_history', { scope })
}

export async function getClipboardSettings(): Promise<ClipboardSettings> {
  return await invoke<ClipboardSettings>('get_clipboard_settings')
}

export async function updateClipboardSettings(settings: ClipboardSettings): Promise<ClipboardSettings> {
  return await invoke<ClipboardSettings>('update_clipboard_settings', { settings })
}

export async function getClipboardStorageUsage(): Promise<ClipboardStorageUsage> {
  return await invoke<ClipboardStorageUsage>('get_clipboard_storage_usage')
}

export async function rebuildClipboardIndex(): Promise<void> {
  await invoke('rebuild_clipboard_index')
}

export async function retryLinkPreview(id: string): Promise<void> {
  await invoke('retry_link_preview', { id })
}
