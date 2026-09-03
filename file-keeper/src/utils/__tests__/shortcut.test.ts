import { describe, expect, it } from 'vitest'
import { findShortcutConflict, normalizeShortcut } from '../shortcut'

describe('normalizeShortcut', () => {
  it('normalizes modifier aliases and ordering', () => {
    expect(normalizeShortcut(' shift + ctrl + k ')).toBe('CommandOrControl+Shift+K')
    expect(normalizeShortcut('Cmd+Alt+p')).toBe('CommandOrControl+Alt+P')
  })

  it('rejects shortcuts without both a modifier and a key', () => {
    expect(normalizeShortcut('Shift')).toBe('')
    expect(normalizeShortcut('K')).toBe('')
  })
})

describe('findShortcutConflict', () => {
  const settings = {
    globalShortcut: 'CommandOrControl+Alt+K',
    clipboardShortcut: 'CommandOrControl+Shift+V',
    screenshotShortcut: 'CommandOrControl+Shift+X'
  }
  const files = [
    { id: 'file-1', name: '季度计划', shortcut: 'Ctrl+Alt+P' },
    { id: 'file-2', name: '预算表', shortcut: 'Ctrl+Alt+B' }
  ]

  it('detects conflicts with application shortcuts', () => {
    expect(findShortcutConflict('ctrl + alt + k', { settings, files })).toEqual({
      kind: 'application',
      id: 'main',
      label: '主窗口'
    })
  })

  it('detects conflicts with another favorite and supports excluding the edited item', () => {
    expect(findShortcutConflict('CommandOrControl+Alt+P', { settings, files, excludeFileId: 'file-2' })).toEqual({
      kind: 'favorite',
      id: 'file-1',
      label: '季度计划'
    })
    expect(findShortcutConflict('CommandOrControl+Alt+P', { settings, files, excludeFileId: 'file-1' })).toBeNull()
  })

  it('can exclude the application shortcut currently being edited', () => {
    expect(findShortcutConflict('ctrl + alt + k', {
      settings,
      files,
      excludeApplicationId: 'main'
    })).toBeNull()
    expect(findShortcutConflict('ctrl + shift + v', {
      settings,
      files,
      excludeApplicationId: 'main'
    })).toEqual({
      kind: 'application',
      id: 'clipboard',
      label: '剪贴板面板'
    })
  })
})
