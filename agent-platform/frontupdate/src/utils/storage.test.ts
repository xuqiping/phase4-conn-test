import { describe, it, expect, beforeEach } from 'vitest'
import { getStorage, setStorage, removeStorage, clearAuthStorage, STORAGE_KEYS } from './storage'

describe('storage utils', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('setStorage + getStorage roundtrip', () => {
    setStorage('test_key', { name: 'hello' })
    expect(getStorage<{ name: string }>('test_key')).toEqual({ name: 'hello' })
  })

  it('getStorage returns null for missing key', () => {
    expect(getStorage('nonexistent')).toBeNull()
  })

  it('getStorage returns null for invalid JSON', () => {
    localStorage.setItem('bad', 'not-json')
    expect(getStorage('bad')).toBeNull()
  })

  it('removeStorage removes the key', () => {
    setStorage('k', 'v')
    removeStorage('k')
    expect(getStorage('k')).toBeNull()
  })

  it('clearAuthStorage removes all auth keys', () => {
    setStorage(STORAGE_KEYS.ACCESS_TOKEN, 'at')
    setStorage(STORAGE_KEYS.REFRESH_TOKEN, 'rt')
    setStorage(STORAGE_KEYS.USER_INFO, { id: 1 })
    clearAuthStorage()
    expect(getStorage(STORAGE_KEYS.ACCESS_TOKEN)).toBeNull()
    expect(getStorage(STORAGE_KEYS.REFRESH_TOKEN)).toBeNull()
    expect(getStorage(STORAGE_KEYS.USER_INFO)).toBeNull()
  })

  it('setStorage handles primitives', () => {
    setStorage('num', 42)
    expect(getStorage('num')).toBe(42)
    setStorage('bool', true)
    expect(getStorage('bool')).toBe(true)
  })
})
