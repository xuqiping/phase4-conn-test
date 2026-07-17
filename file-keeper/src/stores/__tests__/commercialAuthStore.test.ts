import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as commercialAuthApi from '@/api/commercialAuth'
import { useCommercialAuthStore } from '../commercialAuthStore'

const mockedApi = vi.mocked(commercialAuthApi)

const mockInvoke = vi.fn().mockImplementation((command: string) => {
  if (command === 'set_signed_entitlement') return Promise.resolve()
  if (command === 'clear_signed_entitlement') return Promise.resolve()
  if (command === 'check_signed_entitlement_access') {
    return Promise.resolve({ allowed: false, reason: '无凭据' })
  }
  return Promise.reject(new Error(`Unexpected invoke: ${command}`))
})

vi.mock('@tauri-apps/api/core', () => ({
  invoke: (...args: unknown[]) => mockInvoke(...args)
}))

describe('commercialAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
    mockInvoke.mockClear()
    vi.spyOn(commercialAuthApi, 'getOrCreateDeviceIdentity')
    vi.spyOn(commercialAuthApi, 'startAnonymousTrial')
    vi.spyOn(commercialAuthApi, 'getAnonymousAuthorization')
    vi.spyOn(commercialAuthApi, 'selectFreeModule')
    vi.spyOn(commercialAuthApi, 'changeFreeModule')
    vi.spyOn(commercialAuthApi, 'registerClientDevice')
    vi.spyOn(commercialAuthApi, 'getClientAuthorization')
  })

  it('initializes anonymous authorization and exposes module access', async () => {
    mockedApi.getOrCreateDeviceIdentity.mockResolvedValueOnce({
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    })
    mockedApi.startAnonymousTrial.mockResolvedValueOnce({
      deviceId: 'device-001',
      deviceName: 'Laptop',
      inFullTrial: true,
      trialExpired: false,
      freeModuleCode: null,
      allowedModuleCodes: ['files', 'processes', 'clipboard']
    })
    mockedApi.getAnonymousAuthorization.mockResolvedValueOnce({
      mode: 'anonymous',
      onlineRequired: true,
      deviceId: 'device-001',
      modules: [
        { moduleCode: 'files', allowed: true, reason: null, expiresAt: null },
        { moduleCode: 'processes', allowed: false, reason: '非当前免费模块', expiresAt: null },
        { moduleCode: 'clipboard', allowed: true, reason: null, expiresAt: null }
      ]
    })
    const store = useCommercialAuthStore()

    await store.initializeAnonymous('http://localhost:8080')

    expect(mockedApi.getOrCreateDeviceIdentity).toHaveBeenCalled()
    expect(mockedApi.startAnonymousTrial).toHaveBeenCalledWith('http://localhost:8080', {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    })
    expect(mockedApi.getAnonymousAuthorization).toHaveBeenCalledWith('http://localhost:8080', {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    })
    expect(store.deviceIdentity?.deviceId).toBe('device-001')
    expect(store.trialStatus?.inFullTrial).toBe(true)
    expect(store.isModuleAllowed('files')).toBe(true)
    expect(store.isModuleAllowed('processes')).toBe(false)
    expect(store.isModuleAllowed('clipboard')).toBe(true)
    expect(store.denialReason('processes')).toBe('非当前免费模块')
  })

  it('stores initialization errors and denies modules when authorization fails', async () => {
    mockedApi.getOrCreateDeviceIdentity.mockResolvedValueOnce({
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    })
    mockedApi.startAnonymousTrial.mockRejectedValueOnce(new Error('服务器不可用'))
    const store = useCommercialAuthStore()

    await expect(store.initializeAnonymous('http://localhost:8080')).rejects.toThrow('服务器不可用')

    expect(store.error).toBe('服务器不可用')
    expect(store.loading).toBe(false)
    expect(store.isModuleAllowed('files')).toBe(false)
  })

  it('selects a free module and refreshes anonymous authorization', async () => {
    const identity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    mockedApi.getOrCreateDeviceIdentity.mockResolvedValueOnce(identity)
    mockedApi.selectFreeModule.mockResolvedValueOnce({
      deviceId: 'device-001',
      deviceName: 'Laptop',
      inFullTrial: false,
      trialExpired: true,
      freeModuleCode: 'files',
      allowedModuleCodes: ['files']
    })
    mockedApi.getAnonymousAuthorization.mockResolvedValueOnce({
      mode: 'anonymous',
      onlineRequired: true,
      deviceId: 'device-001',
      modules: [
        { moduleCode: 'files', allowed: true, reason: null, expiresAt: null },
        { moduleCode: 'processes', allowed: false, reason: '非当前免费模块', expiresAt: null },
        { moduleCode: 'clipboard', allowed: false, reason: '非当前免费模块', expiresAt: null }
      ]
    })
    const store = useCommercialAuthStore()

    await store.selectFreeModule('http://localhost:8080', 'files')

    expect(mockedApi.selectFreeModule).toHaveBeenCalledWith('http://localhost:8080', identity, 'files')
    expect(mockedApi.getAnonymousAuthorization).toHaveBeenCalledWith('http://localhost:8080', identity)
    expect(store.trialStatus?.freeModuleCode).toBe('files')
    expect(store.anonymousAuthorization?.modules[0].allowed).toBe(true)
    expect(store.isModuleAllowed('files')).toBe(true)
    expect(store.isModuleAllowed('processes')).toBe(false)
    expect(store.error).toBeNull()
  })

  it('changes a free module using the existing device identity', async () => {
    const identity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    mockedApi.changeFreeModule.mockResolvedValueOnce({
      deviceId: 'device-001',
      deviceName: 'Laptop',
      inFullTrial: false,
      trialExpired: true,
      freeModuleCode: 'clipboard',
      allowedModuleCodes: ['clipboard']
    })
    mockedApi.getAnonymousAuthorization.mockResolvedValueOnce({
      mode: 'anonymous',
      onlineRequired: true,
      deviceId: 'device-001',
      modules: [
        { moduleCode: 'files', allowed: false, reason: '非当前免费模块', expiresAt: null },
        { moduleCode: 'processes', allowed: false, reason: '非当前免费模块', expiresAt: null },
        { moduleCode: 'clipboard', allowed: true, reason: null, expiresAt: null }
      ]
    })
    const store = useCommercialAuthStore()
    store.deviceIdentity = identity

    await store.changeFreeModule('http://localhost:8080', 'clipboard')

    expect(mockedApi.getOrCreateDeviceIdentity).not.toHaveBeenCalled()
    expect(mockedApi.changeFreeModule).toHaveBeenCalledWith('http://localhost:8080', identity, 'clipboard')
    expect(mockedApi.getAnonymousAuthorization).toHaveBeenCalledWith('http://localhost:8080', identity)
    expect(store.trialStatus?.freeModuleCode).toBe('clipboard')
    expect(store.isModuleAllowed('clipboard')).toBe(true)
  })

  it('stores backend restriction errors when free module cannot be changed', async () => {
    const identity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    const restriction = new commercialAuthApi.CommercialAuthApiError('免费模块每 30 天只能更换一次', 409, 1009)
    mockedApi.changeFreeModule.mockRejectedValueOnce(restriction)
    const store = useCommercialAuthStore()
    store.deviceIdentity = identity

    await expect(store.changeFreeModule('http://localhost:8080', 'processes')).rejects.toThrow('免费模块每 30 天只能更换一次')

    expect(mockedApi.changeFreeModule).toHaveBeenCalledWith('http://localhost:8080', identity, 'processes')
    expect(mockedApi.getAnonymousAuthorization).not.toHaveBeenCalled()
    expect(store.error).toBe('免费模块每 30 天只能更换一次')
    expect(store.loading).toBe(false)
  })

  it('refreshes authenticated authorization without registering the device again', async () => {
    const identity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    mockedApi.getClientAuthorization.mockResolvedValueOnce({
      mode: 'authenticated',
      userId: 10,
      accountStatus: 'active',
      deviceLimit: 2,
      onlineRequired: true,
      offlineUsableUntil: null,
      deviceBinding: { deviceId: 'device-001', bound: true, active: true },
      modules: [
        { moduleCode: 'files', allowed: true, reason: null, expiresAt: null },
        { moduleCode: 'processes', allowed: true, reason: null, expiresAt: null },
        { moduleCode: 'clipboard', allowed: false, reason: '模块未授权或已过期', expiresAt: null }
      ]
    })
    const store = useCommercialAuthStore()
    store.deviceIdentity = identity

    await store.refreshAuthenticatedAuthorization('http://localhost:8080', 'access-token')

    expect(mockedApi.registerClientDevice).not.toHaveBeenCalled()
    expect(mockedApi.getClientAuthorization).toHaveBeenCalledWith('http://localhost:8080', 'access-token', identity, expect.any(Number))
    expect(store.clientAuthorization?.mode).toBe('authenticated')
    expect(store.isModuleAllowed('files')).toBe(true)
  })

  it('merges authenticated authorization with anonymous fallback per module', async () => {
    const identity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    mockedApi.getOrCreateDeviceIdentity.mockResolvedValueOnce(identity)
    mockedApi.registerClientDevice.mockResolvedValueOnce({
      id: 1,
      userId: 10,
      ...identity,
      status: 'active',
      lastSeenAt: null
    })
    mockedApi.getClientAuthorization.mockResolvedValueOnce({
      mode: 'authenticated',
      userId: 10,
      accountStatus: 'active',
      deviceLimit: 2,
      onlineRequired: true,
      offlineUsableUntil: null,
      deviceBinding: { deviceId: 'device-001', bound: true, active: true },
      modules: [
        { moduleCode: 'files', allowed: true, reason: null, expiresAt: null },
        { moduleCode: 'processes', allowed: true, reason: null, expiresAt: null },
        { moduleCode: 'clipboard', allowed: false, reason: '模块未授权或已过期', expiresAt: null }
      ]
    })
    const store = useCommercialAuthStore()
    store.anonymousAuthorization = {
      mode: 'anonymous',
      onlineRequired: true,
      deviceId: 'device-001',
      modules: [
        { moduleCode: 'files', allowed: false, reason: '匿名未授权', expiresAt: null },
        { moduleCode: 'processes', allowed: false, reason: '匿名未授权', expiresAt: null },
        { moduleCode: 'clipboard', allowed: true, reason: null, expiresAt: null }
      ]
    }

    await store.initializeAuthenticated('http://localhost:8080', 'access-token')

    expect(mockedApi.getOrCreateDeviceIdentity).toHaveBeenCalled()
    expect(mockedApi.registerClientDevice).toHaveBeenCalledWith('http://localhost:8080', 'access-token', identity)
    expect(mockedApi.getClientAuthorization).toHaveBeenCalledWith('http://localhost:8080', 'access-token', identity, expect.any(Number))
    expect(store.deviceIdentity?.deviceId).toBe('device-001')
    expect(store.clientDevice?.status).toBe('active')
    expect(store.clientAuthorization?.mode).toBe('authenticated')
    expect(store.isModuleAllowed('files')).toBe(true)
    expect(store.isModuleAllowed('processes')).toBe(true)
    expect(store.isModuleAllowed('clipboard')).toBe(true)
    expect(store.denialReason('clipboard')).toBe(null)
  })

  it('uses authenticated authorization when backend omits offlineUsableUntil on successful refresh', async () => {
    const identity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    mockedApi.getOrCreateDeviceIdentity.mockResolvedValueOnce(identity)
    mockedApi.registerClientDevice.mockResolvedValueOnce({
      id: 1,
      userId: 10,
      ...identity,
      status: 'active',
      lastSeenAt: null
    })
    mockedApi.getClientAuthorization.mockResolvedValueOnce({
      mode: 'authenticated',
      userId: 10,
      accountStatus: 'active',
      deviceLimit: 2,
      onlineRequired: false,
      deviceBinding: { deviceId: 'device-001', bound: true, active: true },
      modules: [
        { moduleCode: 'files', allowed: true, reason: null, expiresAt: null },
        { moduleCode: 'processes', allowed: false, reason: '模块未授权或已过期', expiresAt: null },
        { moduleCode: 'clipboard', allowed: false, reason: '模块未授权或已过期', expiresAt: null }
      ]
    })
    const store = useCommercialAuthStore()
    store.anonymousAuthorization = {
      mode: 'anonymous',
      onlineRequired: true,
      deviceId: 'device-001',
      modules: [
        { moduleCode: 'files', allowed: false, reason: '匿名未授权', expiresAt: null }
      ]
    }

    await store.initializeAuthenticated('http://localhost:8080', 'access-token')

    expect(store.clientAuthorization?.offlineUsableUntil).toBeUndefined()
    expect(store.isModuleAllowed('files')).toBe(true)
  })

  it('keeps Rust-side entitlement on network failure so offline access continues', async () => {
    const identity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    mockedApi.getOrCreateDeviceIdentity.mockResolvedValue(identity)
    mockedApi.registerClientDevice.mockResolvedValue({
      id: 1,
      userId: 10,
      ...identity,
      status: 'active',
      lastSeenAt: null
    })
    const offlineToken = 'signed-entitlement-token'
    mockedApi.getClientAuthorization
      .mockResolvedValueOnce({
        mode: 'authenticated',
        userId: 10,
        accountStatus: 'active',
        deviceLimit: 2,
        onlineRequired: false,
        offlineUsableUntil: '2099-01-01T00:00:00Z',
        offlineToken,
        deviceBinding: { deviceId: 'device-001', bound: true, active: true },
        modules: [
          { moduleCode: 'files', allowed: true, reason: null, expiresAt: null },
          { moduleCode: 'processes', allowed: true, reason: null, expiresAt: null },
          { moduleCode: 'clipboard', allowed: false, reason: '模块未授权或已过期', expiresAt: null }
        ]
      })
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))

    mockInvoke.mockImplementation((command: string, args?: Record<string, unknown>) => {
      if (command === 'set_signed_entitlement') return Promise.resolve()
      if (command === 'clear_signed_entitlement') return Promise.resolve()
      if (command === 'check_signed_entitlement_access') {
        const moduleCode = args?.moduleCode as string
        return Promise.resolve({ allowed: ['files', 'processes'].includes(moduleCode), reason: '' })
      }
      return Promise.reject(new Error(`Unexpected invoke: ${command}`))
    })

    const store = useCommercialAuthStore()

    await store.initializeAuthenticated('http://localhost:8080', 'access-token')
    await expect(store.initializeAuthenticated('http://localhost:8080', 'access-token')).rejects.toThrow('Failed to fetch')

    expect(store.error).toBe('Failed to fetch')
    expect(store.clientAuthorization?.offlineUsableUntil).toBe('2099-01-01T00:00:00Z')
    expect(store.entitlementAccessCache.get('files')).toBe(true)
    expect(store.entitlementAccessCache.get('processes')).toBe(true)
    expect(store.isModuleAllowed('files')).toBe(true)
    expect(store.isModuleAllowed('processes')).toBe(true)
    expect(store.isModuleAllowed('clipboard')).toBe(false)
  })

  it('clears cached authenticated authorization on structured server rejection and falls back to anonymous access', async () => {
    const identity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    const serverRejection = new commercialAuthApi.CommercialAuthApiError('账号已禁用', 403, 403)
    mockedApi.getOrCreateDeviceIdentity.mockResolvedValue(identity)
    mockedApi.registerClientDevice
      .mockResolvedValueOnce({
        id: 1,
        userId: 10,
        ...identity,
        status: 'active',
        lastSeenAt: null
      })
      .mockRejectedValueOnce(serverRejection)
    mockedApi.getClientAuthorization.mockResolvedValueOnce({
      mode: 'authenticated',
      userId: 10,
      accountStatus: 'active',
      deviceLimit: 2,
      onlineRequired: false,
      offlineUsableUntil: '2099-01-01T00:00:00Z',
      deviceBinding: { deviceId: 'device-001', bound: true, active: true },
      modules: [
        { moduleCode: 'files', allowed: true, reason: null, expiresAt: null },
        { moduleCode: 'processes', allowed: true, reason: null, expiresAt: null },
        { moduleCode: 'clipboard', allowed: false, reason: '模块未授权或已过期', expiresAt: null }
      ]
    })
    const store = useCommercialAuthStore()
    store.anonymousAuthorization = {
      mode: 'anonymous',
      onlineRequired: true,
      deviceId: 'device-001',
      modules: [
        { moduleCode: 'files', allowed: true, reason: null, expiresAt: null },
        { moduleCode: 'processes', allowed: false, reason: '匿名未授权', expiresAt: null },
        { moduleCode: 'clipboard', allowed: true, reason: null, expiresAt: null }
      ]
    }

    await store.initializeAuthenticated('http://localhost:8080', 'access-token')
    await expect(store.initializeAuthenticated('http://localhost:8080', 'access-token')).rejects.toThrow('账号已禁用')

    expect(store.error).toBe('账号已禁用')
    expect(store.clientAuthorization).toBeNull()
    expect(store.clientDevice).toBeNull()
    expect(store.entitlementAccessCache.size).toBe(0)
    expect(store.isModuleAllowed('files')).toBe(true)
    expect(store.isModuleAllowed('processes')).toBe(false)
    expect(store.denialReason('processes')).toBe('匿名未授权')
    expect(store.isModuleAllowed('clipboard')).toBe(true)
  })

  it('does not apply frontend offlineUsableUntil expiration check', () => {
    const store = useCommercialAuthStore()
    store.clientAuthorization = {
      mode: 'authenticated',
      userId: 10,
      accountStatus: 'active',
      deviceLimit: 2,
      onlineRequired: false,
      offlineUsableUntil: '2000-01-01T00:00:00Z',
      deviceBinding: { deviceId: 'device-001', bound: true, active: true },
      modules: [
        { moduleCode: 'files', allowed: true, reason: null, expiresAt: null },
        { moduleCode: 'processes', allowed: true, reason: null, expiresAt: null },
        { moduleCode: 'clipboard', allowed: true, reason: null, expiresAt: null }
      ]
    }
    store.anonymousAuthorization = {
      mode: 'anonymous',
      onlineRequired: true,
      deviceId: 'device-001',
      modules: [
        { moduleCode: 'files', allowed: false, reason: '匿名未授权', expiresAt: null },
        { moduleCode: 'processes', allowed: false, reason: '非当前免费模块', expiresAt: null },
        { moduleCode: 'clipboard', allowed: false, reason: '非当前免费模块', expiresAt: null }
      ]
    }

    expect(store.isModuleAllowed('files')).toBe(true)
    expect(store.isModuleAllowed('processes')).toBe(true)
    expect(store.isModuleAllowed('clipboard')).toBe(true)
  })

  it('prioritizes Rust entitlement cache over memory moduleAccess', () => {
    const store = useCommercialAuthStore()
    store.entitlementAccessCache = new Map([['files', false]])
    store.clientAuthorization = {
      mode: 'authenticated',
      userId: 10,
      accountStatus: 'active',
      deviceLimit: 2,
      onlineRequired: true,
      deviceBinding: { deviceId: 'device-001', bound: true, active: true },
      modules: [
        { moduleCode: 'files', allowed: true, reason: null, expiresAt: null }
      ]
    }

    expect(store.isModuleAllowed('files')).toBe(false)
  })

  it('syncs signed entitlement to Rust for both online and offline authorizations', async () => {
    const identity = {
      deviceId: 'device-001',
      fingerprintHash: 'fingerprint-001',
      deviceName: 'Laptop'
    }
    const offlineToken = 'signed-entitlement-token'
    mockedApi.getOrCreateDeviceIdentity.mockResolvedValue(identity)
    mockedApi.registerClientDevice.mockResolvedValue({
      id: 1,
      userId: 10,
      ...identity,
      status: 'active',
      lastSeenAt: null
    })
    mockedApi.getClientAuthorization.mockResolvedValueOnce({
      mode: 'authenticated',
      userId: 10,
      accountStatus: 'active',
      deviceLimit: 2,
      onlineRequired: true,
      offlineToken,
      deviceBinding: { deviceId: 'device-001', bound: true, active: true },
      modules: [
        { moduleCode: 'files', allowed: true, reason: null, expiresAt: null }
      ]
    })
    const store = useCommercialAuthStore()

    await store.initializeAuthenticated('http://localhost:8080', 'access-token')

    expect(mockInvoke).toHaveBeenCalledWith('set_signed_entitlement', { token: offlineToken })
  })
})
