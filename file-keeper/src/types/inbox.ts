export type InboxStatus = 'PENDING' | 'CONFIRMED' | 'IGNORED' | 'FAILED'

export type InboxIntent =
  | 'complete_fixed_work'
  | 'add_work_log'
  | 'add_inspiration'
  | 'help'
  | 'unknown'

export interface InboxMessage {
  id: number
  userId: number
  platform: string
  platformMessageId: string
  senderId?: string
  senderName?: string
  rawText: string
  intent: InboxIntent
  confidence: number
  parsedPayload: Record<string, unknown>
  status: InboxStatus
  targetModule?: string
  targetId?: number
  createdAt: string
  updatedAt: string
}
