import { describe, expect, it } from 'vitest'
import { filterExecutions, paginateExecutions } from './executionFilters'
import type { ExecutionLog } from '@/api/execution'

function execution(overrides: Partial<ExecutionLog>): ExecutionLog {
  return {
    id: 1,
    workflowId: 1,
    workflowName: '默认流程',
    triggeredBy: 1,
    triggeredByUsername: 'admin',
    sourceType: 'WORKFLOW',
    sourceId: 1,
    traceId: 'trace-1',
    status: 'SUCCESS',
    nodeLogs: null,
    checkpointRef: null,
    errorMessage: null,
    ...overrides
  }
}

describe('executionFilters', () => {
  it('filters executions by status, source type and keyword', () => {
    const executions = [
      execution({ id: 1, workflowName: '日报流程', triggeredByUsername: 'alice', status: 'SUCCESS', sourceType: 'WORKFLOW' }),
      execution({ id: 2, workflowName: '客服流程', triggeredByUsername: 'bob', status: 'FAILED', sourceType: 'AGENT' }),
      execution({ id: 3, workflowName: '日报复核', triggeredByUsername: 'alice', status: 'FAILED', sourceType: 'WORKFLOW' })
    ]

    const result = filterExecutions(executions, {
      status: 'FAILED',
      sourceType: 'WORKFLOW',
      keyword: 'alice'
    })

    expect(result.map(item => item.id)).toEqual([3])
  })

  it('paginates executions with the requested page size', () => {
    const executions = Array.from({ length: 12 }, (_, index) => execution({ id: index + 1 }))

    const result = paginateExecutions(executions, { page: 2, pageSize: 5 })

    expect(result.map(item => item.id)).toEqual([6, 7, 8, 9, 10])
  })
})
