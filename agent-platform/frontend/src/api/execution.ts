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

export const executionApi = {
  getSteps(executionId: number) {
    return request.get<ApiResponse<ExecutionStepLog[]>>(`/executions/${executionId}/steps`)
  }
}
