import { invoke } from '@tauri-apps/api/core'

export interface GitLogEntry {
  hash: string
  date: string
  message: string
  author: string
}

export async function fetchGitLogs(repoPath: string, since: string, until?: string): Promise<GitLogEntry[]> {
  return invoke<GitLogEntry[]>('fetch_git_logs', { repoPath, since, until })
}

export async function showNotification(title: string, body: string): Promise<void> {
  return invoke('show_work_report_notification', { title, body })
}

export async function exportReportMarkdown(title: string, content: string): Promise<{ path: string }> {
  return invoke<{ path: string }>('export_report_markdown', { title, content })
}
