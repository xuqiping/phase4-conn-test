import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import FreeModuleSelector from '../FreeModuleSelector.vue'
import { useCommercialAuthStore } from '../../stores/commercialAuthStore'

function mountSelector() {
  const wrapper = mount(FreeModuleSelector, {
    props: {
      baseUrl: 'http://localhost:8080'
    },
    global: {
      plugins: [createTestingPinia({ createSpy: vi.fn, stubActions: true })]
    }
  })
  return {
    wrapper,
    commercialAuthStore: useCommercialAuthStore()
  }
}

describe('FreeModuleSelector', () => {
  it('shows three module options after anonymous trial expires without a free module', async () => {
    const { wrapper, commercialAuthStore } = mountSelector()
    commercialAuthStore.trialStatus = {
      deviceId: 'device-1',
      inFullTrial: false,
      trialExpired: true,
      freeModuleCode: null,
      allowedModuleCodes: []
    }
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-test="free-module-title"]').text()).toContain('匿名试用已过期')
    expect(wrapper.get('[data-test="free-module-option-files"]').text()).toContain('文件管理')
    expect(wrapper.get('[data-test="free-module-option-processes"]').text()).toContain('进程管理')
    expect(wrapper.get('[data-test="free-module-option-clipboard"]').text()).toContain('剪贴板')
  })

  it('selects files through the commercial auth store', async () => {
    const { wrapper, commercialAuthStore } = mountSelector()
    commercialAuthStore.trialStatus = {
      deviceId: 'device-1',
      inFullTrial: false,
      trialExpired: true,
      freeModuleCode: null,
      allowedModuleCodes: []
    }
    vi.mocked(commercialAuthStore.selectFreeModule).mockResolvedValue(undefined)

    await wrapper.get('[data-test="free-module-option-files"]').trigger('click')

    expect(commercialAuthStore.selectFreeModule).toHaveBeenCalledWith('http://localhost:8080', 'files')
    expect(wrapper.emitted('selected')).toHaveLength(1)
  })

  it('changes the current free module and emits selected after success', async () => {
    const { wrapper, commercialAuthStore } = mountSelector()
    commercialAuthStore.trialStatus = {
      deviceId: 'device-1',
      inFullTrial: false,
      trialExpired: true,
      freeModuleCode: 'files',
      allowedModuleCodes: ['files']
    }
    vi.mocked(commercialAuthStore.changeFreeModule).mockResolvedValue(undefined)
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-test="free-module-change-note"]').text()).toContain('每 30 天可更换一次')

    await wrapper.get('[data-test="free-module-option-clipboard"]').trigger('click')

    expect(commercialAuthStore.changeFreeModule).toHaveBeenCalledWith('http://localhost:8080', 'clipboard')
    expect(wrapper.emitted('selected')).toHaveLength(1)
  })

  it('shows backend restriction message when changing too soon', async () => {
    const { wrapper, commercialAuthStore } = mountSelector()
    commercialAuthStore.trialStatus = {
      deviceId: 'device-1',
      inFullTrial: false,
      trialExpired: true,
      freeModuleCode: 'files',
      allowedModuleCodes: ['files']
    }
    vi.mocked(commercialAuthStore.changeFreeModule).mockRejectedValue(new Error('免费模块每 30 天只能更换一次'))
    await wrapper.vm.$nextTick()

    await wrapper.get('[data-test="free-module-option-processes"]').trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-test="free-module-error"]').text()).toContain('免费模块每 30 天只能更换一次')
    expect(wrapper.emitted('selected')).toBeUndefined()
  })
})
