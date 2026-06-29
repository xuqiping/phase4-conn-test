import type { InspirationNote, InspirationNoteForm } from '@/types/inspiration'
import { request, WorkReportApiError, isWorkReportApiError } from '@/api/workReport'

export { WorkReportApiError, isWorkReportApiError }

const BASE_PATH = '/api/client/work-report/inspirations'

export async function listInspirations(
  baseUrl: string,
  token: string,
  deviceId: string,
  tags?: string[],
  startDate?: string,
  endDate?: string,
): Promise<InspirationNote[]> {
  const params = new URLSearchParams()
  if (tags && tags.length > 0) {
    tags.forEach(tag => params.append('tags', tag))
  }
  if (startDate) params.set('startDate', startDate)
  if (endDate) params.set('endDate', endDate)
  const query = params.toString()
  return request<InspirationNote[]>(
    baseUrl,
    token,
    deviceId,
    query ? `?${query}` : '',
    {},
    1,
    BASE_PATH,
  )
}

export async function createInspiration(
  baseUrl: string,
  token: string,
  deviceId: string,
  note: InspirationNoteForm,
): Promise<InspirationNote> {
  return request<InspirationNote>(baseUrl, token, deviceId, '', {
    method: 'POST',
    body: JSON.stringify(note),
  }, 1, BASE_PATH)
}

export async function updateInspiration(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
  note: InspirationNoteForm,
): Promise<InspirationNote> {
  return request<InspirationNote>(baseUrl, token, deviceId, `/${id}`, {
    method: 'PUT',
    body: JSON.stringify(note),
  }, 1, BASE_PATH)
}

export async function reviewInspiration(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
): Promise<InspirationNote> {
  return request<InspirationNote>(baseUrl, token, deviceId, `/${id}/review`, {
    method: 'POST',
  }, 1, BASE_PATH)
}

export async function deleteInspiration(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
): Promise<void> {
  return request<void>(baseUrl, token, deviceId, `/${id}`, {
    method: 'DELETE',
  }, 1, BASE_PATH)
}
