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
  pushPlatform?: PushPlatform
  pushTargetId?: string
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
  pushPlatform?: PushPlatform
  pushTargetId?: string
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

export interface ReportPushTarget {
  id?: number
  platform: 'FEISHU' | 'DINGTALK' | 'WECHAT_WORK' | 'SLACK'
  targetType: 'GROUP' | 'USER'
  targetId: string
  credential?: string
  hasCredential?: boolean
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
  pushTargets: ReportPushTarget[]
}

export interface WorkReport {
  id: number
  reportType: 'DAILY' | 'WEEKLY'
  title: string
  content: string
  generatedAt: string
  status: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}
