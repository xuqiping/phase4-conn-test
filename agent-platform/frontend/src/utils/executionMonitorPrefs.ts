import { getStorage, setStorage } from '@/utils/storage'

export type ExecutionPageSizeMode = 5 | 10 | 20 | 50 | 100 | 'CUSTOM'

export interface ExecutionMonitorPrefs {
  keyword: string
  status: string
  sourceType: string
  page: number
  pageSizeMode: ExecutionPageSizeMode
  customPageSize: number
}

export const EXECUTION_MONITOR_PREFS_KEY = 'execution_monitor_prefs'

export const DEFAULT_EXECUTION_MONITOR_PREFS: ExecutionMonitorPrefs = {
  keyword: '',
  status: 'ALL',
  sourceType: 'ALL',
  page: 1,
  pageSizeMode: 10,
  customPageSize: 10
}

const PAGE_SIZE_MODES: ExecutionPageSizeMode[] = [5, 10, 20, 50, 100, 'CUSTOM']

function positiveInt(value: unknown, fallback: number) {
  const parsed = Number(value)
  if (!Number.isInteger(parsed) || parsed < 1) {
    return fallback
  }
  return parsed
}

function pageSizeMode(value: unknown): ExecutionPageSizeMode {
  return PAGE_SIZE_MODES.includes(value as ExecutionPageSizeMode)
    ? value as ExecutionPageSizeMode
    : DEFAULT_EXECUTION_MONITOR_PREFS.pageSizeMode
}

export function loadExecutionMonitorPrefs(): ExecutionMonitorPrefs {
  const stored = getStorage<Partial<ExecutionMonitorPrefs>>(EXECUTION_MONITOR_PREFS_KEY)
  if (!stored || typeof stored !== 'object') {
    return { ...DEFAULT_EXECUTION_MONITOR_PREFS }
  }
  return {
    keyword: typeof stored.keyword === 'string' ? stored.keyword : DEFAULT_EXECUTION_MONITOR_PREFS.keyword,
    status: typeof stored.status === 'string' && stored.status ? stored.status : DEFAULT_EXECUTION_MONITOR_PREFS.status,
    sourceType: typeof stored.sourceType === 'string' && stored.sourceType ? stored.sourceType : DEFAULT_EXECUTION_MONITOR_PREFS.sourceType,
    page: positiveInt(stored.page, DEFAULT_EXECUTION_MONITOR_PREFS.page),
    pageSizeMode: pageSizeMode(stored.pageSizeMode),
    customPageSize: positiveInt(stored.customPageSize, DEFAULT_EXECUTION_MONITOR_PREFS.customPageSize)
  }
}

export function saveExecutionMonitorPrefs(prefs: ExecutionMonitorPrefs) {
  setStorage(EXECUTION_MONITOR_PREFS_KEY, prefs)
}
