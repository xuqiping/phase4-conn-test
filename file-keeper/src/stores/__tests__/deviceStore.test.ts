import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as deviceApi from '@/api/device'
import { useDeviceStore } from '../deviceStore'

const mocks = vi.hoisted(() => ({
  getOrCreateDeviceIdentity: vi.fn(),
  registerClientDevice: vi.fn()
}))

vi.mock('@/api/device', () => ({
  getOrCreateDeviceIdentity: mocks.getOrCreateDeviceIdentity,
  registerClientDevice: mocks.registerClientDevice
}))

const identity = {
  deviceId: 'device-001',
  fingerprintHash: 'fingerprint-001',
  deviceName: 'Laptop'
}

describe('deviceStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mocks.getOrCreateDeviceIdentity.mockResolvedValue(identity)
  })

  it('creates the identity once and returns the cached value on repeated reads', async () => {
    const store = useDeviceStore()

    const first = await store.ensureIdentity()
    const second = await store.ensureIdentity()

    expect(first).toEqual(identity)
    expect(second).toEqual(first)
    expect(deviceApi.getOrCreateDeviceIdentity).toHaveBeenCalledTimes(1)
    expect(store.deviceId).toBe('device-001')
  })

  it('registers or heartbeats the authenticated device after ensuring identity', async () => {
    const clientDevice = {
      id: 1,
      userId: 10,
      ...identity,
      status: 'active',
      lastSeenAt: '2026-08-24T00:00:00Z'
    }
    mocks.registerClientDevice.mockResolvedValueOnce(clientDevice)
    const store = useDeviceStore()

    const result = await store.registerAuthenticatedDevice('http://localhost:8080', 'access-token')

    expect(deviceApi.registerClientDevice).toHaveBeenCalledWith(
      'http://localhost:8080',
      'access-token',
      identity
    )
    expect(result).toEqual(clientDevice)
    expect(store.clientDevice).toEqual(clientDevice)
  })

  it('stores and rethrows device-disabled registration errors', async () => {
    mocks.registerClientDevice.mockRejectedValueOnce(new Error('设备已禁用'))
    const store = useDeviceStore()

    await expect(store.registerAuthenticatedDevice('http://localhost:8080', 'access-token'))
      .rejects.toThrow('设备已禁用')

    expect(store.error).toBe('设备已禁用')
    expect(store.clientDevice).toBeNull()
    expect(store.loading).toBe(false)
  })
})
