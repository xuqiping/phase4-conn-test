import { mount } from '@vue/test-utils'
import { NInput } from 'naive-ui'
import { describe, expect, it, vi } from 'vitest'
import PropertyPanel from './PropertyPanel.vue'
import type { WorkflowNode } from '@/types/workflow'

vi.mock('@/api/agent', () => ({
  agentApi: {
    listAgents: vi.fn().mockResolvedValue({ data: { data: [] } })
  }
}))

vi.mock('@/api/workflow', () => ({
  workflowApi: {
    list: vi.fn().mockResolvedValue({ data: { data: [] } })
  }
}))

function skillNode(data: Partial<WorkflowNode['data']> = {}): WorkflowNode {
  return {
    id: 'skill-1',
    type: 'skill',
    position: { x: 0, y: 0 },
    data: {
      label: '联调摘要生成',
      skillId: 1,
      agentId: 1,
      agentName: 'Admin Agent',
      ...data
    }
  }
}

describe('PropertyPanel prompt permissions', () => {
  it('hides prompt editors when a skill node has no explicit prompt permission flags', () => {
    const wrapper = mount(PropertyPanel, {
      props: {
        selectedNode: skillNode(),
        nodes: [],
        edges: [],
        editable: true
      }
    })

    expect(wrapper.text()).toContain('当前账号没有提示词查看权限')
    expect(wrapper.text()).not.toContain('定义模型的角色、边界和回答原则')
    expect(wrapper.text()).not.toContain('输入 / 插入上游变量')
  })
})

describe('PropertyPanel start alias', () => {
  it('shows start node alias editing with start as the default alias', () => {
    const wrapper = mount(PropertyPanel, {
      props: {
        selectedNode: {
          id: 'start-1',
          type: 'start',
          position: { x: 0, y: 0 },
          data: {
            label: '开始',
            inputKey: 'ccc'
          }
        },
        nodes: [],
        edges: [],
        editable: true
      }
    })

    expect(wrapper.text()).toContain('稳定节点别名')
    expect(wrapper.text()).toContain('{{start.ccc}}')
  })
})

describe('PropertyPanel node description permissions', () => {
  it('allows skill owners to edit workflow node description', () => {
    const wrapper = mount(PropertyPanel, {
      props: {
        selectedNode: skillNode({
          description: 'Owner note',
          descriptionVisible: true,
          descriptionEditable: true
        }),
        nodes: [],
        edges: [],
        editable: true
      }
    })

    const descriptionInput = wrapper.findAllComponents(NInput)
      .find(input => input.props('value') === 'Owner note')

    expect(descriptionInput?.exists()).toBe(true)
    expect(descriptionInput?.props('disabled')).toBe(false)
  })

  it('shows authorized users the description without allowing edits', () => {
    const wrapper = mount(PropertyPanel, {
      props: {
        selectedNode: {
          id: 'agent-ref-1',
          type: 'agent_ref',
          position: { x: 0, y: 0 },
          data: {
            label: 'Admin Agent',
            agentId: 1,
            agentName: 'Admin Agent',
            description: 'Read-only note',
            descriptionVisible: true,
            descriptionEditable: false
          }
        },
        nodes: [],
        edges: [],
        editable: true
      }
    })

    const descriptionInput = wrapper.findAllComponents(NInput)
      .find(input => input.props('value') === 'Read-only note')

    expect(descriptionInput?.exists()).toBe(true)
    expect(descriptionInput?.props('disabled')).toBe(true)
  })
})
