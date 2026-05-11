import { Store } from '@tauri-apps/plugin-store'

export interface PersistAPI {
  load<T>(key: string, defaultValue: T): Promise<T>
  save<T>(key: string, value: T): Promise<void>
  flush(): Promise<void>
}

export interface PersistOptions {
  autoSaveDebounceMs?: number
}

export async function createPersistAPI(
  path: string,
  options: PersistOptions = {}
): Promise<PersistAPI> {
  const { autoSaveDebounceMs = 500 } = options

  const store = await Store.load(path, {
    defaults: {},
    autoSave: autoSaveDebounceMs
  })

  return {
    async load<T>(key: string, defaultValue: T): Promise<T> {
      try {
        const value = await store.get<T>(key)
        return value !== undefined ? value : defaultValue
      } catch (error) {
        console.error(`[PersistAPI] 加载 "${key}" 失败:`, error)
        return defaultValue
      }
    },

    async save<T>(key: string, value: T): Promise<void> {
      try {
        await store.set(key, value)
      } catch (error) {
        console.error(`[PersistAPI] 保存 "${key}" 失败:`, error)
        throw error
      }
    },

    async flush(): Promise<void> {
      try {
        await store.save()
      } catch (error) {
        console.error('[PersistAPI] flush 失败:', error)
        throw error
      }
    }
  }
}
