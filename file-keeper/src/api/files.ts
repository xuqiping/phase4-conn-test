// Tauri API 封装 - 文件操作
import { invoke } from '@tauri-apps/api/core'
import { open } from '@tauri-apps/plugin-dialog'
import { documentDir } from '@tauri-apps/api/path'
import type { ManagedArtifact } from '../types/file'

export interface FavoritePathDescriptor {
  name: string
  path: string
  sourcePath: string
  itemType: 'file' | 'folder'
  managedArtifact?: ManagedArtifact
  shortcutTargetPath?: string
}

export async function pickFile(): Promise<string | null> {
  const defaultPath = await documentDir()
  const selected = await open({
    directory: false,
    multiple: false,
    defaultPath: defaultPath ?? undefined
  })

  return selected as string | null
}

export async function pickFolder(defaultPathOverride?: string): Promise<string | null> {
  const defaultPath = defaultPathOverride || await documentDir()
  const selected = await open({
    directory: true,
    multiple: false,
    defaultPath: defaultPath ?? undefined
  })

  return selected as string | null
}

export async function openFile(path: string): Promise<void> {
  return invoke('open_file', { path })
}

export async function validatePath(path: string): Promise<boolean> {
  return invoke('validate_path', { path })
}

export async function showInFolder(path: string): Promise<void> {
  return invoke('show_in_folder', { path })
}

export async function importFavoritePath(path: string): Promise<FavoritePathDescriptor> {
  return invoke('import_favorite_path', { path })
}

export async function validateFavoritePath(path: string, shortcutTargetPath?: string): Promise<boolean> {
  return invoke('validate_favorite_path', { path, shortcutTargetPath })
}

export async function deleteManagedShortcut(cachePath: string): Promise<void> {
  return invoke('delete_managed_shortcut', { cachePath })
}
