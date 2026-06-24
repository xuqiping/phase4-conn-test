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
import { useCommercialAuthStore } from '@/stores/commercialAuthStore'

interface AuthSession {
  accessToken: string
  refreshToken: string
  user: UserSummary
}

const AUTH_STORE_PATH = 'file-keeper-auth.json'
const AUTH_SESSION_KEY = 'authSession'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)
  const refreshToken = ref<string | null>(null)
  const user = ref<UserSummary | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  const isAuthenticated = computed(() => Boolean(accessToken.value && refreshToken.value && user.value))

  async function login(baseUrl: string, identifier: string, password: string): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const response = await authApi.login(baseUrl, identifier, password)
      const session = toSession(response.accessToken, response.refreshToken, response.user)
      try {
        setSession(session)
        await persistSession(session)
        await useCommercialAuthStore().initializeAuthenticated(baseUrl, response.accessToken)
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
        await useCommercialAuthStore().initializeAnonymous(baseUrl)
      } finally {
        loading.value = false
      }
    }
  }

  async function restoreSession(baseUrl: string): Promise<void> {
    loading.value = true
    error.value = null
    let restoringAnonymous = false
    try {
      const storedSession = await loadSession()
      if (!storedSession?.refreshToken) {
        clearSession()
        restoringAnonymous = true
        await useCommercialAuthStore().initializeAnonymous(baseUrl)
        return
      }

      const response = await authApi.refresh(baseUrl, storedSession.refreshToken)
      const session = toSession(response.accessToken, response.refreshToken, response.user)
      setSession(session)
      await persistSession(session)
      await useCommercialAuthStore().initializeAuthenticated(baseUrl, response.accessToken)
    } catch (err) {
      error.value = errorMessage(err)
      if (restoringAnonymous) {
        throw err
      }
      clearSession()
      await persistSession(null)
      await useCommercialAuthStore().initializeAnonymous(baseUrl)
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
  }

  function clearSession() {
    accessToken.value = null
    refreshToken.value = null
    user.value = null
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
    register,
    sendVerificationCode,
    checkVerificationCode
  }
})

function toSession(accessToken: string, refreshToken: string, user: UserSummary): AuthSession {
  return { accessToken, refreshToken, user }
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
