export interface TimelineItem {
  key: string
  type: string
  status: string
  nodeId: string | null
  checkpointRef: string | null
  selectedRoute: string | null
  selectedTarget: string | null
  failedNodeId: string | null
  errorMessage: string | null
  approvalKey: string | null
  inputPayload: Record<string, unknown> | null
  outputPayload: Record<string, unknown> | null
  outputText: string | null
  agentName: string | null
  selectedSkillIds: number[]
  stepOutputs: Array<Record<string, unknown>>
  timestamp: string | null
}

export function parseExecutionTimeline(nodeLogs: string | null | undefined): TimelineItem[] {
  if (!nodeLogs) return []
  try {
    const events = JSON.parse(nodeLogs)
    if (!Array.isArray(events)) return []
    return events.map((event, index) => toTimelineItem(event, index))
  } catch {
    return []
  }
}

function toTimelineItem(event: Record<string, unknown>, index: number): TimelineItem {
  const metadata = metadataOf(event)
  const input = inputOf(event)
  const output = outputOf(event)
  const type = stringValue(event.type) || 'UNKNOWN'
  const nodeId = stringValue(event.nodeId)
  return {
    key: `${index}-${type}-${nodeId || 'execution'}`,
    type,
    status: stringValue(event.status) || 'UNKNOWN',
    nodeId,
    checkpointRef: stringValue(metadata.checkpointRef) || stringValue(metadata.recoveryCheckpointRef),
    selectedRoute: stringValue(metadata.selectedRoute),
    selectedTarget: stringValue(metadata.selectedTarget),
    failedNodeId: stringValue(metadata.failedNodeId),
    errorMessage: stringValue(metadata.errorMessage),
    approvalKey: stringValue(metadata.approvalKey),
    inputPayload: isEmptyObject(input) ? null : input,
    outputPayload: isEmptyObject(output) ? null : output,
    outputText: stringValue(output.text),
    agentName: stringValue(output.agentName),
    selectedSkillIds: numberArray(output.selectedSkillIds),
    stepOutputs: objectArray(output.stepOutputs),
    timestamp: stringValue(event.timestamp)
  }
}

function metadataOf(event: Record<string, unknown>): Record<string, unknown> {
  return event.metadata && typeof event.metadata === 'object'
    ? event.metadata as Record<string, unknown>
    : {}
}

function inputOf(event: Record<string, unknown>): Record<string, unknown> {
  return event.input && typeof event.input === 'object'
    ? event.input as Record<string, unknown>
    : {}
}

function outputOf(event: Record<string, unknown>): Record<string, unknown> {
  return event.output && typeof event.output === 'object'
    ? event.output as Record<string, unknown>
    : {}
}

function isEmptyObject(value: Record<string, unknown>): boolean {
  return Object.keys(value).length === 0
}

function numberArray(value: unknown): number[] {
  if (!Array.isArray(value)) return []
  return value
    .map(item => Number(item))
    .filter(item => Number.isFinite(item))
}

function objectArray(value: unknown): Array<Record<string, unknown>> {
  if (!Array.isArray(value)) return []
  return value.filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
}

function stringValue(value: unknown): string | null {
  if (value === null || value === undefined || value === '') return null
  return String(value)
}
