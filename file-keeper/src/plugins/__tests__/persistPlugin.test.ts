import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createApp } from 'vue'
import { createPinia, defineStore, setActivePinia } from 'pinia'

const mockSave = vi.fn()
const mockFlush = vi.fn()
const mockLoad = vi.fn()

vi.mock('../../api/persist', () => ({
  createPersistAPI: vi.fn(() => Promise.resolve({
    load: mockLoad,
    save: mockSave,
    flush: mockFlush
  }))
}))

import { createPersistPlugin } from '../persistPlugin'

function setupPinia() {
  const app = createApp({})
  const pinia = createPinia()
  app.use(pinia)
  pinia.use(createPersistPlugin())
  setActivePinia(pinia)
  return pinia
}

describe('createPersistPlugin', () => {
  beforeEach(() => {
    mockSave.mockClear()
    mockFlush.mockClear()
    mockLoad.mockClear()
    mockLoad.mockResolvedValue(undefined)
    mockSave.mockResolvedValue(undefined)
    mockFlush.mockResolvedValue(undefined)
  })

  it('未配置 persist 选项的 store 不应触发持久化', async () => {
    setupPinia()

    const useStore = defineStore('plain', {
      state: () => ({ count: 0 }),
      actions: {
        increment() { this.count++ }
      }
    })
    const store = useStore()
    store.increment()

    await new Promise(resolve => setTimeout(resolve, 50))
    expect(mockSave).not.toHaveBeenCalled()
  })

  it('配置了 persist 的 store 在变化时调用 save', async () => {
    setupPinia()

    const useStore = defineStore('test', {
      state: () => ({ count: 0 }),
      actions: {
        increment() { this.count++ }
      },
      persist: { key: 'test' }
    })

    const store = useStore()
    await store.$persistReady
    store.increment()

    await new Promise(resolve => setTimeout(resolve, 50))
    expect(mockSave).toHaveBeenCalled()
  })

  it('action 在 importantActions 列表中时调用 flush 立即落盘', async () => {
    setupPinia()

    const useStore = defineStore('test2', {
      state: () => ({ items: [] as string[] }),
      actions: {
        addItem(s: string) { this.items.push(s) },
        touchItem() { /* noop */ }
      },
      persist: {
        key: 'test2',
        importantActions: ['addItem']
      }
    })

    const store = useStore()
    await store.$persistReady
    store.addItem('hello')

    await new Promise(resolve => setTimeout(resolve, 50))
    expect(mockFlush).toHaveBeenCalled()
  })

  it('启动时从存储加载数据到 store state', async () => {
    mockLoad.mockResolvedValueOnce({ count: 42 })
    setupPinia()

    const useStore = defineStore('test3', {
      state: () => ({ count: 0 }),
      persist: { key: 'test3' }
    })

    const store = useStore()
    await store.$persistReady
    expect(store.count).toBe(42)
  })
})
