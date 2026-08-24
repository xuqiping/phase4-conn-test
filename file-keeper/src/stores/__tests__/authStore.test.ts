import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { Store } from '@tauri-apps/plugin-store'
import * as authApi from '@/api/auth'
import { useAuthStore } from '../authStore'
import type { RegisterRequest, UserSummary } from '@/api/auth'

const mocks = vi.hoisted(() => ({
  storeGet: vi.fn(),
  storeSet: vi.fn(),
  storeSave: vi.fn(),
  storeLoad: vi.fn(),
  authLogin: vi.fn(),
  authLogout: vi.fn(),
  authRefresh: vi.fn(),
  authRegister: vi.fn(),
  authSendVerificationCode: vi.fn(),
  authCheckVerificationCode: vi.fn(),
  registerAuthenticatedDevice: vi.fn()
}))

vi.mock('@tauri-apps/plugin-store', () => ({
  Store: {
    load: mocks.storeLoad
  }
}))

vi.mock('@/api/auth', () => ({
  login: mocks.authLogin,
  logout: mocks.authLogout,
  refresh: mocks.authRefresh,
  register: mocks.authRegister,
  sendVerificationCode: mocks.authSendVerificationCode,
  checkVerificationCode: mocks.authCheckVerificationCode
}))

vi.mock('@/stores/deviceStore', () => ({
  useDeviceStore: () => ({
    registerAuthenticatedDevice: mocks.registerAuthenticatedDevice
  })
}))

const user: UserSummary = {
  id: 10,
  email: 'user@example.com',
  phone: null,
  role: 'user',
  status: 'active',
  emailVerified: true,
  phoneVerified: false,
  deviceLimit: 2,
  offlineCacheMinutes: 60
}

