export interface ShortcutConflict {
  kind: 'application' | 'favorite'
  id: string
  label: string
}

export interface ShortcutConflictContext {
  settings: {
    globalShortcut: string
    clipboardShortcut: string
    screenshotShortcut: string
  }
  files: Array<{ id: string; name: string; shortcut?: string }>
  excludeFileId?: string
  excludeApplicationId?: 'main' | 'clipboard' | 'screenshot'
}

export function normalizeShortcut(shortcut: string): string {
  const parts = shortcut
    .split('+')
    .map(part => part.trim())
    .filter(Boolean)
  const modifiers = new Set<string>()
  let key = ''

  for (const part of parts) {
    const lower = part.toLowerCase()
    if (['ctrl', 'control', 'cmd', 'command', 'meta', 'commandorcontrol'].includes(lower)) {
      modifiers.add('CommandOrControl')
    } else if (['alt', 'option'].includes(lower)) {
      modifiers.add('Alt')
    } else if (lower === 'shift') {
      modifiers.add('Shift')
    } else if (['super', 'win', 'windows'].includes(lower)) {
      modifiers.add('Super')
    } else {
      key = part.length === 1 ? part.toUpperCase() : part
    }
  }

  if (!key || modifiers.size === 0) return ''
  const orderedModifiers = ['CommandOrControl', 'Alt', 'Shift', 'Super']
    .filter(modifier => modifiers.has(modifier))
  return [...orderedModifiers, key].join('+')
}

export function findShortcutConflict(
  shortcut: string,
  context: ShortcutConflictContext
): ShortcutConflict | null {
  const normalized = normalizeShortcut(shortcut)
  if (!normalized) return null
  const applicationShortcuts = [
    ['main', '主窗口', context.settings.globalShortcut],
    ['clipboard', '剪贴板面板', context.settings.clipboardShortcut],
    ['screenshot', '截图', context.settings.screenshotShortcut]
  ] as const
  for (const [id, label, configured] of applicationShortcuts) {
    if (id === context.excludeApplicationId) continue
    if (normalizeShortcut(configured) === normalized) {
      return { kind: 'application', id, label }
    }
  }
  for (const file of context.files) {
    if (file.id === context.excludeFileId) continue
    if (file.shortcut && normalizeShortcut(file.shortcut) === normalized) {
      return { kind: 'favorite', id: file.id, label: file.name }
    }
  }
  return null
}
