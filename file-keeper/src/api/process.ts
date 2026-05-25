import { invoke } from '@tauri-apps/api/core'
import type { ProcessInfo, CloseResult } from '../types/process'

/**
 * Get all running processes
 */
export async function getRunningProcesses(): Promise<ProcessInfo[]> {
  try {
    return await invoke<ProcessInfo[]>('get_running_processes')
  } catch (error) {
    console.error('Failed to get running processes:', error)
    throw new Error(`Failed to get running processes: ${error}`)
  }
}

/**
 * Close a single process by PID
 */
export async function closeProcess(pid: number): Promise<CloseResult> {
  try {
    return await invoke<CloseResult>('close_process', { pid })
  } catch (error) {
    console.error(`Failed to close process ${pid}:`, error)
    return {
      pid,
      success: false,
      error: String(error)
    }
  }
}

/**
 * Close multiple processes by PIDs
 */
export async function closeProcesses(pids: number[]): Promise<CloseResult[]> {
  try {
    return await invoke<CloseResult[]>('close_processes', { pids })
  } catch (error) {
    console.error('Failed to close processes:', error)
    throw new Error(`Failed to close processes: ${error}`)
  }
}