describe('authStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mocks.storeGet.mockReset()
    mocks.storeSet.mockReset()
    mocks.storeSave.mockReset()
    mocks.storeLoad.mockReset()
    mocks.authLogin.mockReset()
    mocks.authLogout.mockReset()
    mocks.authRefresh.mockReset()
    mocks.authRegister.mockReset()
    mocks.authSendVerificationCode.mockReset()
    mocks.authCheckVerificationCode.mockReset()
    mocks.registerAuthenticatedDevice.mockReset()
    mocks.storeLoad.mockResolvedValue({
      get: mocks.storeGet,
      set: mocks.storeSet,
      save: mocks.storeSave
    })
  })

  it('stores access token, refresh token, and user on login', async () => {
    mocks.authLogin.mockResolvedValueOnce({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
      expiresInSeconds: 900,
      user
    })
    const store = useAuthStore()

    await store.login('http://localhost:8080', 'user@example.com', 'secret')

    expect(authApi.login).toHaveBeenCalledWith('http://localhost:8080', 'user@example.com', 'secret')
    expect(store.accessToken).toBe('access-token')
    expect(store.refreshToken).toBe('refresh-token')
    expect(store.user).toEqual(user)
    expect(store.isAuthenticated).toBe(true)
  })

  it('persists session to the Tauri store on login', async () => {
    mocks.authLogin.mockResolvedValueOnce({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
      expiresInSeconds: 900,
      user
    })
    const store = useAuthStore()

    await store.login('http://localhost:8080', 'user@example.com', 'secret')

    expect(Store.load).toHaveBeenCalledWith('file-keeper-auth.json', {
      defaults: {},
      autoSave: 500
    })
    expect(mocks.storeSet).toHaveBeenCalledWith('authSession', expect.objectContaining({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
      user
    }))
    expect(mocks.storeSave).toHaveBeenCalled()
  })

  it('registers or heartbeats the authenticated device on login', async () => {
    mocks.authLogin.mockResolvedValueOnce({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
      expiresInSeconds: 900,
      user
    })
    const store = useAuthStore()

    await store.login('http://localhost:8080', 'user@example.com', 'secret')

    expect(mocks.registerAuthenticatedDevice).toHaveBeenCalledWith('http://localhost:8080', 'access-token')
  })

  it('clears and persists the session when authenticated device registration fails on login', async () => {
    mocks.authLogin.mockResolvedValueOnce({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
      expiresInSeconds: 900,
      user
    })
    mocks.registerAuthenticatedDevice.mockRejectedValueOnce(new Error('设备已禁用'))
    const store = useAuthStore()

    await expect(store.login('http://localhost:8080', 'user@example.com', 'secret')).rejects.toThrow(
      '设备已禁用'
    )

    expect(store.accessToken).toBeNull()
    expect(store.refreshToken).toBeNull()
    expect(store.user).toBeNull()
    expect(store.isAuthenticated).toBe(false)
    expect(mocks.storeSet).toHaveBeenLastCalledWith('authSession', null)
    expect(mocks.storeSave).toHaveBeenCalled()
  })

  it('clears in-memory session when login session persistence fails', async () => {
    mocks.authLogin.mockResolvedValueOnce({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
      expiresInSeconds: 900,
      user
    })
    mocks.storeSave.mockRejectedValueOnce(new Error('store save failed'))
    const store = useAuthStore()

    await expect(store.login('http://localhost:8080', 'user@example.com', 'secret')).rejects.toThrow('store save failed')

    expect(store.accessToken).toBeNull()
    expect(store.refreshToken).toBeNull()
    expect(store.user).toBeNull()
    expect(store.isAuthenticated).toBe(false)
    expect(mocks.registerAuthenticatedDevice).not.toHaveBeenCalled()
  })

  it('calls logout API when refresh token exists, clears login state, and persists cleared session', async () => {
    mocks.authLogin.mockResolvedValueOnce({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
      expiresInSeconds: 900,
      user
    })
    const store = useAuthStore()
    await store.login('http://localhost:8080', 'user@example.com', 'secret')
    mocks.storeSet.mockClear()
    mocks.storeSave.mockClear()

    await store.logout('http://localhost:8080')

    expect(authApi.logout).toHaveBeenCalledWith('http://localhost:8080', 'refresh-token')
    expect(store.accessToken).toBeNull()
    expect(store.refreshToken).toBeNull()
    expect(store.user).toBeNull()
    expect(store.isAuthenticated).toBe(false)
    expect(mocks.storeSet).toHaveBeenCalledWith('authSession', null)
    expect(mocks.storeSave).toHaveBeenCalled()
  })

  it('does not delete or re-register device identity when logging out', async () => {
    mocks.authLogin.mockResolvedValueOnce({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
      expiresInSeconds: 900,
      user
    })
    const store = useAuthStore()
    await store.login('http://localhost:8080', 'user@example.com', 'secret')
    mocks.registerAuthenticatedDevice.mockClear()

    await store.logout('http://localhost:8080')

    expect(mocks.registerAuthenticatedDevice).not.toHaveBeenCalled()
    expect(store.accessToken).toBeNull()
  })

  it('restores the logged-out state without starting anonymous authorization', async () => {
    mocks.storeGet.mockResolvedValueOnce(null)
    const store = useAuthStore()

    await store.restoreSession('http://localhost:8080')

    expect(mocks.storeGet).toHaveBeenCalledWith('authSession')
    expect(mocks.registerAuthenticatedDevice).not.toHaveBeenCalled()
    expect(authApi.refresh).not.toHaveBeenCalled()
  })

  it('uses local refresh token and registers the authenticated device on restore', async () => {
    mocks.storeGet.mockResolvedValueOnce({
      accessToken: 'old-access-token',
      refreshToken: 'local-refresh-token',
      user
    })
    mocks.authRefresh.mockResolvedValueOnce({
      accessToken: 'new-access-token',
      refreshToken: 'new-refresh-token',
      expiresInSeconds: 900,
      user
    })
    const store = useAuthStore()

    await store.restoreSession('http://localhost:8080')

    expect(authApi.refresh).toHaveBeenCalledWith('http://localhost:8080', 'local-refresh-token')
    expect(store.accessToken).toBe('new-access-token')
    expect(store.refreshToken).toBe('new-refresh-token')
    expect(store.user).toEqual(user)
    expect(mocks.storeSet).toHaveBeenCalledWith('authSession', expect.objectContaining({
      accessToken: 'new-access-token',
      refreshToken: 'new-refresh-token',
      user
    }))
    expect(mocks.registerAuthenticatedDevice).toHaveBeenCalledWith('http://localhost:8080', 'new-access-token')
  })

  it('clears login state without anonymous fallback when restore refresh fails', async () => {
    mocks.storeGet.mockResolvedValueOnce({
      accessToken: 'old-access-token',
      refreshToken: 'local-refresh-token',
      user
    })
    mocks.authRefresh.mockRejectedValueOnce(new Error('refresh failed'))
    const store = useAuthStore()

    await store.restoreSession('http://localhost:8080')

    expect(store.accessToken).toBeNull()
    expect(store.refreshToken).toBeNull()
    expect(store.user).toBeNull()
    expect(store.error).toBe('refresh failed')
    expect(mocks.storeSet).toHaveBeenCalledWith('authSession', null)
    expect(mocks.registerAuthenticatedDevice).not.toHaveBeenCalled()
  })

  it('returns registered user without logging in automatically', async () => {
    const request: RegisterRequest = { email: 'new@example.com', password: 'secret' }
    const registeredUser = { ...user, id: 11, email: 'new@example.com' }
    mocks.authRegister.mockResolvedValueOnce(registeredUser)
    const store = useAuthStore()

    const result = await store.register('http://localhost:8080', request)

    expect(result).toEqual(registeredUser)
    expect(authApi.register).toHaveBeenCalledWith('http://localhost:8080', request)
    expect(store.accessToken).toBeNull()
    expect(store.refreshToken).toBeNull()
    expect(store.user).toBeNull()
    expect(mocks.registerAuthenticatedDevice).not.toHaveBeenCalled()
  })

  it('delegates verification wrappers to auth API functions', async () => {
    mocks.authSendVerificationCode.mockResolvedValueOnce(undefined)
    mocks.authCheckVerificationCode.mockResolvedValueOnce(true)
    const sendRequest = { contactType: 'email' as const, contact: 'user@example.com' }
    const checkRequest = { contactType: 'email' as const, contact: 'user@example.com', code: '123456' }
    const store = useAuthStore()

    await store.sendVerificationCode('http://localhost:8080', sendRequest)
    const verified = await store.checkVerificationCode('http://localhost:8080', checkRequest)

    expect(authApi.sendVerificationCode).toHaveBeenCalledWith('http://localhost:8080', sendRequest)
    expect(authApi.checkVerificationCode).toHaveBeenCalledWith('http://localhost:8080', checkRequest)
    expect(verified).toBe(true)
  })
})
