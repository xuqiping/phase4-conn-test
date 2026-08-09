import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import MemoryRulePanel from './MemoryRulePanel.vue'
import { memoryApi, type MemoryGenMatrixItemVO, type MemoryProjectRuleVO } from '@/api/memory'
import type { AxiosResponse } from 'axios'

// 稳定单例 message（组件 setup 捕获 = 测试断言同一实例）
const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/memory', () => ({
  memoryApi: {
    getGenMatrix: vi.fn(),
    getProjectRule: vi.fn(),
    putProjectRule: vi.fn()
  }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function mkProject(projectId: number, role: 'OWNER' | 'ADMIN' | 'MEMBER'): MemoryGenMatrixItemVO {
  return {
    projectId,
    projectName: `项目${projectId}`,
    role,
    ownerEnabled: true,
    memberEnabled: true,
    effective: true
  }
}

function mkRule(overrides: Partial<MemoryProjectRuleVO> = {}): MemoryProjectRuleVO {
  return {
    id: 9,
    projectId: 1,
    ruleText: '涉及 SeedDance 的讨论',
    positiveExamples: ['正例A'],
    negativeExamples: ['负例B'],
    enabled: true,
    anchorReady: true,
    updatedAt: '2026-08-08T10:00:00Z',
    ...overrides
  }
}

function apiOk<T>(data: T) {
  return response({ code: 200, message: 'ok', data })
}

async function settle() {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

describe('MemoryRulePanel（二期 P1 · FR-001 收录规则）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('加载项目后默认选中首个可编辑项目并拉取规则填入表单', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([mkProject(1, 'MEMBER'), mkProject(2, 'OWNER')]))
    vi.mocked(memoryApi.getProjectRule).mockResolvedValue(apiOk(mkRule({ projectId: 2 })))

    const wrapper = mount(MemoryRulePanel)
    await settle()

    expect(memoryApi.getProjectRule).toHaveBeenCalledWith(2)
    const vm = wrapper.vm as unknown as {
      currentProjectId: number
      form: { ruleText: string; positiveExamples: string[]; negativeExamples: string[]; enabled: boolean }
      canEdit: boolean
    }
    expect(vm.currentProjectId).toBe(2)
    expect(vm.canEdit).toBe(true)
    expect(vm.form.ruleText).toBe('涉及 SeedDance 的讨论')
    expect(vm.form.positiveExamples).toEqual(['正例A'])
    expect(vm.form.negativeExamples).toEqual(['负例B'])
  })

  it('成员角色只读：不渲染保存按钮，negativeExamples 为 null 时表单空', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([mkProject(1, 'MEMBER')]))
    vi.mocked(memoryApi.getProjectRule).mockResolvedValue(
      apiOk(mkRule({ projectId: 1, negativeExamples: null }))
    )

    const wrapper = mount(MemoryRulePanel)
    await settle()

    const vm = wrapper.vm as unknown as { canEdit: boolean; form: { negativeExamples: string[] } }
    expect(vm.canEdit).toBe(false)
    expect(vm.form.negativeExamples).toEqual([])
    expect(wrapper.find('button[type="button"].n-button--primary-type').exists()).toBe(false)
  })

  it('项目无规则（data=null）→ 空表单待创建（PUT upsert）', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([mkProject(1, 'OWNER')]))
    vi.mocked(memoryApi.getProjectRule).mockResolvedValue(apiOk(null))

    const wrapper = mount(MemoryRulePanel)
    await settle()

    const vm = wrapper.vm as unknown as {
      form: { ruleText: string; enabled: boolean }
    }
    expect(vm.form.ruleText).toBe('')
    expect(vm.form.enabled).toBe(true)
  })

  it('保存时 trim + 过滤空例，调用 putProjectRule；anchorReady=false 提示降级', async () => {
    vi.mocked(memoryApi.getGenMatrix).mockResolvedValue(apiOk([mkProject(1, 'OWNER')]))
    vi.mocked(memoryApi.getProjectRule).mockResolvedValue(apiOk(mkRule()))
    vi.mocked(memoryApi.putProjectRule).mockResolvedValue(
      apiOk(mkRule({ anchorReady: false, enabled: false }))
    )

    const wrapper = mount(MemoryRulePanel)
    await settle()

    const vm = wrapper.vm as unknown as {
      form: { ruleText: string; positiveExamples: string[]; negativeExamples: string[]; enabled: boolean }
      save: () => Promise<void>
    }
    vm.form.ruleText = '  新规则  '
    vm.form.positiveExamples = ['  例1 ', '   ']
    vm.form.negativeExamples = []
    await vm.save()

    expect(memoryApi.putProjectRule).toHaveBeenCalledWith(1, {
      ruleText: '新规则',
      positiveExamples: ['例1'],
      negativeExamples: [],
      enabled: true
    })
    expect(messageMock.success).toHaveBeenCalledWith('已保存，但向量化失败规则未生效')
  })
})
