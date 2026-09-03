import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SettingsDialog from '../SettingsDialog.vue'
import { useSettingsStore } from '../../stores/settingsStore'
import { useFileStore } from '../../stores/fileStore'

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

  it('saves one of the three mutually exclusive close behaviors', async () => {
    setActivePinia(createPinia())
    const wrapper = mount(SettingsDialog, { props: { show: true } })

    await wrapper.get('[data-test="close-behavior-tray"]').setValue()
    await wrapper.get('[data-test="save-settings"]').trigger('click')

    const payload = wrapper.emitted('save')?.[0]?.[0] as any
    expect(payload.closeBehavior).toBe('tray')
    expect(payload).not.toHaveProperty('minimizeToTray')
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

  it('rejects application shortcuts that conflict with a favorite', async () => {
    setActivePinia(createPinia())
    const fileStore = useFileStore()
    fileStore.files = [{
      id: 'favorite-1',
      name: '财务表',
      path: 'C:/finance.xlsx',
      type: 'file',
      icon: 'excel',
      tags: [],
      groupId: 'all',
      openCount: 0,
      createdAt: 1,
      shortcut: 'CommandOrControl+Alt+F'
    }]
    const wrapper = mount(SettingsDialog, { props: { show: true } })

    await wrapper.get('[data-test="main-shortcut"]').setValue('Ctrl+Alt+F')
    await wrapper.get('[data-test="save-settings"]').trigger('click')

    expect(wrapper.emitted('save')).toBeUndefined()
    expect(wrapper.get('[role="alert"]').text()).toContain('财务表')
  })

  it('rejects conflicts among the three application shortcuts', async () => {
    setActivePinia(createPinia())
    const wrapper = mount(SettingsDialog, { props: { show: true } })

    await wrapper.get('[data-test="main-shortcut"]').setValue('Ctrl+Shift+V')
    await wrapper.get('[data-test="save-settings"]').trigger('click')

    expect(wrapper.emitted('save')).toBeUndefined()
    expect(wrapper.get('[role="alert"]').text()).toContain('剪贴板面板')
  })
})
