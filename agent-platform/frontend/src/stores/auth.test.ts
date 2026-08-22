import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from './auth'
import { STORAGE_KEYS, getStorage } from '@/utils/storage'

// mock auth API
vi.mock('@/api/auth', () => ({
  authApi: {
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
    refresh: vi.fn(),
    getMe: vi.fn()
  }
}))

import { authApi } from '@/api/auth'
const mockedLogin = vi.mocked(authApi.login)
const mockedRegister = vi.mocked(authApi.register)
const mockedLogout = vi.mocked(authApi.logout)

describe('auth store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('isLoggedIn is false by default', () => {
    const store = useAuthStore()
    expect(store.isLoggedIn).toBe(false)
  })

  it('isAdmin is false when no userInfo', () => {
    const store = useAuthStore()
    expect(store.isAdmin).toBe(false)
  })

  it('hasPermission returns false when no userInfo', () => {
    const store = useAuthStore()
    expect(store.hasPermission('agent:read')).toBe(false)
  })

  it('login stores tokens and userInfo', async () => {
    mockedLogin.mockResolvedValue({
      data: {
        code: 200,
        message: 'ok',
        data: {
          accessToken: 'at-123',
          refreshToken: 'rt-456',
          tokenType: 'Bearer',
          expiresIn: 900,
          userInfo: {
            id: 1,
            username: 'testuser',
            email: 'test@test.com',
            avatar: null,
            roles: ['user'],
            permissions: ['agent:read']
          }
        }
      }
    } as any)

    const store = useAuthStore()
    await store.login({ username: 'testuser', password: 'pass123' })

    expect(store.accessToken).toBe('at-123')
    expect(store.refreshToken).toBe('rt-456')
    expect(store.userInfo?.username).toBe('testuser')
    expect(store.isLoggedIn).toBe(true)
    expect(getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN)).toBe('at-123')
  })

  it('login sets loading state', async () => {
    let resolveLogin: (v: any) => void
    mockedLogin.mockImplementation(() => new Promise(r => { resolveLogin = r }))

    const store = useAuthStore()
    const promise = store.login({ username: 'u', password: 'p' })
    expect(store.loading).toBe(true)
    resolveLogin!({
      data: { code: 200, message: 'ok', data: { accessToken: 'a', refreshToken: 'r', tokenType: 'Bearer', expiresIn: 0, userInfo: { id: 1, username: 'u', email: null, avatar: null, roles: [], permissions: [] } } }
    })
    await promise
    expect(store.loading).toBe(false)
  })

  it('logout clears state', async () => {
    mockedLogout.mockResolvedValue({ data: { code: 200, message: 'ok' } } as any)
    localStorage.setItem(STORAGE_KEYS.ACCESS_TOKEN, JSON.stringify('at'))
    localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, JSON.stringify('rt'))

    const store = useAuthStore()
    store.accessToken = 'at'
    store.refreshToken = 'rt'
    store.userInfo = { id: 1, username: 'u', email: null, avatar: null, roles: [], permissions: [] }

    await store.logout()

    expect(store.accessToken).toBeNull()
    expect(store.refreshToken).toBeNull()
    expect(store.userInfo).toBeNull()
    expect(store.isLoggedIn).toBe(false)
  })

  it('hasPermission returns true when user has permission', async () => {
    mockedLogin.mockResolvedValue({
      data: {
        code: 200, message: 'ok',
        data: {
          accessToken: 'at', refreshToken: 'rt', tokenType: 'Bearer', expiresIn: 0,
          userInfo: { id: 1, username: 'u', email: null, avatar: null, roles: ['admin'], permissions: ['agent:read', 'agent:create'] }
        }
      }
    } as any)

    const store = useAuthStore()
    await store.login({ username: 'u', password: 'p' })
    expect(store.hasPermission('agent:read')).toBe(true)
    expect(store.hasPermission('workflow:read')).toBe(false)
  })

  it('isAdmin returns true when roles include admin', async () => {
    mockedLogin.mockResolvedValue({
      data: {
        code: 200, message: 'ok',
        data: {
          accessToken: 'at', refreshToken: 'rt', tokenType: 'Bearer', expiresIn: 0,
          userInfo: { id: 1, username: 'admin', email: null, avatar: null, roles: ['admin'], permissions: [] }
        }
      }
    } as any)

    const store = useAuthStore()
    await store.login({ username: 'admin', password: 'p' })
    expect(store.isAdmin).toBe(true)
  })

  it('register calls API and sets loading', async () => {
    mockedRegister.mockResolvedValue({ data: { code: 200, message: 'ok' } } as any)
    const store = useAuthStore()
    // 12x B1：注册参数含邮箱验证码
    await store.register({ username: 'new', email: 'new@test.com', password: 'pass', emailCode: '123456' })
    expect(mockedRegister).toHaveBeenCalledWith({ username: 'new', email: 'new@test.com', password: 'pass', emailCode: '123456' })
  })
})
