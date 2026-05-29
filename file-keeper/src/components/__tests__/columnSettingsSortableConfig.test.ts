import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ColumnSettings from '../ColumnSettings.vue'
import { getColumnSettingsSortableOptions } from '../processColumns'

const { createMock, destroyMock } = vi.hoisted(() => ({
  createMock: vi.fn(),
  destroyMock: vi.fn()
}))

vi.mock('sortablejs', () => ({
  default: {
    create: createMock
  }
}))

describe('column settings sortable config', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    destroyMock.mockReset()
    createMock.mockReset()
    createMock.mockReturnValue({
      destroy: destroyMock
    })
  })

  it('enables fallback drag behavior for the column settings dialog', () => {
    const options = getColumnSettingsSortableOptions(() => {})

    expect(options.handle).toBe('.drag-handle')
    expect(options.forceFallback).toBe(true)
    expect(options.fallbackOnBody).toBe(true)
    expect(options.fallbackClass).toBe('sortable-fallback')
    expect(options.chosenClass).toBe('sortable-chosen')
    expect(options.ghostClass).toBe('sortable-ghost')
    expect(options.dragClass).toBe('sortable-drag')
  })

  it('uses the shared sortable fallback config in the dialog component', async () => {
    mount(ColumnSettings)
    await nextTick()

    expect(createMock).toHaveBeenCalledTimes(1)

    const options = createMock.mock.calls[0][1]
    expect(options.handle).toBe('.drag-handle')
    expect(options.forceFallback).toBe(true)
    expect(options.fallbackOnBody).toBe(true)
    expect(options.fallbackClass).toBe('sortable-fallback')
    expect(options.chosenClass).toBe('sortable-chosen')
    expect(options.ghostClass).toBe('sortable-ghost')
    expect(options.dragClass).toBe('sortable-drag')
  })
})
