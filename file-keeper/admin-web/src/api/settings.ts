import request, { unwrap } from './request'
import type { SystemSettings } from '@/types'

export function getSettings(): Promise<SystemSettings> {
  return unwrap(request.get('/api/admin/settings'))
}

export function updateSettings(payload: SystemSettings): Promise<SystemSettings> {
  return unwrap(request.put('/api/admin/settings', payload))
}
