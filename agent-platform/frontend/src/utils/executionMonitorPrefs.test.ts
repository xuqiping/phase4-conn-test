import { beforeEach, describe, expect, it } from 'vitest'
import {
  DEFAULT_EXECUTION_MONITOR_PREFS,
  loadExecutionMonitorPrefs,
  saveExecutionMonitorPrefs
} from './executionMonitorPrefs'

describe('executionMonitorPrefs', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('persists execution monitor filters and page size preferences', () => {
    saveExecutionMonitorPrefs({
      keyword: 'alice',
      status: 'FAILED',
      sourceType: 'WORKFLOW',
      page: 3,
      pageSizeMode: 'CUSTOM',
      customPageSize: 7
    })

    expect(loadExecutionMonitorPrefs()).toEqual({
      keyword: 'alice',
      status: 'FAILED',
      sourceType: 'WORKFLOW',
      page: 3,
      pageSizeMode: 'CUSTOM',
      customPageSize: 7
    })
  })

  it('falls back to defaults when persisted data is invalid', () => {
    localStorage.setItem('execution_monitor_prefs', JSON.stringify({
      keyword: 123,
      status: '',
      sourceType: null,
      page: -1,
      pageSizeMode: 999,
      customPageSize: 0
    }))

    expect(loadExecutionMonitorPrefs()).toEqual(DEFAULT_EXECUTION_MONITOR_PREFS)
  })
})
