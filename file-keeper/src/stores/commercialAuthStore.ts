import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { invoke } from '@tauri-apps/api/core'
import * as commercialAuthApi from '@/api/commercialAuth'
import type {
  AnonymousAuthorizationSnapshot,
  AnonymousTrialStatus,
  ClientAuthorizationSnapshot,
  ClientDevice,
  DeviceIdentity,
  ModuleAccess,
  ModuleCode
} from '@/api/commercialAuth'

export const useCommercialAuthStore = defineStore('commercialAuth', () => {
  const deviceIdentity = ref<DeviceIdentity | null>(null)
  const trialStatus = ref<AnonymousTrialStatus | null>(null)
  const anonymousAuthorization = ref<AnonymousAuthorizationSnapshot | null>(null)
  const clientDevice = ref<ClientDevice | null>(null)
  const clientAuthorization = ref<ClientAuthorizationSnapshot | null>(null)
  const usingOfflineClientAuthorization = ref(false)
  const offlineAccessCache = ref(new Map<ModuleCode, boolean>())
  const loading = ref(false)
  const error = ref<string | null>(null)

  const moduleAccess = computed(() => {
    const access = new Map<ModuleCode, { allowed: boolean; reason: string | null }>()
    const authenticatedModules = getUsableClientAuthorization()?.modules ?? []
    const anonymousModules = anonymousAuthorization.value?.modules ?? []
    const moduleCodes = new Set<ModuleCode>([
      ...anonymousModules.map(module => module.moduleCode),
      ...authenticatedModules.map(module => module.moduleCode)
    ])

    moduleCodes.forEach(moduleCode => {
      access.set(moduleCode, resolveModuleAccess(moduleCode, authenticatedModules, anonymousModules))
    })
    return access
  })

  async function initializeAnonymous(baseUrl: string) {
    loading.value = true
    error.value = null
    try {
      const identity = await commercialAuthApi.getOrCreateDeviceIdentity()
      deviceIdentity.value = identity
      trialStatus.value = await commercialAuthApi.startAnonymousTrial(baseUrl, identity)
      anonymousAuthorization.value = await commercialAuthApi.getAnonymousAuthorization(baseUrl, identity)
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      anonymousAuthorization.value = null
      throw err
    } finally {
      loading.value = false
    }
  }

  async function initializeAuthenticated(baseUrl: string, accessToken: string) {
    loading.value = true
    error.value = null
    try {
      const identity = await commercialAuthApi.getOrCreateDeviceIdentity()
      deviceIdentity.value = identity
      clientDevice.value = await commercialAuthApi.registerClientDevice(baseUrl, accessToken, identity)
      clientAuthorization.value = await commercialAuthApi.getClientAuthorization(baseUrl, accessToken, identity, Date.now())
      usingOfflineClientAuthorization.value = false
      await syncOfflineTokenToRust()
    } catch (err) {
      handleAuthenticatedAuthorizationError(err)
      throw err
    } finally {
      loading.value = false
    }
  }

  async function selectFreeModule(baseUrl: string, moduleCode: ModuleCode): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const identity = await ensureDeviceIdentity()
      trialStatus.value = await commercialAuthApi.selectFreeModule(baseUrl, identity, moduleCode)
      anonymousAuthorization.value = await commercialAuthApi.getAnonymousAuthorization(baseUrl, identity)
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      throw err
    } finally {
      loading.value = false
    }
  }

  async function changeFreeModule(baseUrl: string, moduleCode: ModuleCode): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const identity = await ensureDeviceIdentity()
      trialStatus.value = await commercialAuthApi.changeFreeModule(baseUrl, identity, moduleCode)
      anonymousAuthorization.value = await commercialAuthApi.getAnonymousAuthorization(baseUrl, identity)
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      throw err
    } finally {
      loading.value = false
    }
  }

  async function refreshAnonymousAuthorization(baseUrl: string): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const identity = await ensureDeviceIdentity()
      anonymousAuthorization.value = await commercialAuthApi.getAnonymousAuthorization(baseUrl, identity)
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      anonymousAuthorization.value = null
      throw err
    } finally {
      loading.value = false
    }
  }

  async function refreshAuthenticatedAuthorization(baseUrl: string, accessToken: string): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const identity = await ensureDeviceIdentity()
      clientAuthorization.value = await commercialAuthApi.getClientAuthorization(baseUrl, accessToken, identity, Date.now())
      usingOfflineClientAuthorization.value = false
      await syncOfflineTokenToRust()
    } catch (err) {
      handleAuthenticatedAuthorizationError(err)
      throw err
    } finally {
      loading.value = false
    }
  }

  async function ensureDeviceIdentity(): Promise<DeviceIdentity> {
    if (deviceIdentity.value) {
      return deviceIdentity.value
    }
    const identity = await commercialAuthApi.getOrCreateDeviceIdentity()
    deviceIdentity.value = identity
    return identity
  }

  function handleAuthenticatedAuthorizationError(err: unknown) {
    error.value = err instanceof Error ? err.message : String(err)
    if (commercialAuthApi.isCommercialAuthApiError(err)) {
      clientAuthorization.value = null
      clientDevice.value = null
      usingOfflineClientAuthorization.value = false
      offlineAccessCache.value.clear()
      void clearOfflineTokenInRust()
    } else if (hasValidOfflineClientAuthorization()) {
      usingOfflineClientAuthorization.value = true
    } else {
      clientAuthorization.value = null
      usingOfflineClientAuthorization.value = false
      offlineAccessCache.value.clear()
      void clearOfflineTokenInRust()
    }
  }

  async function syncOfflineTokenToRust() {
    offlineAccessCache.value.clear()
    const auth = clientAuthorization.value
    if (!auth || auth.onlineRequired || !auth.offlineUsableUntil || !auth.offlineToken) {
      await clearOfflineTokenInRust()
      return
    }
    const until = new Date(auth.offlineUsableUntil).getTime()
    const now = Date.now()
    const offlineSeconds = Math.max(0, Math.floor((until - now) / 1000))
    try {
      await invoke('set_offline_token', { token: auth.offlineToken, offlineSeconds })
      const cache = new Map<ModuleCode, boolean>()
      for (const moduleCode of ['files', 'processes', 'clipboard', 'work-report'] as ModuleCode[]) {
        try {
          const result = await invoke<{ allowed: boolean; reason: string }>('check_offline_access', { moduleCode })
          cache.set(moduleCode, result.allowed)
        } catch {
          cache.set(moduleCode, false)
        }
      }
      offlineAccessCache.value = cache
    } catch (e) {
      console.error('[commercialAuthStore] set_offline_token failed:', e)
    }
  }

  async function clearOfflineTokenInRust() {
    offlineAccessCache.value.clear()
    try {
      await invoke('clear_offline_token')
    } catch (e) {
      console.error('[commercialAuthStore] clear_offline_token failed:', e)
    }
  }

  function getUsableClientAuthorization(): ClientAuthorizationSnapshot | null {
    const authorization = clientAuthorization.value
    if (!authorization) {
      return null
    }
    // When operating on cached (offline) authorization, verify the cache hasn't expired
    if (usingOfflineClientAuthorization.value && !hasValidOfflineClientAuthorization()) {
      return null
    }
    // Even for fresh authorizations with offlineCacheMinutes > 0,
    // if the offlineUsableUntil has passed, treat as expired
    if (!authorization.onlineRequired && authorization.offlineUsableUntil
      && new Date(authorization.offlineUsableUntil).getTime() <= Date.now()) {
      return null
    }
    return authorization
  }

  function hasValidOfflineClientAuthorization(): boolean {
    const authorization = clientAuthorization.value
    if (!authorization || authorization.onlineRequired || !authorization.offlineUsableUntil) {
      return false
    }
    return new Date(authorization.offlineUsableUntil).getTime() > Date.now()
  }

  function resolveModuleAccess(
    moduleCode: ModuleCode,
    authenticatedModules: ModuleAccess[],
    anonymousModules: ModuleAccess[]
  ): { allowed: boolean; reason: string | null } {
    const authenticated = authenticatedModules.find(module => module.moduleCode === moduleCode)
    const anonymous = anonymousModules.find(module => module.moduleCode === moduleCode)
    if (authenticated?.allowed) {
      return { allowed: true, reason: authenticated.reason }
    }
    if (anonymous?.allowed) {
      return { allowed: true, reason: anonymous.reason }
    }
    if (authenticated) {
      return { allowed: false, reason: authenticated.reason }
    }
    return { allowed: anonymous?.allowed ?? false, reason: anonymous?.reason ?? null }
  }

  function isModuleAllowed(moduleCode: ModuleCode): boolean {
    if (usingOfflineClientAuthorization.value) {
      // 离线模式下优先使用 Rust 层签名校验结果；若 Rust 调用失败或缓存未设置，回退到内存权限
      return offlineAccessCache.value.get(moduleCode) ?? moduleAccess.value.get(moduleCode)?.allowed ?? false
    }
    return moduleAccess.value.get(moduleCode)?.allowed ?? false
  }

  function denialReason(moduleCode: ModuleCode): string | null {
    return moduleAccess.value.get(moduleCode)?.reason ?? null
  }

  return {
    deviceIdentity,
    trialStatus,
    anonymousAuthorization,
    clientDevice,
    clientAuthorization,
    usingOfflineClientAuthorization,
    offlineAccessCache,
    loading,
    error,
    initializeAnonymous,
    initializeAuthenticated,
    selectFreeModule,
    changeFreeModule,
    refreshAnonymousAuthorization,
    refreshAuthenticatedAuthorization,
    isModuleAllowed,
    denialReason
  }
})
