import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ClipboardSettings from '../ClipboardSettings.vue'
import type { ClipboardSettings as ClipboardSettingsType } from '../../types/clipboard'

function settings(): ClipboardSettingsType {
  return {
    monitorEnabled: true,
    quickPanelShortcut: 'CommandOrControl+Shift+V',
    autoPaste: false,
    protectSensitiveContent: true,
    enableOcr: true,
    enableLinkPreview: false,
    totalNonTextLimitMb: 2048,
    itemSizeLimitMb: 200,
    typeLimitsMb: { image: 1024, file: 2048, html: 500, linkPreview: 200 },
    fileExtensionMode: 'allow_all',
    fileExtensions: [],
    excludedApps: []
  }
}

describe('ClipboardSettings', () => {
  it('renders storage and safety controls', () => {
    const wrapper = mount(ClipboardSettings, { props: { settings: settings() } })

    expect(wrapper.text()).toContain('安全防护')
    expect(wrapper.text()).toContain('非文本缓存上限')
    expect(wrapper.text()).toContain('后缀规则')
  })

  it('emits save with changed auto paste setting', async () => {
    const wrapper = mount(ClipboardSettings, { props: { settings: settings() } })

    await wrapper.get('[data-test="auto-paste"]').setValue(true)
    await wrapper.get('[data-test="save-settings"]').trigger('click')

    const payload = wrapper.emitted('save')?.[0]?.[0] as ClipboardSettingsType
    expect(payload.autoPaste).toBe(true)
  })

  it('parses file extensions as trimmed lower-case values', async () => {
    const wrapper = mount(ClipboardSettings, { props: { settings: settings() } })

    await wrapper.get('[data-test="extension-mode"]').setValue('allow_list')
    await wrapper.get('[data-test="extensions"]').setValue('.PDF, Docx, png')
    await wrapper.get('[data-test="save-settings"]').trigger('click')

    const payload = wrapper.emitted('save')?.[0]?.[0] as ClipboardSettingsType
    expect(payload.fileExtensionMode).toBe('allow_list')
    expect(payload.fileExtensions).toEqual(['pdf', 'docx', 'png'])
  })
})
