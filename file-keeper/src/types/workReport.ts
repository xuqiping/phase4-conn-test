export type RecurrenceType = 'DAILY' | 'WEEKLY' | 'MONTHLY'
export type FuturePlanStatus = 'PENDING' | 'REMINDED' | 'COMPLETED' | 'CANCELLED'
export type PushPlatform = 'FEISHU' | 'DINGTALK' | 'WECHAT_WORK' | 'SLACK'

export interface WorkLog {
  id?: number
  logDate: string
  content: string
  tags?: string
  source?: string
  sortOrder?: number
  createdAt?: string
  updatedAt?: string
}

export interface WorkPlan {
  id?: number
  planDate: string
  content: string
  description?: string
  priority?: 'HIGH' | 'MEDIUM' | 'LOW'
  plannedStartTime?: string
  plannedEndTime?: string
  completed: boolean
  sortOrder?: number
  createdAt?: string
  updatedAt?: string
}

export interface FixedWorkItem {
  id?: number
  content: string
  description?: string
  recurrenceType: RecurrenceType
  reminderTime: string
  reminderDays?: string
  timezone?: string
  reminderEnabled: boolean
  pushTargetId?: number
  pushPlatform?: PushPlatform
  pushTargetIdText?: string
  pushCredential?: string
  hasCredential?: boolean
  sortOrder?: number
  completedToday?: boolean
  createdAt?: string
  updatedAt?: string
}

export interface FuturePlan {
  id?: number
  content: string
  description?: string
  scheduledAt: string
  timezone?: string
  reminderEnabled: boolean
  reminderMinutesBefore?: number
  pushTargetId?: number
  pushPlatform?: PushPlatform
  pushTargetIdText?: string
  pushCredential?: string
  hasCredential?: boolean
  status: FuturePlanStatus
  sortOrder?: number
  createdAt?: string
  updatedAt?: string
}

export interface ReportTemplate {
  id: number
  name: string
  type: 'DAILY' | 'WEEKLY'
  content: string
  isDefault: boolean
}

export interface PushCredential {
  id: number
  name: string
  platform: PushPlatform
  hasCredential: boolean
}

export interface PushCredentialForm {
  name: string
  platform: PushPlatform
  credential: string
}

export interface PushTarget {
  id: number
  name: string
  platform: PushPlatform
  targetType: 'GROUP' | 'USER'
  targetId: string
  credentialId: number
  credentialName?: string
}

export interface PushTargetForm {
  name: string
  platform: PushPlatform
  targetType: 'GROUP' | 'USER'
  targetId: string
  credentialId?: number
}

export interface ReportConfig {
  id?: number
  name: string
  reportType: 'DAILY' | 'WEEKLY'
  templateId: number
  templateName?: string
  cronExpression: string
  timezone?: string
  enabled: boolean
  aiEnabled: boolean
  aiConfigId?: number
  includeInspirationDigest: boolean
  pushTargetIds: number[]
  pushTargets?: PushTarget[]
}

export interface WorkReport {
  id: number
  reportType: 'DAILY' | 'WEEKLY'
  title: string
  content: string
  generatedAt: string
  status: string
  completionRate?: number
  consecutiveMissDays?: number
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}
