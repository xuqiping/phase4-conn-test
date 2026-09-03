// src/api/__tests__/files.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  deleteManagedShortcut,
  importFavoritePath,
  pickFile,
  pickFolder,
  validateFavoritePath
} from '../files'
import { open } from '@tauri-apps/plugin-dialog'
import { invoke } from '@tauri-apps/api/core'

vi.mock('@tauri-apps/plugin-dialog', () => ({
  open: vi.fn()
}))

vi.mock('@tauri-apps/api/path', () => ({
  documentDir: vi.fn(() => Promise.resolve('C:\\Users\\Test\\Documents'))
}))

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn()
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
        directory: false,
        multiple: false,
        defaultPath: 'C:\\Users\\Test\\Documents'
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
        directory: true,
        defaultPath: expect.any(String)
      })
    })

    it('should use custom default path when picking a folder', async () => {
      vi.mocked(open).mockResolvedValue('/path/to/folder')

      await pickFolder('D:/ClipboardBackup')

      expect(open).toHaveBeenCalledWith({
        multiple: false,
        directory: true,
        defaultPath: 'D:/ClipboardBackup'
      })
    })

    it('should return null when user cancels', async () => {
      vi.mocked(open).mockResolvedValue(null)

      const result = await pickFolder()

      expect(result).toBeNull()
  })
  })

  it('wraps managed favorite path commands without changing paths', async () => {
    const descriptor = {
      name: 'Report.lnk',
      path: 'C:/AppData/managed-shortcuts/id.lnk',
      sourcePath: 'C:/Desktop/Report.lnk',
      itemType: 'file' as const,
      shortcutTargetPath: 'C:/Docs/Report.xlsx',
      managedArtifact: {
        kind: 'windows-shortcut-copy' as const,
        cachePath: 'C:/AppData/managed-shortcuts/id.lnk',
        originalPath: 'C:/Desktop/Report.lnk'
      }
    }
    vi.mocked(invoke).mockResolvedValueOnce(descriptor).mockResolvedValueOnce(true)

    await expect(importFavoritePath('C:/Desktop/Report.lnk')).resolves.toEqual(descriptor)
    await expect(validateFavoritePath(descriptor.path, descriptor.shortcutTargetPath)).resolves.toBe(true)
    await deleteManagedShortcut(descriptor.managedArtifact.cachePath)

    expect(invoke).toHaveBeenNthCalledWith(1, 'import_favorite_path', { path: 'C:/Desktop/Report.lnk' })
    expect(invoke).toHaveBeenNthCalledWith(2, 'validate_favorite_path', {
      path: descriptor.path,
      shortcutTargetPath: descriptor.shortcutTargetPath
    })
    expect(invoke).toHaveBeenNthCalledWith(3, 'delete_managed_shortcut', {
      cachePath: descriptor.managedArtifact.cachePath
    })
  })
})
