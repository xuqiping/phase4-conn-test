export type OfficeTaskStatus =
  | 'draft'
  | 'preflight'
  | 'awaitingConfirmation'
  | 'queued'
  | 'running'
  | 'partialSuccess'
  | 'succeeded'
  | 'failed'
  | 'cancelled'

export type OfficeEngine = 'ooxmlWorker' | 'windowsOfficeWorker'

export type OfficeOutputPolicy = 'singleAtomic' | 'multipleIndependent'

export const OFFICE_JS_SAFE_INTEGER_MAX = Number.MAX_SAFE_INTEGER

export type OfficeTaskType =
  | 'excelSplit'
  | 'excelMerge'
  | 'wordBatchReplace'
  | 'powerPointMerge'
  | 'powerPointRelink'

export type OfficeSourceAccess = 'readOnly'

export type OfficeInputStatus = 'pending' | 'scanned' | 'ready' | 'failed'

export type OfficeOutputStatus = 'planned' | 'validating' | 'published' | 'failed'

export type OfficeIssueScope = 'task' | 'input' | 'output'

export type OfficeIssueSeverity = 'info' | 'warning' | 'error' | 'blocking'

export interface OfficeTaskInput {
  inputId: string
  /** Local-only path. Never include it in service requests or user-facing error text. */
  path: string
  format: string
  /** Must not exceed Number.MAX_SAFE_INTEGER. */
  sizeBytes: number
  sourceAccess: OfficeSourceAccess
}

export interface OfficeTaskOutput {
  outputId: string
  inputId?: string
  status: OfficeOutputStatus
}

export interface OfficeTaskIssue {
  issueId: string
  scope: OfficeIssueScope
  severity: OfficeIssueSeverity
  code: string
  messageKey: string
  /** Structured metadata only; never store body text, passwords, tokens, or model keys. */
  detailsJson: Record<string, unknown>
  resolved: boolean
}

export interface OfficeTask {
  /** UUID string. */
  taskId: string
  /** UUID string used for idempotency/tracing. */
  requestId?: string
  taskType: OfficeTaskType
  status: OfficeTaskStatus
  engine?: OfficeEngine
  outputPolicy: OfficeOutputPolicy
  inputs: OfficeTaskInput[]
  outputs: OfficeTaskOutput[]
  issues: OfficeTaskIssue[]
}

export interface OfficeOutputSummary {
  /** All counts must not exceed Number.MAX_SAFE_INTEGER. */
  expected: number
  published: number
  failed: number
}

export interface OfficeCreateTaskRequest {
  taskType: OfficeTaskType
  outputPolicy: OfficeOutputPolicy
  inputPaths: string[]
  outputDirectory: string
}

export interface OfficePreflightInput {
  inputId: string
  path: string
  format: string
  sizeBytes: number
  risks: string[]
}

export interface OfficePreflightIssue {
  issueId: string
  severity: OfficeIssueSeverity
  code: string
  messageKey: string
  inputId?: string
}

export interface OfficePreflightResponse {
  taskId: string
  status: OfficeTaskStatus
  engine: OfficeEngine
  outputPolicy: OfficeOutputPolicy
  outputDirectory: string
  inputs: OfficePreflightInput[]
  issues: OfficePreflightIssue[]
  withinFreeQuota: boolean
  canConfirm: boolean
}

export interface OfficeTaskSummary {
  taskId: string
  taskType: OfficeTaskType
  status: OfficeTaskStatus
  engine?: OfficeEngine
  outputPolicy: OfficeOutputPolicy
  inputCount: number
  totalBytes: number
  outputDir?: string
  createdAt: number
  startedAt?: number
  finishedAt?: number
}

export interface OfficeTaskPage {
  items: OfficeTaskSummary[]
  total: number
  page: number
  pageSize: number
}

export interface OfficeCredentialReference {
  bindingId: string
}
