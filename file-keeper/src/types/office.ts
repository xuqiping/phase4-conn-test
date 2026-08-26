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

export type OfficeTaskType =
  | 'excelSplit'
  | 'excelMerge'
  | 'wordBatchReplace'
  | 'powerPointMerge'
  | 'powerPointRelink'

export type OfficeSourceAccess = 'readOnly'

export interface OfficeTaskInput {
  inputId: string
  /** Local-only path. Never include it in service requests or user-facing error text. */
  path: string
  format: string
  sizeBytes: number
  sourceAccess: OfficeSourceAccess
}

export interface OfficeTaskOutput {
  outputId: string
  inputId?: string
  status: string
}

export interface OfficeTaskIssue {
  issueId: string
  scope: string
  severity: string
  code: string
  messageKey: string
  /** Structured metadata only; never store body text, passwords, tokens, or model keys. */
  detailsJson: Record<string, unknown>
  resolved: boolean
}

export interface OfficeTask {
  taskId: string
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
  expected: number
  published: number
  failed: number
}
