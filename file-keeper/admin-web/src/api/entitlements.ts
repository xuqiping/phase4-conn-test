import request, { unwrap } from './request'
import type { ModuleEntitlement } from '@/types'

export function listEntitlements(userId: number): Promise<ModuleEntitlement[]> {
  return unwrap(request.get(`/api/admin/users/${userId}/entitlements`))
}

export function grantEntitlement(
  userId: number,
  moduleCode: string,
  expiresAt?: string | null
): Promise<ModuleEntitlement> {
  return unwrap(request.post(`/api/admin/users/${userId}/entitlements`, { moduleCode, expiresAt: expiresAt ?? null }))
}

export function updateEntitlement(
  userId: number,
  entitlementId: number,
  data: { enabled?: boolean; expiresAt?: string | null }
): Promise<ModuleEntitlement> {
  return unwrap(request.put(`/api/admin/users/${userId}/entitlements/${entitlementId}`, data))
}

export function revokeEntitlement(userId: number, entitlementId: number): Promise<void> {
  return unwrap(request.delete(`/api/admin/users/${userId}/entitlements/${entitlementId}`))
}
