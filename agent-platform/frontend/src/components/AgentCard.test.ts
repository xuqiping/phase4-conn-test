import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import AgentCard from './AgentCard.vue'
import type { Agent } from '@/api/agent'

const mockAgent: Agent = {
  id: 1,
  name: '测试Agent',
  description: '用于测试的Agent',
  avatar: null,
  status: 'ACTIVE',
  groupId: 1,
  groupName: '开发工具',
  skillCount: 3,
  createdAt: '2026-01-01'
}

function createMockRouter() {
  return createRouter({
    history: createWebHistory(),
    routes: [{ path: '/agents/:id', component: { template: '<div />' } }]
  })
}

describe('AgentCard', () => {
  it('renders agent name and description', () => {
    const router = createMockRouter()
    const wrapper = mount(AgentCard, {
      props: { agent: mockAgent },
      global: {
        plugins: [router],
        stubs: {
          NIcon: true
        }
      }
    })
    expect(wrapper.text()).toContain('测试Agent')
    expect(wrapper.text()).toContain('用于测试的Agent')
  })

  it('shows first letter as avatar placeholder when no avatar', () => {
    const router = createMockRouter()
    const wrapper = mount(AgentCard, {
      props: { agent: mockAgent },
      global: { plugins: [router], stubs: { NIcon: true } }
    })
    expect(wrapper.text()).toContain('测')
  })

  it('shows group name when present', () => {
    const router = createMockRouter()
    const wrapper = mount(AgentCard, {
      props: { agent: mockAgent },
      global: { plugins: [router], stubs: { NIcon: true } }
    })
    expect(wrapper.text()).toContain('开发工具')
  })

  it('shows skill count', () => {
    const router = createMockRouter()
    const wrapper = mount(AgentCard, {
      props: { agent: mockAgent },
      global: { plugins: [router], stubs: { NIcon: true } }
    })
    expect(wrapper.text()).toContain('3 个技能')
  })

  it('shows online status for ACTIVE agent', () => {
    const router = createMockRouter()
    const wrapper = mount(AgentCard, {
      props: { agent: mockAgent },
      global: { plugins: [router], stubs: { NIcon: true } }
    })
    expect(wrapper.text()).toContain('在线')
  })

  it('shows offline status for non-ACTIVE agent', () => {
    const router = createMockRouter()
    const offlineAgent = { ...mockAgent, status: 'DRAFT' }
    const wrapper = mount(AgentCard, {
      props: { agent: offlineAgent },
      global: { plugins: [router], stubs: { NIcon: true } }
    })
    expect(wrapper.text()).toContain('离线')
  })

  it('navigates to detail on click', async () => {
    const router = createMockRouter()
    const pushSpy = vi.spyOn(router, 'push')
    const wrapper = mount(AgentCard, {
      props: { agent: mockAgent },
      global: { plugins: [router], stubs: { NIcon: true } }
    })
    await wrapper.find('.agent-card').trigger('click')
    expect(pushSpy).toHaveBeenCalledWith('/agents/1')
  })

  it('shows placeholder text when no description', () => {
    const router = createMockRouter()
    const noDesc = { ...mockAgent, description: null }
    const wrapper = mount(AgentCard, {
      props: { agent: noDesc },
      global: { plugins: [router], stubs: { NIcon: true } }
    })
    expect(wrapper.text()).toContain('暂无描述')
  })
})
