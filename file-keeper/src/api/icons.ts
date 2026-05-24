import { invoke } from '@tauri-apps/api/core'

export async function getFileIcon(filePath: string, useRealIcon?: boolean): Promise<string | null> {
  try {
    const iconData = await invoke<string>('get_file_icon', {
      path: filePath,
      useRealIcon: useRealIcon ?? true // 默认使用真实图标
    })
    return iconData || null
  } catch (error) {
    console.error('Failed to get icon for', filePath, error)
    return null
  }
}
