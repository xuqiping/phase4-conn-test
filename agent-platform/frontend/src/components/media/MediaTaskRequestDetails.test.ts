import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('naive-ui', () => ({
  NButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  NModal: { template: '<section><slot /></section>' },
  NTabs: { template: '<div><slot /></div>' },
  NTabPane: { props: ['tab'], template: '<article>{{ tab }}<slot /></article>' },
  NAlert: { template: '<div><slot /></div>' }
}))

import MediaTaskRequestDetails from './MediaTaskRequestDetails.vue'

describe('MediaTaskRequestDetails', () => {
  it('AC-V3-07 展示平台参数和实际 Provider 脱敏快照', async () => {
    const wrapper = mount(MediaTaskRequestDetails, {
      props: {
        submittedRequest: { prompt: '猫', attachments: [{ fileId: 'img-1' }] },
        providerRequestSnapshot: {
          provider: 'ark-seedance',
          request: { content: [{ image_url: { redacted: true, fileId: 'img-1' } }] }
        }
      }
    })
    await wrapper.get('button').trigger('click')

    expect(wrapper.text()).toContain('平台收到的提交参数')
    expect(wrapper.text()).toContain('实际发给模型（已脱敏）')
    expect(wrapper.get('[data-testid="submitted-request"]').text()).toContain('"prompt": "猫"')
    expect(wrapper.get('[data-testid="provider-request"]').text()).toContain('"redacted": true')
    expect(wrapper.text()).not.toContain('该历史任务未记录发送快照')
  })

  it('AC-V3-07 旧任务明确提示未记录，且可复制当前视图 JSON', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const wrapper = mount(MediaTaskRequestDetails, {
      props: { submittedRequest: { prompt: '旧任务' }, providerRequestSnapshot: null }
    })
    await wrapper.get('button').trigger('click')

    expect(wrapper.text()).toContain('该历史任务未记录发送快照')
    await wrapper.get('[data-testid="copy-submitted"]').trigger('click')
    expect(writeText).toHaveBeenCalledWith('{\n  "prompt": "旧任务"\n}')
    expect(wrapper.text()).toContain('已复制')
  })
})
