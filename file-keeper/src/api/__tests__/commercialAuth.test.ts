import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DeviceIdentity } from '../commercialAuth'

let Store: typeof import('@tauri-apps/plugin-store').Store
let CommercialAuthApiError: typeof import('../commercialAuth').CommercialAuthApiError
let getOrCreateDeviceIdentity: typeof import('../commercialAuth').getOrCreateDeviceIdentity
let startAnonymousTrial: typeof import('../commercialAuth').startAnonymousTrial
let getAnonymousAuthorization: typeof import('../commercialAuth').getAnonymousAuthorization
let registerClientDevice: typeof import('../commercialAuth').registerClientDevice
let getClientAuthorization: typeof import('../commercialAuth').getClientAuthorization
let selectFreeModule: typeof import('../commercialAuth').selectFreeModule
let changeFreeModule: typeof import('../commercialAuth').changeFreeModule

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  set: vi.fn(),
  save: vi.fn(),
  fetch: vi.fn(),
  randomUUID: vi.fn(),
  storeLoad: vi.fn()
}))

vi.mock('@tauri-apps/plugin-store', () => ({
  Store: {
    load: (...args: unknown[]) => mocks.storeLoad(...args)
  }
}))

describe('commercialAuth API', () => {
  beforeEach(async () => {
    vi.resetModules()
    mocks.get.mockReset()
    mocks.set.mockReset()
    mocks.save.mockReset()
    mocks.fetch.mockReset()
    mocks.randomUUID.mockReset()
    mocks.storeLoad.mockReset()
    mocks.storeLoad.mockResolvedValue({
      get: mocks.get,
      set: mocks.set,
      save: mocks.save
    })
    vi.stubGlobal('fetch', mocks.fetch)
    vi.stubGlobal('localStorage', { getItem: vi.fn(), setItem: vi.fn(), removeItem: vi.fn() })
    vi.spyOn(crypto, 'randomUUID').mockImplementation(mocks.randomUUID)
    mocks.randomUUID.mockReturnValue('uuid-001')

    Store = (await import('@tauri-apps/plugin-store')).Store
    vi.spyOn(Store, 'load').mockImplementation(mocks.storeLoad as typeof Store.load)
    const commercialAuth = await import('../commercialAuth')
    CommercialAuthApiError = commercialAuth.CommercialAuthApiError
    getOrCreateDeviceIdentity = commercialAuth.getOrCreateDeviceIdentity
    startAnonymousTrial = commercialAuth.startAnonymousTrial
    getAnonymousAuthorization = commercialAuth.getAnonymousAuthorization
    registerClientDevice = commercialAuth.registerClientDevice
    getClientAuthorization = commercialAuth.getClientAuthorization
    selectFreeModule = commercialAuth.selectFreeModule
    changeFreeModule = commercialAuth.changeFreeModule
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

    expect(result.deviceId).toBe('device-uuid-001')
    expect(result.deviceName).toBe('Desktop')
    expect(result.fingerprintHash).toBeTruthy()
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

  it('selects an anonymous free module with device identity', async () => {
    const identity: DeviceIdentity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({
        code: 200,
        data: {
          deviceId: 'device-001',
          inFullTrial: false,
          trialExpired: true,
          freeModuleCode: 'files',
          allowedModuleCodes: ['files']
        }
      })
    })

    const result = await selectFreeModule('http://localhost:8080/', identity, 'files')

    expect(mocks.fetch).toHaveBeenCalledWith('http://localhost:8080/api/anonymous/trial/select-free-module', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        deviceId: 'device-001',
        fingerprintHash: 'fingerprint-001',
        freeModuleCode: 'files'
      })
    })
    expect(result.freeModuleCode).toBe('files')
  })

  it('changes an anonymous free module with device identity', async () => {
    const identity: DeviceIdentity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({
        code: 200,
        data: {
          deviceId: 'device-001',
          inFullTrial: false,
          trialExpired: true,
          freeModuleCode: 'clipboard',
          allowedModuleCodes: ['clipboard']
        }
      })
    })

    const result = await changeFreeModule('http://localhost:8080/', identity, 'clipboard')

    expect(mocks.fetch).toHaveBeenCalledWith('http://localhost:8080/api/anonymous/trial/change-free-module', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        deviceId: 'device-001',
        fingerprintHash: 'fingerprint-001',
        freeModuleCode: 'clipboard'
      })
    })
    expect(result.freeModuleCode).toBe('clipboard')
  })

  it('surfaces backend restriction message when changing free module too soon', async () => {
    const identity: DeviceIdentity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    mocks.fetch.mockResolvedValueOnce({
      ok: false,
      status: 409,
      json: () => Promise.resolve({ code: 1009, msg: '免费模块每 30 天只能更换一次', data: null })
    })

    await expect(changeFreeModule('http://localhost:8080', identity, 'processes')).rejects.toMatchObject({
      message: '免费模块每 30 天只能更换一次',
      status: 409,
      code: 1009
    })
  })
})
