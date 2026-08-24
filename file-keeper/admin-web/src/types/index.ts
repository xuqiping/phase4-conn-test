export interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

export interface UserSummary {
  id: number
  email?: string | null
  phone?: string | null
  role: string
  status: string
  emailVerified: boolean
  phoneVerified: boolean
}

export interface DeviceInfo {
  id: number
  userId: number
  deviceId: string
  fingerprintHash: string
  deviceName: string
  status: string
  lastSeenAt: string | null
  timeSyncAnomalyCount: number
}

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  expiresInSeconds: number
  user: UserSummary
}

export const USER_STATUS_MAP: Record<string, { label: string; type: 'default' | 'info' | 'success' | 'warning' | 'error' }> = {
  pending_verification: { label: '待验证', type: 'warning' },
  pending_review: { label: '待审核', type: 'info' },
  active: { label: '正常', type: 'success' },
  disabled: { label: '已禁用', type: 'error' }
}

export interface DashboardStats {
  totalUsers: number
  activeUsers: number
  disabledUsers: number
  activeDevices: number
}
