import { invoke } from '@tauri-apps/api/core'
import type { ProcessInfo } from '../types/process'

export async function findFileProcesses(filePath: string): Promise<ProcessInfo[]> {
  return await invoke<ProcessInfo[]>('find_file_processes', { filePath })
}

export async function closeProcess(windowHandle: number): Promise<void> {
  return await invoke<void>('close_app_process', { windowHandle })
}

export async function closeFileProcesses(filePath: string): Promise<number> {
  return await invoke<number>('close_file_processes', { filePath })
}
