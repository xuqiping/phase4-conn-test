export interface AiConfig {
  id: number
  name: string
  provider: 'qwen' | 'doubao' | 'claude' | 'custom'
  model: string
  endpoint?: string
  maxTokens: number
  timeoutSeconds: number
  isDefault: boolean
  enabled: boolean
}

export interface AiConfigForm {
  name: string
  provider: 'qwen' | 'doubao' | 'claude' | 'custom'
  model: string
  apiKey?: string
  endpoint?: string
  maxTokens: number
  timeoutSeconds: number
  isDefault: boolean
  enabled: boolean
}
