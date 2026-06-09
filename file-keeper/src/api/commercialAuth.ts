import { Store } from '@tauri-apps/plugin-store'

export type ModuleCode = 'files' | 'processes' | 'clipboard'

export interface DeviceIdentity {
  deviceId: string
  fingerprintHash: string
  deviceName: string
}

export interface AnonymousTrialStatus {
  deviceId: string
  deviceName?: string
  trialStartedAt?: string
  trialExpiresAt?: string
  inFullTrial: boolean
  trialExpired: boolean
  freeModuleCode?: ModuleCode | null
  freeModuleSelectedAt?: string | null
  lastFreeModuleChangedAt?: string | null
  allowedModuleCodes: ModuleCode[]
}

export interface ModuleAccess {
  moduleCode: ModuleCode
  allowed: boolean
  reason: string | null
  expiresAt: string | null
}

export interface AnonymousAuthorizationSnapshot {
  mode: 'anonymous'
  onlineRequired: boolean
  deviceId: string
  modules: ModuleAccess[]
}

export interface ClientDevice {
  id: number
  userId: number
  deviceId: string
  fingerprintHash: string
  deviceName: string
  status: string
  lastSeenAt: string | null
}

export interface ClientDeviceBinding {
  deviceId: string
  bound: boolean
  active: boolean
}

export interface ClientAuthorizationSnapshot {
  mode: 'authenticated'
  userId: number
  accountStatus: string
  deviceLimit: number
  onlineRequired: boolean
  offlineUsableUntil?: string | null
  deviceBinding: ClientDeviceBinding
  modules: ModuleAccess[]
}

interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

export class CommercialAuthApiError extends Error {
  constructor(message: string, public readonly status: number, public readonly code: number) {
    super(message)
    this.name = 'CommercialAuthApiError'
    Object.setPrototypeOf(this, CommercialAuthApiError.prototype)
  }
}

export function isCommercialAuthApiError(error: unknown): error is CommercialAuthApiError {
  return error instanceof CommercialAuthApiError || (
    error instanceof Error &&
    error.name === 'CommercialAuthApiError' &&
    typeof (error as { status?: unknown }).status === 'number' &&
    typeof (error as { code?: unknown }).code === 'number'
  )
}

const AUTH_STORE_PATH = 'file-keeper-auth.json'
const DEVICE_IDENTITY_KEY = 'deviceIdentity'

export async function getOrCreateDeviceIdentity(deviceName = defaultDeviceName()): Promise<DeviceIdentity> {
  const store = await Store.load(AUTH_STORE_PATH, {
    defaults: {},
    autoSave: 500
  })
  const existing = await store.get<DeviceIdentity>(DEVICE_IDENTITY_KEY)
  if (existing) {
    return existing
  }
  const suffix = crypto.randomUUID()
  const identity: DeviceIdentity = {
    deviceId: `device-${suffix}`,
    fingerprintHash: `fingerprint-${suffix}`,
    deviceName
  }
  await store.set(DEVICE_IDENTITY_KEY, identity)
  await store.save()
  return identity
}

export async function startAnonymousTrial(baseUrl: string, identity: DeviceIdentity): Promise<AnonymousTrialStatus> {
  return postJson<AnonymousTrialStatus>(`${normalizeBaseUrl(baseUrl)}/api/anonymous/trial/start`, identity)
}

export async function getAnonymousAuthorization(baseUrl: string, identity: DeviceIdentity): Promise<AnonymousAuthorizationSnapshot> {
  const params = new URLSearchParams({
    deviceId: identity.deviceId,
    fingerprintHash: identity.fingerprintHash
  })
  return getJson<AnonymousAuthorizationSnapshot>(`${normalizeBaseUrl(baseUrl)}/api/anonymous/authorization?${params.toString()}`)
}

export async function registerClientDevice(
  baseUrl: string,
  accessToken: string,
  identity: DeviceIdentity
): Promise<ClientDevice> {
  return postJson<ClientDevice>(`${normalizeBaseUrl(baseUrl)}/api/client/devices/register`, identity, {
    Authorization: `Bearer ${accessToken}`
  })
}

export async function getClientAuthorization(
  baseUrl: string,
  accessToken: string,
  identity: DeviceIdentity
): Promise<ClientAuthorizationSnapshot> {
  const params = new URLSearchParams({
    deviceId: identity.deviceId
  })
  return getJson<ClientAuthorizationSnapshot>(`${normalizeBaseUrl(baseUrl)}/api/client/authorization?${params.toString()}`, {
    Authorization: `Bearer ${accessToken}`
  })
}

async function postJson<T>(url: string, body: unknown, headers: Record<string, string> = {}): Promise<T> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body)
  })
  return readApiResponse<T>(response)
}

async function getJson<T>(url: string, headers?: Record<string, string>): Promise<T> {
  const response = headers ? await fetch(url, { headers }) : await fetch(url)
  return readApiResponse<T>(response)
}

async function readApiResponse<T>(response: Response): Promise<T> {
  const payload = await response.json() as ApiResponse<T>
  if (!response.ok || payload.code !== 200) {
    throw new CommercialAuthApiError(payload.msg || `请求失败：${response.status}`, response.status, payload.code)
  }
  return payload.data
}

function normalizeBaseUrl(baseUrl: string): string {
  return baseUrl.replace(/\/+$/, '')
}

function defaultDeviceName(): string {
  return globalThis.navigator?.userAgent || 'File Keeper Desktop'
}
