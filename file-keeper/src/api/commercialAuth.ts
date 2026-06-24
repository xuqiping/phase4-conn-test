import { Store } from '@tauri-apps/plugin-store'

export type ModuleCode = 'files' | 'processes' | 'clipboard' | 'work-report'

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
  offlineToken?: string | null
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
const DEVICE_IDENTITY_BACKUP_KEY = 'fk.deviceIdentity.backup'

export async function getOrCreateDeviceIdentity(deviceName = defaultDeviceName()): Promise<DeviceIdentity> {
  const store = await Store.load(AUTH_STORE_PATH, {
    defaults: {},
    autoSave: 500
  })
  const primary = await store.get<DeviceIdentity>(DEVICE_IDENTITY_KEY)
  const backup = readBackupIdentity()

  if (primary && backup) {
    // 两者都存在，校验一致性；若不一致以 primary 为准并刷新 backup。
    if (primary.deviceId !== backup.deviceId || primary.fingerprintHash !== backup.fingerprintHash) {
      writeBackupIdentity(primary)
    }
    return primary
  }

  if (primary && !backup) {
    writeBackupIdentity(primary)
    return primary
  }

  if (!primary && backup) {
    // 主存储被清除但备份还在，恢复并记录（未来可上报风控）。
    await store.set(DEVICE_IDENTITY_KEY, backup)
    await store.save()
    return backup
  }

  const identity = generateDeviceIdentity(deviceName)
  await store.set(DEVICE_IDENTITY_KEY, identity)
  await store.save()
  writeBackupIdentity(identity)
  return identity
}

function generateDeviceIdentity(deviceName: string): DeviceIdentity {
  const suffix = crypto.randomUUID()
  return {
    deviceId: `device-${suffix}`,
    fingerprintHash: computeFingerprintHash(),
    deviceName
  }
}

function computeFingerprintHash(): string {
  // 基于设备/浏览器环境特征生成指纹，增加伪造成本。
  const components = [
    globalThis.navigator?.userAgent ?? '',
    globalThis.navigator?.platform ?? '',
    String(globalThis.navigator?.hardwareConcurrency ?? ''),
    String((globalThis.navigator as { deviceMemory?: number }).deviceMemory ?? ''),
    String(globalThis.screen?.width ?? ''),
    String(globalThis.screen?.height ?? ''),
    String(globalThis.screen?.colorDepth ?? ''),
    String(globalThis.window?.devicePixelRatio ?? '')
  ]
  const raw = components.join('|')
  let hash = 0
  for (let i = 0; i < raw.length; i++) {
    const char = raw.charCodeAt(i)
    hash = ((hash << 5) - hash) + char
    hash |= 0
  }
  return `fp-${Math.abs(hash).toString(16)}`
}

function readBackupIdentity(): DeviceIdentity | null {
  try {
    const raw = globalThis.localStorage?.getItem(DEVICE_IDENTITY_BACKUP_KEY)
    return raw ? JSON.parse(raw) as DeviceIdentity : null
  } catch {
    return null
  }
}

function writeBackupIdentity(identity: DeviceIdentity): void {
  try {
    globalThis.localStorage?.setItem(DEVICE_IDENTITY_BACKUP_KEY, JSON.stringify(identity))
  } catch {
    // localStorage 不可用时不影响主流程。
  }
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

export async function selectFreeModule(
  baseUrl: string,
  identity: DeviceIdentity,
  freeModuleCode: ModuleCode
): Promise<AnonymousTrialStatus> {
  return postJson<AnonymousTrialStatus>(`${normalizeBaseUrl(baseUrl)}/api/anonymous/trial/select-free-module`, {
    deviceId: identity.deviceId,
    fingerprintHash: identity.fingerprintHash,
    freeModuleCode
  })
}

export async function changeFreeModule(
  baseUrl: string,
  identity: DeviceIdentity,
  freeModuleCode: ModuleCode
): Promise<AnonymousTrialStatus> {
  return postJson<AnonymousTrialStatus>(`${normalizeBaseUrl(baseUrl)}/api/anonymous/trial/change-free-module`, {
    deviceId: identity.deviceId,
    fingerprintHash: identity.fingerprintHash,
    freeModuleCode
  })
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
  identity: DeviceIdentity,
  clientTimestamp?: number
): Promise<ClientAuthorizationSnapshot> {
  const params = new URLSearchParams({
    deviceId: identity.deviceId
  })
  if (clientTimestamp !== undefined) {
    params.set('clientTimestamp', String(clientTimestamp))
  }
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
  const raw = globalThis.navigator?.userAgent || 'File Keeper Desktop'
  // 后端 device_name 已放宽到 VARCHAR(255)，截断到 250 防止边缘情况。
  return raw.length > 250 ? raw.slice(0, 250) : raw
}
