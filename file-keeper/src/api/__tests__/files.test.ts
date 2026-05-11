// src/api/__tests__/files.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { pickFile, pickFolder } from '../files'
import { open } from '@tauri-apps/plugin-dialog'

vi.mock('@tauri-apps/plugin-dialog', () => ({
  open: vi.fn()
}))

describe('files API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('pickFile', () => {
    it('should return file path when user selects a file', async () => {
      vi.mocked(open).mockResolvedValue('/path/to/file.txt')

      const result = await pickFile()

      expect(result).toBe('/path/to/file.txt')
      expect(open).toHaveBeenCalledWith({
        multiple: false,
        directory: false
      })
    })

    it('should return null when user cancels', async () => {
      vi.mocked(open).mockResolvedValue(null)

      const result = await pickFile()

      expect(result).toBeNull()
    })
  })

  describe('pickFolder', () => {
    it('should return folder path when user selects a folder', async () => {
      vi.mocked(open).mockResolvedValue('/path/to/folder')

      const result = await pickFolder()

      expect(result).toBe('/path/to/folder')
      expect(open).toHaveBeenCalledWith({
        multiple: false,
        directory: true
      })
    })

    it('should return null when user cancels', async () => {
      vi.mocked(open).mockResolvedValue(null)

      const result = await pickFolder()

      expect(result).toBeNull()
    })
  })
})
