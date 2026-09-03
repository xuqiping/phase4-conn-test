import { describe, expect, it } from 'vitest'
import { normalizeSettings } from '../settingsStore'

describe('settings migration', () => {
  it('migrates the legacy tray preference to tray close behavior', () => {
    const settings = normalizeSettings({ minimizeToTray: true })

    expect(settings.closeBehavior).toBe('tray')
    expect(settings).not.toHaveProperty('minimizeToTray')
  })

  it('migrates the legacy exit preference to the new floating ball default', () => {
    const settings = normalizeSettings({ minimizeToTray: false })

    expect(settings.closeBehavior).toBe('floating_ball')
  })

  it('keeps an explicitly saved new close behavior', () => {
    const settings = normalizeSettings({
      minimizeToTray: true,
      closeBehavior: 'exit'
    })

    expect(settings.closeBehavior).toBe('exit')
  })

  it('ignores an invalid persisted close behavior', () => {
    const settings = normalizeSettings({ closeBehavior: 'hidden' })

    expect(settings.closeBehavior).toBe('floating_ball')
  })
})
