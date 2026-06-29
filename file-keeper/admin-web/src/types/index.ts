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
  timeSyncAnomalyCount: number
}

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  expiresInSeconds: number
  user: UserSummary
}

export type ModuleCode = 'files' | 'processes' | 'clipboard' | 'work-report' | 'ai'

export const USER_STATUS_MAP: Record<string, { label: string; type: 'default' | 'info' | 'success' | 'warning' | 'error' }> = {
  pending_verification: { label: '待验证', type: 'warning' },
  pending_review: { label: '待审核', type: 'info' },
  active: { label: '正常', type: 'success' },
  disabled: { label: '已禁用', type: 'error' }
}

export const MODULE_LABEL_MAP: Record<string, string> = {
  files: '文件管理',
  processes: '进程管理',
  clipboard: '剪贴板',
  'work-report': '工作汇报',
  ai: 'AI 能力'
}

export interface DashboardStats {
  totalUsers: number
  pendingReviewUsers: number
  activeUsers: number
  disabledUsers: number
  pendingVerificationUsers: number
  activeDevices: number
  expiringSoonEntitlements: number
  expiredEntitlements: number
}

export interface SystemSettings {
  defaultDeviceLimit: number
  defaultOfflineCacheMinutes: number
  anonymousTrialDays: number
  freeModuleChangeDays: number
}

export interface AnonymousDevice {
  id: number
  deviceId: string
  fingerprintHash: string
  deviceName: string
  status: string
  trialStartedAt: string | null
  trialExpiresAt: string | null
  freeModuleCode: string | null
  freeModuleSelectedAt: string | null
  lastFreeModuleChangedAt: string | null
  lastSeenAt: string | null
  firstSeenIp: string | null
  userAgentHash: string | null
  trialResetCount: number
}

export interface IpDeviceCount {
  firstSeenIp: string
  deviceCount: number
}

export const ANONYMOUS_DEVICE_STATUS_MAP: Record<string, { label: string; type: 'default' | 'info' | 'success' | 'warning' | 'error' }> = {
  active: { label: '正常', type: 'success' },
  disabled: { label: '已禁用', type: 'error' }
}
