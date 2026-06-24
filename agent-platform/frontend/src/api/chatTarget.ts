import request from './request'
import type { ApiResponse } from './request'

export type ChatTargetType = 'NONE' | 'AGENT' | 'WORKFLOW'

export interface ChatTarget {
  type: ChatTargetType
  targetKey: string
  id: number | null
  name: string
  description: string | null
  available: boolean
  disabledReason?: string | null
  metadata?: Record<string, unknown> | null
}

export const chatTargetApi = {
  listTargets() {
    return request.get<ApiResponse<ChatTarget[]>>('/chat/targets')
  }
}
