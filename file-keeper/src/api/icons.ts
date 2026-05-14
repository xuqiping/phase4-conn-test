import { invoke } from '@tauri-apps/api/core'

export async function getFileIcon(filePath: string): Promise<string | null> {
  try {
    const iconData = await invoke<string>('get_file_icon', { path: filePath })
    return iconData || null
  } catch (error) {
    console.error('Failed to get icon for', filePath, error)
    return null
  }
}
