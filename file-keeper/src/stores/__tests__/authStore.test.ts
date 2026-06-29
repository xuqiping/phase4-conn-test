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
  initializeAuthenticated: vi.fn(),
  initializeAnonymous: vi.fn()
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

vi.mock('@/stores/commercialAuthStore', () => ({
  useCommercialAuthStore: () => ({
    initializeAuthenticated: mocks.initializeAuthenticated,
    initializeAnonymous: mocks.initializeAnonymous
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
    mocks.initializeAuthenticated.mockReset()
    mocks.initializeAnonymous.mockReset()
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

  it('initializes authenticated commercial authorization on login', async () => {
    mocks.authLogin.mockResolvedValueOnce({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
      expiresInSeconds: 900,
      user
    })
    const store = useAuthStore()

    await store.login('http://localhost:8080', 'user@example.com', 'secret')

    expect(mocks.initializeAuthenticated).toHaveBeenCalledWith('http://localhost:8080', 'access-token')
  })

  it('clears and persists cleared session when authenticated authorization initialization fails on login', async () => {
    mocks.authLogin.mockResolvedValueOnce({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
      expiresInSeconds: 900,
      user
    })
    mocks.initializeAuthenticated.mockRejectedValueOnce(new Error('authorization init failed'))
    const store = useAuthStore()

    await expect(store.login('http://localhost:8080', 'user@example.com', 'secret')).rejects.toThrow(
      'authorization init failed'
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
    expect(mocks.initializeAuthenticated).not.toHaveBeenCalled()
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

  it('initializes anonymous commercial authorization after clearing login state on logout', async () => {
    mocks.authLogin.mockResolvedValueOnce({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
      expiresInSeconds: 900,
      user
    })
    const store = useAuthStore()
    await store.login('http://localhost:8080', 'user@example.com', 'secret')
    mocks.initializeAnonymous.mockClear()

    await store.logout('http://localhost:8080')

    expect(mocks.initializeAnonymous).toHaveBeenCalledWith('http://localhost:8080')
    expect(store.accessToken).toBeNull()
  })

  it('resets loading and keeps login state cleared when anonymous authorization initialization fails on logout', async () => {
    mocks.authLogin.mockResolvedValueOnce({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
      expiresInSeconds: 900,
      user
    })
    const store = useAuthStore()
    await store.login('http://localhost:8080', 'user@example.com', 'secret')
    mocks.initializeAnonymous.mockRejectedValueOnce(new Error('anonymous init failed'))

    await expect(store.logout('http://localhost:8080')).rejects.toThrow('anonymous init failed')

    expect(store.loading).toBe(false)
    expect(store.accessToken).toBeNull()
    expect(store.refreshToken).toBeNull()
    expect(store.user).toBeNull()
    expect(store.isAuthenticated).toBe(false)
    expect(mocks.storeSet).toHaveBeenLastCalledWith('authSession', null)
  })

  it('initializes anonymous commercial authorization when no local session exists on restore', async () => {
    mocks.storeGet.mockResolvedValueOnce(null)
    const store = useAuthStore()

    await store.restoreSession('http://localhost:8080')

    expect(mocks.storeGet).toHaveBeenCalledWith('authSession')
    expect(mocks.initializeAnonymous).toHaveBeenCalledWith('http://localhost:8080')
    expect(authApi.refresh).not.toHaveBeenCalled()
  })

  it('does not retry anonymous authorization initialization when restore has no local session and anonymous initialization fails', async () => {
    mocks.storeGet.mockResolvedValueOnce(null)
    mocks.initializeAnonymous.mockRejectedValueOnce(new Error('anonymous init failed'))
    const store = useAuthStore()

    await expect(store.restoreSession('http://localhost:8080')).rejects.toThrow('anonymous init failed')

    expect(mocks.initializeAnonymous).toHaveBeenCalledTimes(1)
    expect(store.loading).toBe(false)
    expect(authApi.refresh).not.toHaveBeenCalled()
  })

  it('uses local refresh token to refresh the session and initialize authenticated authorization on restore', async () => {
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
    expect(mocks.initializeAuthenticated).toHaveBeenCalledWith('http://localhost:8080', 'new-access-token')
  })

  it('clears login state and falls back to anonymous authorization when restore refresh fails', async () => {
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
    expect(mocks.initializeAnonymous).toHaveBeenCalledWith('http://localhost:8080')
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
    expect(mocks.initializeAuthenticated).not.toHaveBeenCalled()
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
