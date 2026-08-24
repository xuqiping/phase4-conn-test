import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Store } from '@tauri-apps/plugin-store'
import {
  DeviceApiError,
  getOrCreateDeviceIdentity,
  registerClientDevice,
  type DeviceIdentity
} from '../device'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  set: vi.fn(),
  save: vi.fn(),
  storeLoad: vi.fn(),
  fetch: vi.fn(),
  randomUUID: vi.fn()
}))

vi.mock('@tauri-apps/plugin-store', () => ({
  Store: {
    load: mocks.storeLoad
  }
}))

describe('device API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mocks.storeLoad.mockResolvedValue({
      get: mocks.get,
      set: mocks.set,
      save: mocks.save
    })
    vi.stubGlobal('fetch', mocks.fetch)
    vi.spyOn(crypto, 'randomUUID').mockImplementation(mocks.randomUUID)
    mocks.randomUUID.mockReturnValue('uuid-001')
  })

  it('returns the persisted device identity without creating a replacement', async () => {
    const identity: DeviceIdentity = {
      deviceId: 'device-existing',
      fingerprintHash: 'fingerprint-existing',
      deviceName: 'My PC'
    }
    mocks.get.mockResolvedValueOnce(identity)

    const result = await getOrCreateDeviceIdentity()

    expect(result).toEqual(identity)
    expect(Store.load).toHaveBeenCalledWith('file-keeper-auth.json', {
      defaults: {},
      autoSave: 500
    })
    expect(mocks.set).not.toHaveBeenCalled()
  })

  it('creates and persists a stable device identity when none exists', async () => {
    mocks.get.mockResolvedValueOnce(undefined)

    const result = await getOrCreateDeviceIdentity('Desktop')

    expect(result).toEqual(expect.objectContaining({
      deviceId: 'device-uuid-001',
      deviceName: 'Desktop'
    }))
    expect(result.fingerprintHash).toMatch(/^fp-/)
    expect(mocks.set).toHaveBeenCalledWith('deviceIdentity', result)
    expect(mocks.save).toHaveBeenCalledOnce()
  })

  it('registers the device with JWT authentication and preserves disabled-device errors', async () => {
    const identity: DeviceIdentity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    mocks.fetch.mockResolvedValueOnce({
      ok: false,
      status: 403,
      json: vi.fn().mockResolvedValue({ code: 403, msg: '设备已禁用', data: null })
    } as unknown as Response)

    await expect(registerClientDevice('http://localhost:8080/', 'access-token', identity))
      .rejects.toEqual(expect.objectContaining<DeviceApiError>({
        name: 'DeviceApiError',
        message: '设备已禁用',
        status: 403,
        code: 403
      }))

    expect(mocks.fetch).toHaveBeenCalledWith('http://localhost:8080/api/client/devices/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer access-token' },
      body: JSON.stringify(identity)
    })
  })
})
