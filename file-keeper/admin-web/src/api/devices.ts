import request, { unwrap } from './request'
import type { DeviceInfo } from '@/types'

export function listDevices(userId: number): Promise<DeviceInfo[]> {
  return unwrap(request.get(`/api/admin/users/${userId}/devices`))
}

export function disableDevice(userId: number, deviceId: string, note: string): Promise<void> {
  return unwrap(request.post(`/api/admin/users/${userId}/devices/${deviceId}/disable`, { note }))
}
