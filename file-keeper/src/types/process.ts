// Process category types
export type ProcessCategory =
  | 'All'
  | 'Browser'
  | 'Office'
  | 'Explorer'
  | 'Terminal'
  | 'Archive'
  | 'Document'
  | 'Media'
  | 'Image'
  | 'Communication'
  | 'Download'
  | 'Game'
  | 'System'
  | 'Other'

// Process information from backend
export interface ProcessInfo {
  pid: number
  name: string
  window_title: string
  category: ProcessCategory
  memory_mb: number
  cpu_usage: number
  window_handle: number
}

// Column configuration
export interface ColumnConfig {
  key: string
  label: string
  width: string
  visible: boolean
  sortable: boolean
}

// Confirmation mode for closing processes
export type ConfirmMode = 'always' | 'whitelist' | 'never'

// Process settings
export interface ProcessSettings {
  columns: ColumnConfig[]
  autoRefresh: boolean
  refreshInterval: number
  confirmMode: ConfirmMode
  whitelist: string[]
}

// Close result from backend
export interface CloseResult {
  succeeded: number
  failed: number
}
