export type ContactType = 'email' | 'phone'

export interface UserSummary {
  id: number
  email: string | null
  phone: string | null
  role: string
  status: string
  emailVerified: boolean
  phoneVerified: boolean
  deviceLimit: number
  offlineCacheMinutes: number
}

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  expiresInSeconds: number
  user: UserSummary
}

export interface SendVerificationRequest { contactType: ContactType; contact: string }
export interface CheckVerificationRequest { contactType: ContactType; contact: string; code: string }
export interface RegisterRequest { email?: string | null; phone?: string | null; password: string }

interface ApiResponse<T> {
  code: number
  msg?: string
  data: T
}

interface CheckVerificationResponse {
  verified: boolean
}

export class AuthApiError extends Error {
  constructor(message: string, public readonly status: number, public readonly code: number) {
    super(message)
    this.name = 'AuthApiError'
    Object.setPrototypeOf(this, AuthApiError.prototype)
  }
}

export async function sendVerificationCode(baseUrl: string, request: SendVerificationRequest): Promise<void> {
  await postJson<void>(`${normalizeBaseUrl(baseUrl)}/api/client/verification/send`, request)
}

export async function checkVerificationCode(baseUrl: string, request: CheckVerificationRequest): Promise<boolean> {
  const result = await postJson<CheckVerificationResponse>(`${normalizeBaseUrl(baseUrl)}/api/client/verification/check`, request)
  return result.verified
}

export async function register(baseUrl: string, request: RegisterRequest): Promise<UserSummary> {
  return postJson<UserSummary>(`${normalizeBaseUrl(baseUrl)}/api/client/auth/register`, request)
}

export async function login(baseUrl: string, identifier: string, password: string): Promise<AuthResponse> {
  return postJson<AuthResponse>(`${normalizeBaseUrl(baseUrl)}/api/client/auth/login`, { identifier, password })
}

export async function refresh(baseUrl: string, refreshToken: string): Promise<AuthResponse> {
  return postJson<AuthResponse>(`${normalizeBaseUrl(baseUrl)}/api/client/auth/refresh`, { refreshToken })
}

export async function logout(baseUrl: string, refreshToken: string): Promise<void> {
  await postJson<void>(`${normalizeBaseUrl(baseUrl)}/api/client/auth/logout`, { refreshToken })
}

async function postJson<T>(url: string, body: unknown): Promise<T> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  return readApiResponse<T>(response)
}

async function readApiResponse<T>(response: Response): Promise<T> {
  const payload = await response.json() as ApiResponse<T>
  if (!response.ok || payload.code !== 200) {
    throw new AuthApiError(payload.msg || `请求失败：${response.status}`, response.status, payload.code)
  }
  return payload.data
}

function normalizeBaseUrl(baseUrl: string): string {
  return baseUrl.replace(/\/+$/, '')
}
