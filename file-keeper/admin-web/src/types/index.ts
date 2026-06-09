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
  deviceLimit: number
  offlineCacheMinutes: number
}

export interface ModuleEntitlement {
  id: number
  userId: number
  moduleCode: string
  enabled: boolean
  expiresAt: string | null
}

export interface DeviceInfo {
  id: number
  userId: number
  deviceId: string
  fingerprintHash: string
  deviceName: string
  status: string
  lastSeenAt: string | null
}

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  expiresInSeconds: number
  user: UserSummary
}

export type ModuleCode = 'files' | 'processes' | 'clipboard'

export const USER_STATUS_MAP: Record<string, { label: string; type: 'default' | 'info' | 'success' | 'warning' | 'error' }> = {
  pending_verification: { label: '待验证', type: 'warning' },
  pending_review: { label: '待审核', type: 'info' },
  active: { label: '正常', type: 'success' },
  disabled: { label: '已禁用', type: 'error' }
}

export const MODULE_LABEL_MAP: Record<string, string> = {
  files: '文件管理',
  processes: '进程管理',
  clipboard: '剪贴板'
}
