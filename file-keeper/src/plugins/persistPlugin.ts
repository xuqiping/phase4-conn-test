import type { PiniaPlugin, PiniaPluginContext } from 'pinia'
import { createPersistAPI, type PersistAPI } from '../api/persist'

export interface StorePersistOptions {
  key: string
  path?: string
  paths?: string[]
  importantActions?: string[]
  debounceMs?: number
}

declare module 'pinia' {
  export interface DefineStoreOptionsBase<S, Store> {
    persist?: StorePersistOptions
  }
  export interface PiniaCustomProperties {
    $persistReady: Promise<void>
  }
}

const apiCache = new Map<string, Promise<PersistAPI>>()

function getAPI(path: string, debounceMs: number): Promise<PersistAPI> {
  if (!apiCache.has(path)) {
    apiCache.set(path, createPersistAPI(path, { autoSaveDebounceMs: debounceMs }))
  }
  return apiCache.get(path)!
}

function pickPaths<T extends object>(state: T, paths?: string[]): Partial<T> {
  if (!paths || paths.length === 0) return state
  const result: Partial<T> = {}
  for (const key of paths) {
    if (key in state) {
      ;(result as any)[key] = (state as any)[key]
    }
  }
  return result
}

export function createPersistPlugin(): PiniaPlugin {
  return ({ store, options }: PiniaPluginContext) => {
    const persist = options.persist
    if (!persist) return

    const {
      key,
      path = 'file-keeper-data.json',
      paths,
      importantActions = [],
      debounceMs = 500
    } = persist

    let api: PersistAPI | null = null
    let loaded = false

    const ready = (async () => {
      api = await getAPI(path, debounceMs)
      const stored = await api.load<Partial<typeof store.$state>>(key, {} as any)
      if (stored && typeof stored === 'object' && Object.keys(stored).length > 0) {
        store.$patch(stored)
      }
      loaded = true
    })()

    store.$persistReady = ready

    store.$subscribe((_mutation, state) => {
      if (!loaded || !api) return
      const dataToSave = pickPaths(state, paths)
      api.save(key, dataToSave).catch(err => {
        console.error(`[persistPlugin] save 失败 (${key}):`, err)
      })
    })

    store.$onAction(({ name, after }) => {
      if (!importantActions.includes(name)) return
      after(() => {
        if (!loaded || !api) return
        api.flush().catch(err => {
          console.error(`[persistPlugin] flush 失败 (${key}):`, err)
        })
      })
    })
  }
}
