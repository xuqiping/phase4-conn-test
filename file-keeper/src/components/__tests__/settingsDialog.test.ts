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

  it('always shows the AI tab but renders a login prompt without loading AI config when logged out', async () => {
    setActivePinia(createPinia())
    const wrapper = mount(SettingsDialog, {
      props: { show: true },
      global: {
        stubs: {
          AiConfigSettings: { template: '<div data-test="ai-config-settings" />' }
        }
      }
    })

    const aiTab = wrapper.findAll('button').find(button => button.text().includes('AI 模型'))
    expect(aiTab).toBeDefined()
    await aiTab!.trigger('click')

    expect(wrapper.find('[data-test="ai-config-settings"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="ai-login-prompt"]').exists()).toBe(true)
    const loginButton = wrapper.get('[data-test="ai-login-button"]')
    expect(loginButton.element.tagName).toBe('BUTTON')
    expect(loginButton.text()).toContain('登录')
  })
})
