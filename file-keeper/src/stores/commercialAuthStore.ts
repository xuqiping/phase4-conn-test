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

const MODULE_CODES: ModuleCode[] = ['files', 'processes', 'clipboard', 'work-report', 'ai']

export const useCommercialAuthStore = defineStore('commercialAuth', () => {
  const deviceIdentity = ref<DeviceIdentity | null>(null)
  const trialStatus = ref<AnonymousTrialStatus | null>(null)
  const anonymousAuthorization = ref<AnonymousAuthorizationSnapshot | null>(null)
  const clientDevice = ref<ClientDevice | null>(null)
  const clientAuthorization = ref<ClientAuthorizationSnapshot | null>(null)
  const entitlementAccessCache = ref(new Map<ModuleCode, boolean>())
  const loading = ref(false)
  const error = ref<string | null>(null)

  const moduleAccess = computed(() => {
    const access = new Map<ModuleCode, { allowed: boolean; reason: string | null }>()
    const authenticatedModules = clientAuthorization.value?.modules ?? []
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
      await syncEntitlementToRust()
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
      await syncEntitlementToRust()
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
      // 服务端明确拒绝（账号禁用、设备解绑等）：清空客户端授权与 Rust 凭据
      clientAuthorization.value = null
      clientDevice.value = null
      entitlementAccessCache.value.clear()
      void clearEntitlementInRust()
    }
    // 网络抖动等瞬时错误保留已有 Rust 凭据，用户可在 token 有效期内继续离线使用
  }

  /**
   * 将服务端签名的授权凭据同步给 Rust 侧。
   * 在线/离线统一处理：只要快照包含 signed entitlement 就同步，
   * 有效期与模块权限由 Rust 通过 Ed25519 公钥验签和 notAfter 强校验决定。
   *
   * 兼容旧版 HMAC 离线 token：若检测到旧格式（无 payload/signature 分隔符 `.`），
   * 直接清空 Rust 凭据并抛错，触发调用方在线刷新一次新的 Ed25519 signed entitlement。
   */
  async function syncEntitlementToRust() {
    entitlementAccessCache.value.clear()
    const token = clientAuthorization.value?.offlineToken
    if (!token) {
      await clearEntitlementInRust()
      return
    }
    if (isLegacyHmacToken(token)) {
      console.warn('[commercialAuthStore] 检测到旧版 HMAC 离线 token，清空 Rust 凭据并需要在线刷新')
      await clearEntitlementInRust()
      clientAuthorization.value = null
      throw new Error('授权凭据已升级，请联网刷新一次')
    }
    try {
      await invoke('set_signed_entitlement', { token })
      await refreshEntitlementAccessCache()
    } catch (e) {
      console.error('[commercialAuthStore] set_signed_entitlement failed:', e)
      await clearEntitlementInRust()
    }
  }

  /**
   * 判断 token 是否为旧版 HMAC 离线 token。
   * 旧版格式：base64url(userId|deviceId|offlineUsableUntil|modules|hmac)，无 `.` 分隔符；
   * 新版 signed entitlement：base64url(payload).base64url(signature)，有且仅有一个 `.`。
   */
  function isLegacyHmacToken(token: string): boolean {
    return !token.includes('.')
  }

  async function refreshEntitlementAccessCache() {
    const cache = new Map<ModuleCode, boolean>()
    for (const moduleCode of MODULE_CODES) {
      try {
        const result = await invoke<{ allowed: boolean; reason: string }>('check_signed_entitlement_access', { moduleCode })
        cache.set(moduleCode, result.allowed)
      } catch {
        cache.set(moduleCode, false)
      }
    }
    entitlementAccessCache.value = cache
  }

  async function clearEntitlementInRust() {
    entitlementAccessCache.value.clear()
    try {
      await invoke('clear_signed_entitlement')
    } catch (e) {
      console.error('[commercialAuthStore] clear_signed_entitlement failed:', e)
    }
  }

  function getUsableClientAuthorization(): ClientAuthorizationSnapshot | null {
    return clientAuthorization.value
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

  /**
   * 判断模块是否可用。
   * 优先使用 Rust 侧对签名凭据的校验结果（在线/离线一致）；
   * 无凭据时回退到内存中的模块授权快照（匿名/在线只读态）。
   */
  function isModuleAllowed(moduleCode: ModuleCode): boolean {
    if (entitlementAccessCache.value.has(moduleCode)) {
      return entitlementAccessCache.value.get(moduleCode)!
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
    entitlementAccessCache,
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
