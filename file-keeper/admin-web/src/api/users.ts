import request, { unwrap } from './request'
import type { PageResult, UserSummary } from '@/types'

export function listUsers(params: { page: number; size: number; status?: string }): Promise<PageResult<UserSummary>> {
  return unwrap(request.get('/api/admin/users', { params }))
}

export function getUser(id: number): Promise<UserSummary> {
  return unwrap(request.get(`/api/admin/users/${id}`))
}

export function approveUser(id: number, note: string): Promise<UserSummary> {
  return unwrap(request.post(`/api/admin/users/${id}/approve`, { note }))
}

export function disableUser(id: number, note: string): Promise<UserSummary> {
  return unwrap(request.post(`/api/admin/users/${id}/disable`, { note }))
}

export function enableUser(id: number, note: string): Promise<UserSummary> {
  return unwrap(request.post(`/api/admin/users/${id}/enable`, { note }))
}

export function updateUserSettings(
  id: number,
  deviceLimit: number,
  offlineCacheMinutes: number
): Promise<UserSummary> {
  return unwrap(request.put(`/api/admin/users/${id}/settings`, { deviceLimit, offlineCacheMinutes }))
}
