// src/stores/__tests__/fileStore.test.ts
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useFileStore } from '../fileStore'

describe('fileStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })
  describe('addFile', () => {
    it('should add a new file to the store', () => {
      const store = useFileStore()

      const newFile = store.addFile({
        name: 'test.txt',
        path: '/path/to/test.txt',
        type: 'file',
        tags: [],
      groupId: 'all'
      })

      expect(newFile).toBeDefined()
      expect(newFile.id).toBeDefined()
      expect(newFile.name).toBe('test.txt')
      expect(newFile.openCount).toBe(0)
    expect(newFile.createdAt).toBeDefined()
      expect(store.files).toHaveLength(7) // 6 mock + 1 new
    })

    it('should not add duplicate files with same path', () => {
      const store = useFileStore()

      store.addFile({
        name: 'test.txt',
        path: '/path/to/test.txt',
        type: 'file',
        tags: [],
        groupId: 'all'
      })

      const result = store.addFile({
        name: 'test.txt',
     path: '/path/to/test.txt',
        type: 'file',
        tags: [],
        groupId: 'all'
      })

      expect(result).toBeNull()
    expect(store.files).toHaveLength(7) // 6 mock + 1 new (not 8)
    })
  })
})
