import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { Store } from '@tauri-apps/plugin-store'
import * as authApi from '@/api/auth'
import type {
  CheckVerificationRequest,
  RegisterRequest,
  SendVerificationRequest,
  UserSummary
} from '@/api/auth'
import { useDeviceStore } from '@/stores/deviceStore'

interface AuthSession {
  accessToken: string
  refreshToken: string
  user: UserSummary
  expiresAt: number
}

const AUTH_STORE_PATH = 'file-keeper-auth.json'
const AUTH_SESSION_KEY = 'authSession'
// 提前 1 分钟刷新，避免边界时间导致的 401
const TOKEN_REFRESH_MARGIN_MS = 60 * 1000

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)
  const refreshToken = ref<string | null>(null)
  const user = ref<UserSummary | null>(null)
  const expiresAt = ref<number | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  const isAuthenticated = computed(() => Boolean(accessToken.value && refreshToken.value && user.value))

  // 防止多个并发请求同时触发 refresh
  let refreshPromise: Promise<void> | null = null

  async function login(baseUrl: string, identifier: string, password: string): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const response = await authApi.login(baseUrl, identifier, password)
      const session = toSession(response.accessToken, response.refreshToken, response.user, response.expiresInSeconds)
      try {
        setSession(session)
        await persistSession(session)
        await useDeviceStore().registerAuthenticatedDevice(baseUrl, response.accessToken)
      } catch (err) {
        clearSession()
        try {
          await persistSession(null)
        } catch {
          // Preserve the original login failure.
        }
        throw err
      }
    } catch (err) {
      error.value = errorMessage(err)
      throw err
    } finally {
      loading.value = false
    }
  }

  async function logout(baseUrl: string): Promise<void> {
    loading.value = true
    error.value = null
    const token = refreshToken.value
    try {
      if (token) {
        await authApi.logout(baseUrl, token)
      }
    } catch (err) {
      error.value = errorMessage(err)
    } finally {
      try {
        clearSession()
        await persistSession(null)
      } finally {
        loading.value = false
      }
    }
  }

  async function restoreSession(baseUrl: string): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const storedSession = await loadSession()
      if (!storedSession?.refreshToken) {
        clearSession()
        return
      }

      // 兼容旧版持久化：旧 session 没有 expiresAt，按已过期处理并刷新
      const session: AuthSession = {
        ...storedSession,
        expiresAt: storedSession.expiresAt || 0
      }
      setSession(session)

      if (Date.now() >= session.expiresAt - TOKEN_REFRESH_MARGIN_MS) {
        await refreshAccessToken(baseUrl)
      } else {
        await persistSession(session)
      }
      await useDeviceStore().registerAuthenticatedDevice(baseUrl, accessToken.value!)
    } catch (err) {
      error.value = errorMessage(err)
      clearSession()
      await persistSession(null)
    } finally {
      loading.value = false
    }
  }

  async function register(baseUrl: string, request: RegisterRequest): Promise<UserSummary> {
    loading.value = true
    error.value = null
    try {
      return await authApi.register(baseUrl, request)
    } catch (err) {
      error.value = errorMessage(err)
      throw err
    } finally {
      loading.value = false
    }
  }

  async function sendVerificationCode(baseUrl: string, request: SendVerificationRequest): Promise<void> {
    loading.value = true
    error.value = null
    try {
      await authApi.sendVerificationCode(baseUrl, request)
    } catch (err) {
      error.value = errorMessage(err)
      throw err
    } finally {
      loading.value = false
    }
  }

  async function checkVerificationCode(baseUrl: string, request: CheckVerificationRequest): Promise<boolean> {
    loading.value = true
    error.value = null
    try {
      return await authApi.checkVerificationCode(baseUrl, request)
    } catch (err) {
      error.value = errorMessage(err)
      throw err
    } finally {
      loading.value = false
    }
  }

  function setSession(session: AuthSession) {
    accessToken.value = session.accessToken
    refreshToken.value = session.refreshToken
    user.value = session.user
    expiresAt.value = session.expiresAt
  }

  function clearSession() {
    accessToken.value = null
    refreshToken.value = null
    user.value = null
    expiresAt.value = null
  }

  async function refreshAccessToken(baseUrl: string): Promise<void> {
    if (refreshPromise) {
      await refreshPromise
      return
    }
    refreshPromise = (async () => {
      try {
        const currentRefreshToken = refreshToken.value
        if (!currentRefreshToken) {
          throw new Error('未登录')
        }
        const response = await authApi.refresh(baseUrl, currentRefreshToken)
        const session = toSession(response.accessToken, response.refreshToken, response.user, response.expiresInSeconds)
        setSession(session)
        await persistSession(session)
      } catch (err) {
        error.value = errorMessage(err)
        clearSession()
        await persistSession(null)
        throw err
      } finally {
        refreshPromise = null
      }
    })()
    await refreshPromise
  }

  async function ensureValidToken(baseUrl: string): Promise<string> {
    if (!accessToken.value) {
      throw new Error('未登录')
    }
    if (!expiresAt.value || Date.now() >= expiresAt.value - TOKEN_REFRESH_MARGIN_MS) {
      await refreshAccessToken(baseUrl)
    }
    if (!accessToken.value) {
      throw new Error('未登录')
    }
    return accessToken.value
  }

  return {
    accessToken,
    refreshToken,
    user,
    loading,
    error,
    isAuthenticated,
    login,
    logout,
    restoreSession,
    refreshAccessToken,
    ensureValidToken,
    register,
    sendVerificationCode,
    checkVerificationCode
  }
})

function toSession(accessToken: string, refreshToken: string, user: UserSummary, expiresInSeconds: number): AuthSession {
  return {
    accessToken,
    refreshToken,
    user,
    expiresAt: Date.now() + expiresInSeconds * 1000
  }
}

async function loadSession(): Promise<AuthSession | null> {
  const store = await loadAuthStore()
  return await store.get<AuthSession | null>(AUTH_SESSION_KEY) ?? null
}

async function persistSession(session: AuthSession | null): Promise<void> {
  const store = await loadAuthStore()
  await store.set(AUTH_SESSION_KEY, session)
  await store.save()
}

async function loadAuthStore() {
  return Store.load(AUTH_STORE_PATH, {
    defaults: {},
    autoSave: 500
  })
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err)
}
