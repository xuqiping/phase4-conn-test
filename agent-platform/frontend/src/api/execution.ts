import request from './request'
import type { ApiResponse } from './request'

export interface ExecutionStepLog {
  id: number
  executionId: number
  nodeId: string | null
  skillId: number | null
  stepOrder: number | null
  stepName: string | null
  action: string | null
  inputData: string | null
  outputData: string | null
  status: string
  duration: number | null
  errorMessage: string | null
  createdAt: string
}

export interface ExecutionEvent {
  executionId: string
  rootExecutionId: string
  nodeId: string | null
  type: string
  status: string
  sourceType?: string
  sourceId?: number
  input?: Record<string, unknown>
  output?: Record<string, unknown>
  metadata: Record<string, unknown>
  timestamp?: string
}

export interface ExecutionRecoveryInfo {
  executionId: number
  status: string
  failedNodeId: string | null
  errorMessage: string | null
  checkpointRef: string | null
  recoverable: boolean
  recoverySuggestion: string
}

export interface ExecutionLog {
  id: number
  workflowId: number | null
  workflowName: string | null
  triggeredBy?: number | null
  triggeredByUsername?: string | null
  sourceType?: string | null
  sourceId?: number | null
  traceId?: string | null
  startedAt?: string | null
  completedAt?: string | null
  duration?: number | null
  status: string
  nodeLogs: string | null
  checkpointRef: string | null
  errorMessage: string | null
  nodeId?: string | null
}

export const executionApi = {
  listExecutions() {
    return request.get<ApiResponse<ExecutionLog[]>>('/executions')
  },

  getExecution(executionId: number) {
    return request.get<ApiResponse<ExecutionLog>>(`/executions/${executionId}`)
  },

  listPendingApprovals() {
    return request.get<ApiResponse<ExecutionLog[]>>('/executions/pending-approvals')
  },

  getSteps(executionId: number) {
    return request.get<ApiResponse<ExecutionStepLog[]>>(`/executions/${executionId}/steps`)
  },

  getRecoveryInfo(executionId: number) {
    return request.get<ApiResponse<ExecutionRecoveryInfo>>(`/executions/${executionId}/recovery`)
  },

  retry(executionId: number) {
    return request.post<ApiResponse<ExecutionEvent[]>>(`/executions/${executionId}/retry`)
  },

  resume(checkpointRef: string) {
    return request.post<ApiResponse<ExecutionEvent[]>>('/executions/resume', null, {
      params: { checkpointRef }
    })
  },

  approve(executionId: number) {
    return request.post<ApiResponse<ExecutionEvent[]>>(`/executions/${executionId}/approve`)
  },

  reject(executionId: number, reason: string) {
    return request.post<ApiResponse<void>>(`/executions/${executionId}/reject`, null, {
      params: { reason }
    })
  }
}
