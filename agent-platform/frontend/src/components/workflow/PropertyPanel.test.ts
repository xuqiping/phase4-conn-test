import { mount } from '@vue/test-utils'
import { NInput, NSelect, NSwitch, NDynamicTags } from 'naive-ui'
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

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    listBases: vi.fn().mockResolvedValue({ data: { data: [] } })
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

describe('PropertyPanel human_input node', () => {
  function humanInputNode(data: Partial<WorkflowNode['data']> = {}): WorkflowNode {
    return {
      id: 'human-1',
      type: 'human_input',
      position: { x: 0, y: 0 },
      data: {
        label: '收集姓名',
        inputKey: 'user_name',
        inputType: 'text',
        questionTemplate: '你叫什么名字？',
        required: true,
        ...data
      }
    }
  }

  it('renders human_input config fields', () => {
    const wrapper = mount(PropertyPanel, {
      props: {
        selectedNode: humanInputNode(),
        nodes: [],
        edges: [],
        editable: true
      }
    })

    expect(wrapper.text()).toContain('人机交互')
    expect(wrapper.text()).toContain('答案变量名')
    expect(wrapper.text()).toContain('问题模板')

    const questionInput = wrapper.findAllComponents(NInput)
      .find(input => input.props('value') === '你叫什么名字？')
    expect(questionInput?.exists()).toBe(true)
  })

  it('shows the inputType select with text/textarea/select options', () => {
    const wrapper = mount(PropertyPanel, {
      props: {
        selectedNode: humanInputNode({ inputType: 'select', options: ['低', '中', '高'] }),
        nodes: [],
        edges: [],
        editable: true
      }
    })

    const typeSelect = wrapper.findAllComponents(NSelect)
      .find(select => select.props('value') === 'select')

    expect(typeSelect?.exists()).toBe(true)
    const options = (typeSelect?.props('options') as Array<{ value: string }>) || []
    expect(options.some(o => o.value === 'select')).toBe(true)
  })

  it('shows the options tag editor only for select type', () => {
    const selectWrapper = mount(PropertyPanel, {
      props: {
        selectedNode: humanInputNode({ inputType: 'select', options: ['低', '中', '高'] }),
        nodes: [],
        edges: [],
        editable: true
      }
    })
    expect(selectWrapper.findComponent(NDynamicTags).exists()).toBe(true)
    expect(selectWrapper.findComponent(NDynamicTags).props('value')).toEqual(['低', '中', '高'])

    const textWrapper = mount(PropertyPanel, {
      props: {
        selectedNode: humanInputNode({ inputType: 'text' }),
        nodes: [],
        edges: [],
        editable: true
      }
    })
    expect(textWrapper.findComponent(NDynamicTags).exists()).toBe(false)
  })

  it('emits update-node-data when required switch toggles', async () => {
    const wrapper = mount(PropertyPanel, {
      props: {
        selectedNode: humanInputNode({ required: true }),
        nodes: [],
        edges: [],
        editable: true
      }
    })

    const sw = wrapper.findComponent(NSwitch)
    expect(sw.exists()).toBe(true)
    await sw.vm.$emit('update:value', false)

    const updateEvents = wrapper.emitted('update-node-data')
    expect(updateEvents).toBeTruthy()
    const last = updateEvents![updateEvents!.length - 1]
    expect(last![0]).toBe('human-1')
    expect(last![1]).toBe('required')
    expect(last![2]).toBe(false)
  })
})
