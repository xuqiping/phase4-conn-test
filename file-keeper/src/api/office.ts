import { invoke } from '@tauri-apps/api/core'
import type {
  OfficeCreateTaskRequest,
  OfficeCredentialReference,
  OfficePreflightResponse,
  OfficeTaskPage,
  OfficeTaskSummary
} from '../types/office'

export async function createOfficePreflight(
  request: OfficeCreateTaskRequest
): Promise<OfficePreflightResponse> {
  return await invoke<OfficePreflightResponse>('office_create_preflight', { request })
}

export async function confirmOfficeTask(taskId: string): Promise<OfficeTaskSummary> {
  return await invoke<OfficeTaskSummary>('office_confirm_task', { taskId })
}

export async function startOfficeTask(taskId: string): Promise<OfficeTaskSummary> {
  return await invoke<OfficeTaskSummary>('office_start_task', { taskId })
}

export async function cancelOfficeTask(taskId: string): Promise<OfficeTaskSummary> {
  return await invoke<OfficeTaskSummary>('office_cancel_task', { taskId })
}

export async function listOfficeTasks(page = 1, pageSize = 50): Promise<OfficeTaskPage> {
  return await invoke<OfficeTaskPage>('office_list_tasks', { page, pageSize })
}

export async function recoverOfficeTasks(): Promise<OfficeTaskSummary[]> {
  return await invoke<OfficeTaskSummary[]>('office_recover_tasks')
}

export async function saveOfficeCredential(
  path: string,
  password: string
): Promise<OfficeCredentialReference> {
  return await invoke<OfficeCredentialReference>('office_save_credential', { path, password })
}

export async function deleteOfficeCredential(bindingId: string): Promise<void> {
  await invoke('office_delete_credential', { bindingId })
}
