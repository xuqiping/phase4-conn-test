import type { AiConfig, AiConfigForm } from '@/types/aiConfig'

const BASE_PATH = '/api/client/ai-configs'

interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

export class AiConfigApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code: number,
  ) {
    super(message)
    this.name = 'AiConfigApiError'
    Object.setPrototypeOf(this, AiConfigApiError.prototype)
  }
}

export function isAiConfigApiError(error: unknown): error is AiConfigApiError {
  return error instanceof AiConfigApiError || (
    error instanceof Error &&
    error.name === 'AiConfigApiError' &&
    typeof (error as { status?: unknown }).status === 'number' &&
    typeof (error as { code?: unknown }).code === 'number'
  )
}

function normalizeBaseUrl(baseUrl: string): string {
  return baseUrl.replace(/\/+$/, '')
}

function buildUrl(baseUrl: string, path: string, deviceId: string): string {
  const separator = path.includes('?') ? '&' : '?'
  return `${normalizeBaseUrl(baseUrl)}${BASE_PATH}${path}${separator}deviceId=${encodeURIComponent(deviceId)}`
}

async function readApiResponse<T>(response: Response): Promise<T> {
  const payload = (await response.json()) as ApiResponse<T>
  if (!response.ok || payload.code !== 200) {
    throw new AiConfigApiError(
      payload.msg || `请求失败：${response.status}`,
      response.status,
      payload.code,
    )
  }
  return payload.data
}

async function request<T>(
  baseUrl: string,
  token: string,
  deviceId: string,
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const response = await fetch(buildUrl(baseUrl, path, deviceId), {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...(options.headers || {}),
    },
  })
  return readApiResponse<T>(response)
}

export async function listAiConfigs(
  baseUrl: string,
  token: string,
  deviceId: string,
): Promise<AiConfig[]> {
  return request<AiConfig[]>(baseUrl, token, deviceId, '')
}

export async function getAiConfig(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
): Promise<AiConfig> {
  return request<AiConfig>(baseUrl, token, deviceId, `/${id}`)
}

export async function createAiConfig(
  baseUrl: string,
  token: string,
  deviceId: string,
  config: AiConfigForm,
): Promise<AiConfig> {
  return request<AiConfig>(baseUrl, token, deviceId, '', {
    method: 'POST',
    body: JSON.stringify(config),
  })
}

export async function updateAiConfig(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
  config: AiConfigForm,
): Promise<AiConfig> {
  return request<AiConfig>(baseUrl, token, deviceId, `/${id}`, {
    method: 'PUT',
    body: JSON.stringify(config),
  })
}

export async function deleteAiConfig(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
): Promise<void> {
  return request<void>(baseUrl, token, deviceId, `/${id}`, {
    method: 'DELETE',
  })
}

export async function setDefaultAiConfig(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
): Promise<AiConfig> {
  return request<AiConfig>(baseUrl, token, deviceId, `/${id}/default`, {
    method: 'PUT',
  })
}
