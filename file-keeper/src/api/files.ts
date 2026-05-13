// Tauri API 封装 - 文件操作
import { invoke } from '@tauri-apps/api/core'
import { open } from '@tauri-apps/plugin-dialog'
import { documentDir } from '@tauri-apps/api/path'

export async function pickFile(): Promise<string | null> {
  const defaultPath = await documentDir()
  const selected = await open({
    directory: false,
    multiple: false,
    defaultPath: defaultPath ?? undefined
  })

  return selected as string | null
}

export async function pickFolder(): Promise<string | null> {
  const selected = await open({
    multiple: false,
    directory: true
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
