import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Store } from '@tauri-apps/plugin-store'
import {
  CommercialAuthApiError,
  getOrCreateDeviceIdentity,
  startAnonymousTrial,
  getAnonymousAuthorization,
  registerClientDevice,
  getClientAuthorization,
  type DeviceIdentity
} from '../commercialAuth'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  set: vi.fn(),
  save: vi.fn(),
  fetch: vi.fn(),
  randomUUID: vi.fn()
}))

const { mockStoreLoad } = vi.hoisted(() => ({
  mockStoreLoad: vi.fn()
}))

vi.mock('@tauri-apps/plugin-store', () => ({
  Store: {
    load: mockStoreLoad
  }
}))

describe('commercialAuth API', () => {
  beforeEach(() => {
    mocks.get.mockReset()
    mocks.set.mockReset()
    mocks.save.mockReset()
    mocks.fetch.mockReset()
    mocks.randomUUID.mockReset()
    mockStoreLoad.mockReset()
    mockStoreLoad.mockResolvedValue({
      get: mocks.get,
      set: mocks.set,
      save: mocks.save
    })
    vi.stubGlobal('fetch', mocks.fetch)
    vi.spyOn(crypto, 'randomUUID').mockImplementation(mocks.randomUUID)
    mocks.randomUUID.mockReturnValue('uuid-001')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('loads existing device identity from local store', async () => {
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

  it('creates and persists device identity when none exists', async () => {
    mocks.get.mockResolvedValueOnce(undefined)

    const result = await getOrCreateDeviceIdentity('Desktop')

    expect(result).toEqual({
      deviceId: 'device-uuid-001',
      fingerprintHash: 'fingerprint-uuid-001',
      deviceName: 'Desktop'
    })
    expect(mocks.set).toHaveBeenCalledWith('deviceIdentity', result)
    expect(mocks.save).toHaveBeenCalled()
  })

  it('starts anonymous trial with device identity', async () => {
    const identity: DeviceIdentity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({
        code: 200,
        data: { deviceId: 'device-001', inFullTrial: true, allowedModuleCodes: ['files', 'processes', 'clipboard'] }
      })
    })

    const result = await startAnonymousTrial('http://localhost:8080', identity)

    expect(mocks.fetch).toHaveBeenCalledWith('http://localhost:8080/api/anonymous/trial/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(identity)
    })
    expect(result.inFullTrial).toBe(true)
    expect(result.allowedModuleCodes).toEqual(['files', 'processes', 'clipboard'])
  })

  it('loads anonymous authorization snapshot with encoded query params', async () => {
    const identity: DeviceIdentity = {
      deviceId: 'device 001',
      fingerprintHash: 'fingerprint/001',
      deviceName: 'Laptop'
    }
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({
        code: 200,
        data: {
          mode: 'anonymous',
          onlineRequired: true,
          deviceId: 'device 001',
          modules: [
            { moduleCode: 'files', allowed: true, reason: null, expiresAt: null },
            { moduleCode: 'processes', allowed: false, reason: '非当前免费模块', expiresAt: null },
            { moduleCode: 'clipboard', allowed: false, reason: '非当前免费模块', expiresAt: null }
          ]
        }
      })
    })

    const result = await getAnonymousAuthorization('http://localhost:8080/', identity)

    expect(mocks.fetch).toHaveBeenCalledWith('http://localhost:8080/api/anonymous/authorization?deviceId=device+001&fingerprintHash=fingerprint%2F001')
    expect(result.modules[0]).toEqual({ moduleCode: 'files', allowed: true, reason: null, expiresAt: null })
  })

  it('registers authenticated user device with bearer token', async () => {
    const identity: DeviceIdentity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({ code: 200, data: { id: 1, userId: 10, ...identity, status: 'active', lastSeenAt: null } })
    })

    const result = await registerClientDevice('http://localhost:8080', 'access-token', identity)

    expect(mocks.fetch).toHaveBeenCalledWith('http://localhost:8080/api/client/devices/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer access-token' },
      body: JSON.stringify(identity)
    })
    expect(result.status).toBe('active')
  })

  it('loads authenticated authorization snapshot with bearer token', async () => {
    const identity: DeviceIdentity = {
      deviceId: 'device 001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({
        code: 200,
        data: {
          mode: 'authenticated',
          userId: 10,
          accountStatus: 'active',
          deviceLimit: 2,
          onlineRequired: false,
          offlineUsableUntil: '2099-01-01T00:00:00Z',
          deviceBinding: { deviceId: 'device 001', bound: true, active: true },
          modules: [
            { moduleCode: 'files', allowed: true, reason: null, expiresAt: null },
            { moduleCode: 'processes', allowed: true, reason: null, expiresAt: null },
            { moduleCode: 'clipboard', allowed: false, reason: '模块未授权或已过期', expiresAt: null }
          ]
        }
      })
    })

    const result = await getClientAuthorization('http://localhost:8080/', 'access-token', identity)

    expect(mocks.fetch).toHaveBeenCalledWith('http://localhost:8080/api/client/authorization?deviceId=device+001', {
      headers: { Authorization: 'Bearer access-token' }
    })
    expect(result.mode).toBe('authenticated')
    expect(result.modules[2].allowed).toBe(false)
  })

  it('throws structured API error when API response code is not success', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 403, msg: '设备指纹不匹配', data: null })
    })

    await expect(startAnonymousTrial('http://localhost:8080', {
      deviceId: 'device-001',
      fingerprintHash: 'bad',
      deviceName: 'Laptop'
    })).rejects.toMatchObject({
      message: '设备指纹不匹配',
      status: 200,
      code: 403
    })
  })

  it('throws structured API error with HTTP status when response is not ok', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: false,
      status: 409,
      json: () => Promise.resolve({ code: 1009, msg: '设备数量超限', data: null })
    })

    const result = startAnonymousTrial('http://localhost:8080', {
      deviceId: 'device-001',
      fingerprintHash: 'bad',
      deviceName: 'Laptop'
    })

    await expect(result).rejects.toBeInstanceOf(CommercialAuthApiError)
    await expect(result).rejects.toMatchObject({
      status: 409,
      code: 1009
    })
  })

  it('does not wrap fetch network failures as API errors', async () => {
    const networkError = new TypeError('Failed to fetch')
    mocks.fetch.mockRejectedValueOnce(networkError)

    await expect(startAnonymousTrial('http://localhost:8080', {
      deviceId: 'device-001',
      fingerprintHash: 'bad',
      deviceName: 'Laptop'
    })).rejects.toBe(networkError)
  })
})
