// Tauri API 封装 - 文件操作
import { invoke } from '@tauri-apps/api/core'

export async function openFile(path: string): Promise<void> {
  return invoke('open_file', { path })
}

export async function validatePath(path: string): Promise<boolean> {
  return invoke('validate_path', { path })
}
