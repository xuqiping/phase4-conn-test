import type { SkillStepSaveRequest } from '@/api/agent'

export type SkillInputParamForm = {
  key: string
  label: string
  description: string
  type: string
  required: boolean
  defaultValue: string
}

export type LlmStepConfigForm = {
  systemPrompt: string
  promptTemplate: string
  model: string
  temperature: number
  outputKey: string
}

export type SkillStepForm = SkillStepSaveRequest & {
  configForm: LlmStepConfigForm
}

export const STEP_ACTION_OPTIONS = [
  {
    label: 'LLM 调用',
    value: 'LLM_CALL',
    description: '调用大模型生成内容，支持系统提示词、用户提示词、模型、温度和输出变量。'
  }
]

export const DEFAULT_LLM_STEP_CONFIG: LlmStepConfigForm = {
  systemPrompt: '',
  promptTemplate: '',
  model: '',
  temperature: 0.7,
  outputKey: 'output'
}

export function parseSkillInputParams(config?: string | null): SkillInputParamForm[] {
  const parsed = parseJsonObject(config)
  const inputParams = Array.isArray(parsed.inputParams) ? parsed.inputParams : []
  return inputParams.map(param => ({
    key: String(param?.key || ''),
    label: String(param?.label || ''),
    description: String(param?.description || ''),
    type: String(param?.type || 'text'),
    required: Boolean(param?.required),
    defaultValue: String(param?.defaultValue || '')
  }))
}

export function buildSkillConfig(inputParams: SkillInputParamForm[]) {
  const cleaned = inputParams
    .map(param => ({
      key: param.key.trim(),
      label: param.label.trim(),
      description: param.description.trim(),
      type: param.type || 'text',
      required: param.required,
      defaultValue: param.defaultValue
    }))
    .filter(param => param.key)
    .map(param => removeEmptyValues(param))
  return JSON.stringify({ inputParams: cleaned })
}

export function parseLlmStepConfig(config?: string | null): LlmStepConfigForm {
  const parsed = parseJsonObject(config)
  return {
    systemPrompt: String(parsed.systemPrompt || ''),
    promptTemplate: String(parsed.promptTemplate || ''),
    model: String(parsed.model || DEFAULT_LLM_STEP_CONFIG.model),
    temperature: numberOrDefault(parsed.temperature, DEFAULT_LLM_STEP_CONFIG.temperature),
    outputKey: String(parsed.outputKey || DEFAULT_LLM_STEP_CONFIG.outputKey)
  }
}

export function buildStepConfig(config: LlmStepConfigForm) {
  return JSON.stringify({
    systemPrompt: config.systemPrompt || '',
    promptTemplate: config.promptTemplate || '',
    model: config.model || '',
    temperature: config.temperature ?? DEFAULT_LLM_STEP_CONFIG.temperature,
    outputKey: config.outputKey || DEFAULT_LLM_STEP_CONFIG.outputKey
  })
}

function parseJsonObject(config?: string | null): Record<string, any> {
  if (!config) return {}
  try {
    const parsed = JSON.parse(config)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

function numberOrDefault(value: unknown, defaultValue: number) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : defaultValue
}

function removeEmptyValues(value: Record<string, unknown>) {
  return Object.fromEntries(
    Object.entries(value).filter(([, item]) => item !== undefined && item !== null && item !== '')
  )
}
