import request, { unwrap } from './request'
import type { AnonymousDevice, IpDeviceCount, PageResult } from '@/types'

export function listAnonymousDevices(params: {
  page: number
  size: number
  status?: string
  minResetCount?: number
  firstSeenIp?: string
}): Promise<PageResult<AnonymousDevice>> {
  return unwrap(request.get('/api/admin/anonymous-devices', { params }))
}

export function getIpAbuse(minCount = 5): Promise<IpDeviceCount[]> {
  return unwrap(request.get('/api/admin/anonymous-devices/ip-abuse', { params: { minCount } }))
}

export function resetTrial(deviceId: string): Promise<AnonymousDevice> {
  return unwrap(request.post(`/api/admin/anonymous-devices/${encodeURIComponent(deviceId)}/reset-trial`))
}

export function disableAnonymousDevice(deviceId: string): Promise<AnonymousDevice> {
  return unwrap(request.post(`/api/admin/anonymous-devices/${encodeURIComponent(deviceId)}/disable`))
}

export function enableAnonymousDevice(deviceId: string): Promise<AnonymousDevice> {
  return unwrap(request.post(`/api/admin/anonymous-devices/${encodeURIComponent(deviceId)}/enable`))
}
