import { invoke } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import type {
  ClipboardItemDetail,
  ClipboardGroup,
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

export async function copyClipboardItems(ids: string[], format: ClipboardPasteFormat): Promise<void> {
  await invoke('copy_clipboard_items', { ids, format })
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

export async function updateClipboardItemNote(id: string, note: string | null): Promise<string | null> {
  return await invoke<string | null>('update_clipboard_item_note', { id, note })
}

export async function getClipboardGroups(): Promise<ClipboardGroup[]> {
  return invoke<ClipboardGroup[]>('get_clipboard_groups')
}

export async function createClipboardGroup(name: string): Promise<ClipboardGroup> {
  return invoke<ClipboardGroup>('create_clipboard_group', { name })
}

export async function renameClipboardGroup(id: string, name: string): Promise<ClipboardGroup> {
  return invoke<ClipboardGroup>('rename_clipboard_group', { id, name })
}

export async function deleteClipboardGroup(id: string): Promise<void> {
  await invoke('delete_clipboard_group', { id })
}

export async function moveClipboardItems(ids: string[], groupId: string | null): Promise<void> {
  await invoke('move_clipboard_items', { ids, groupId })
}

export async function setClipboardItemsPinned(ids: string[], isPinned: boolean): Promise<void> {
  await invoke('set_clipboard_items_pinned', { ids, isPinned })
}

export async function listenClipboardChanged(handler: () => void): Promise<() => void> {
  return await listen('clipboard://changed', handler)
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
