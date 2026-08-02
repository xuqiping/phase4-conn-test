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
 * Close a single process by window handle
 */
export async function closeProcess(windowHandle: number): Promise<void> {
  try {
    await invoke('close_app_process', { windowHandle })
  } catch (error) {
    console.error(`Failed to close process with handle ${windowHandle}:`, error)
    throw new Error(`Failed to close process: ${error}`)
  }
}

/**
 * Close multiple processes by window handles
 */
export async function closeProcesses(windowHandles: number[]): Promise<CloseResult> {
  try {
    return await invoke<CloseResult>('close_app_processes', { windowHandles })
  } catch (error) {
    console.error('Failed to close processes:', error)
    throw new Error(`Failed to close processes: ${error}`)
  }
}

export async function killProcess(pid: number): Promise<void> {
  try {
    await invoke('kill_app_process', { pid })
  } catch (error) {
    console.error(`Failed to kill process with pid ${pid}:`, error)
    throw new Error(`Failed to kill process: ${error}`)
  }
}

export async function killProcesses(pids: number[]): Promise<CloseResult> {
  try {
    return await invoke<CloseResult>('kill_app_processes', { pids })
  } catch (error) {
    console.error('Failed to kill processes:', error)
    throw new Error(`Failed to kill processes: ${error}`)
  }
}

export async function activateWindow(windowHandle: number): Promise<void> {
  try {
    await invoke('activate_app_window', { windowHandle })
  } catch (error) {
    console.error(`Failed to activate window with handle ${windowHandle}:`, error)
    throw new Error(`Failed to activate window: ${error}`)
  }
}
