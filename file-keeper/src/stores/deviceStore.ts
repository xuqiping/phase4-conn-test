import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as deviceApi from '@/api/device'
import type { ClientDevice, DeviceIdentity } from '@/api/device'

export const useDeviceStore = defineStore('device', () => {
  const identity = ref<DeviceIdentity | null>(null)
  const clientDevice = ref<ClientDevice | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const deviceId = computed(() => identity.value?.deviceId ?? null)

  let identityPromise: Promise<DeviceIdentity> | null = null

  async function ensureIdentity(): Promise<DeviceIdentity> {
    if (identity.value) {
      return identity.value
    }
    if (identityPromise) {
      return await identityPromise
    }

    identityPromise = (async () => {
      try {
        const resolved = await deviceApi.getOrCreateDeviceIdentity()
        identity.value = resolved
        return resolved
      } finally {
        identityPromise = null
      }
    })()
    return await identityPromise
  }

  async function registerAuthenticatedDevice(baseUrl: string, accessToken: string): Promise<ClientDevice> {
    if (!accessToken) {
      throw new Error('未登录')
    }
    loading.value = true
    error.value = null
    try {
      const currentIdentity = await ensureIdentity()
      const registered = await deviceApi.registerClientDevice(baseUrl, accessToken, currentIdentity)
      clientDevice.value = registered
      return registered
    } catch (err) {
      clientDevice.value = null
      error.value = errorMessage(err)
      throw err
    } finally {
      loading.value = false
    }
  }

  return {
    identity,
    clientDevice,
    loading,
    error,
    deviceId,
    ensureIdentity,
    registerAuthenticatedDevice
  }
})

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
