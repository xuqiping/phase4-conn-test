import request, { unwrap } from './request'
import type { DashboardStats } from '@/types'

export function getDashboardStats(): Promise<DashboardStats> {
  return unwrap(request.get('/api/admin/stats/dashboard'))
}
