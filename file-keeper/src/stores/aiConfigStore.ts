import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useAuthStore } from './authStore'
import { useCommercialAuthStore } from './commercialAuthStore'
import * as api from '@/api/aiConfig'
import type { AiConfig, AiConfigForm } from '@/types/aiConfig'

const COMMERCIAL_SERVER_URL = import.meta.env.VITE_FILE_KEEPER_SERVER_URL || 'http://localhost:8088'

export const useAiConfigStore = defineStore('ai-config', () => {
  const configs = ref<AiConfig[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  const defaultConfig = computed(() => configs.value.find(c => c.isDefault && c.enabled))

  function getAuthContext() {
    const authStore = useAuthStore()
    const commercialStore = useCommercialAuthStore()
    const token = authStore.accessToken
    const deviceIdentity = commercialStore.deviceIdentity
    if (!token) {
      throw new Error('未登录')
    }
    if (!deviceIdentity) {
      throw new Error('未获取设备身份')
    }
    return { baseUrl: COMMERCIAL_SERVER_URL, token, deviceId: deviceIdentity.deviceId }
  }

  async function loadConfigs() {
    const { baseUrl, token, deviceId } = getAuthContext()
    loading.value = true
    error.value = null
    try {
      configs.value = await api.listAiConfigs(baseUrl, token, deviceId)
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  async function saveConfig(id: number | undefined, config: AiConfigForm): Promise<AiConfig> {
    const { baseUrl, token, deviceId } = getAuthContext()
    loading.value = true
    error.value = null
    try {
      const saved = id
        ? await api.updateAiConfig(baseUrl, token, deviceId, id, config)
        : await api.createAiConfig(baseUrl, token, deviceId, config)
      await loadConfigs()
      return saved
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  async function deleteConfig(id: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    loading.value = true
    error.value = null
    try {
      await api.deleteAiConfig(baseUrl, token, deviceId, id)
      await loadConfigs()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  async function setDefault(id: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    loading.value = true
    error.value = null
    try {
      await api.setDefaultAiConfig(baseUrl, token, deviceId, id)
      await loadConfigs()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  async function testConfig(config: AiConfigForm): Promise<string> {
    const { baseUrl, token, deviceId } = getAuthContext()
    loading.value = true
    error.value = null
    try {
      const reply = await api.testAiConfig(baseUrl, token, deviceId, config)
      return reply
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  return {
    configs,
    loading,
    error,
    defaultConfig,
    loadConfigs,
    saveConfig,
    deleteConfig,
    setDefault,
    testConfig,
  }
})
