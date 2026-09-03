import { describe, expect, it } from 'vitest'
import { normalizeProcessSettings, PROCESS_SETTINGS_VERSION } from '../processSettingsStore'
import type { ProcessSettings } from '../../types/process'

const legacySettings: ProcessSettings = {
  columns: [
    { key: 'name', label: 'Old Name', width: '160px', visible: true, sortable: true },
    { key: 'windowTitle', label: 'Window Title', width: '180px', visible: false, sortable: false },
    { key: 'runtime', label: 'Runtime', width: '100px', visible: true, sortable: true },
    { key: 'path', label: 'Path', width: '300px', visible: true, sortable: false }
  ],
  autoRefresh: true,
  refreshInterval: 3000,
  confirmMode: 'always',
  whitelist: []
}

describe('process settings migration', () => {
  it('merges legacy columns by key, removes unsupported columns and enables window titles once', () => {
    const migrated = normalizeProcessSettings(legacySettings)

    expect(migrated.version).toBe(PROCESS_SETTINGS_VERSION)
    expect(migrated.columns.map(column => column.key)).toEqual([
      'name', 'category', 'pid', 'memory', 'cpu', 'windowTitle'
    ])
    expect(migrated.columns.find(column => column.key === 'name')?.width).toBe('160px')
    expect(migrated.columns.find(column => column.key === 'windowTitle')).toMatchObject({
      visible: true,
      sortable: true
    })
  })

  it('preserves a user-hidden window title after the migration version is current', () => {
    const current = normalizeProcessSettings({
      ...legacySettings,
      version: PROCESS_SETTINGS_VERSION,
      columns: legacySettings.columns.map(column =>
        column.key === 'windowTitle' ? { ...column, visible: false } : column
      )
    })

    expect(current.columns.find(column => column.key === 'windowTitle')?.visible).toBe(false)
  })
})
