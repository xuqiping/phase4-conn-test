import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SettingsDialog from '../SettingsDialog.vue'
import { useSettingsStore } from '../../stores/settingsStore'

describe('SettingsDialog', () => {
  it('renders and saves screenshot shortcut', async () => {
    setActivePinia(createPinia())
    const settingsStore = useSettingsStore()
    settingsStore.updateSettings({ screenshotShortcut: 'CommandOrControl+Shift+X' })
    const wrapper = mount(SettingsDialog, { props: { show: true } })

    expect(wrapper.text()).toContain('截图快捷键')
    const input = wrapper.get('[data-test="screenshot-shortcut"]')
    expect((input.element as HTMLInputElement).value).toBe('CommandOrControl+Shift+X')

    await input.setValue('CommandOrControl+Alt+S')
    await wrapper.get('[data-test="save-settings"]').trigger('click')

    const payload = wrapper.emitted('save')?.[0]?.[0] as any
    expect(payload.screenshotShortcut).toBe('CommandOrControl+Alt+S')
  })
})
