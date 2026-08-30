import type { ExecutionLog } from '@/api/execution'

export interface ExecutionFilterState {
  status: string
  sourceType: string
  keyword: string
}

export interface ExecutionPaginationState {
  page: number
  pageSize: number
}

function normalized(value: unknown) {
  return String(value ?? '').toLowerCase()
}

export function filterExecutions(executions: ExecutionLog[], filters: ExecutionFilterState) {
  const keyword = filters.keyword.trim().toLowerCase()
  return executions.filter(item => {
    const statusMatched = filters.status === 'ALL' || item.status === filters.status
    const sourceMatched = filters.sourceType === 'ALL' || (item.sourceType || 'WORKFLOW') === filters.sourceType
    const keywordMatched = !keyword || [
      item.id,
      item.workflowName,
      item.triggeredBy,
      item.triggeredByUsername,
      item.sourceType,
      item.traceId
    ].some(value => normalized(value).includes(keyword))
    return statusMatched && sourceMatched && keywordMatched
  })
}

export function paginateExecutions(executions: ExecutionLog[], pagination: ExecutionPaginationState) {
  const pageSize = Math.max(1, pagination.pageSize)
  const page = Math.max(1, pagination.page)
  const start = (page - 1) * pageSize
  return executions.slice(start, start + pageSize)
}
