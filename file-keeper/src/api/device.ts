import { Store } from '@tauri-apps/plugin-store'

export interface DeviceIdentity {
  deviceId: string
  fingerprintHash: string
  deviceName: string
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

interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

export class DeviceApiError extends Error {
  constructor(message: string, public readonly status: number, public readonly code: number) {
    super(message)
    this.name = 'DeviceApiError'
    Object.setPrototypeOf(this, DeviceApiError.prototype)
  }
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

export async function registerClientDevice(
  baseUrl: string,
  accessToken: string,
  identity: DeviceIdentity
): Promise<ClientDevice> {
  const response = await fetch(`${normalizeBaseUrl(baseUrl)}/api/client/devices/register`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${accessToken}`
    },
    body: JSON.stringify(identity)
  })
  const payload = await response.json() as ApiResponse<ClientDevice>
  if (!response.ok || payload.code !== 200) {
    throw new DeviceApiError(payload.msg || `请求失败：${response.status}`, response.status, payload.code)
  }
  return payload.data
}

function generateDeviceIdentity(deviceName: string): DeviceIdentity {
  return {
    deviceId: `device-${crypto.randomUUID()}`,
    fingerprintHash: computeFingerprintHash(),
    deviceName
  }
}

function computeFingerprintHash(): string {
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
  for (let index = 0; index < raw.length; index += 1) {
    hash = ((hash << 5) - hash) + raw.charCodeAt(index)
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
    // localStorage 仅作为设备身份备份，不可用时主存储仍可正常工作。
  }
}

function normalizeBaseUrl(baseUrl: string): string {
  return baseUrl.replace(/\/+$/, '')
}

function defaultDeviceName(): string {
  const raw = globalThis.navigator?.userAgent || 'File Keeper Desktop'
  return raw.length > 250 ? raw.slice(0, 250) : raw
}
