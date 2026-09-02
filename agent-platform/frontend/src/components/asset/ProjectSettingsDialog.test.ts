import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { NPopconfirm } from 'naive-ui'
import ProjectSettingsDialog from './ProjectSettingsDialog.vue'
import { projectApi } from '@/api/assets'
import type { AxiosResponse } from 'axios'
import type { AssetProjectVO } from '@/types/asset'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/assets', () => ({
  projectApi: { updateSettings: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function mkProject(over: Partial<AssetProjectVO> = {}): AssetProjectVO {
  return {
    id: 7,
    name: '短剧第一季',
    description: '',
    ownerId: 1,
    narrativeRoles: [{ key: '人物', children: [] }],
    mediaTypes: [],
    role: 'OWNER',
    memberScoringEnabled: false,
    contentMode: 'SHARED',
    createdAt: '2026-08-05',
    ...over
  }
}

async function mountDialog(project: AssetProjectVO = mkProject()) {
  const wrapper = mount(ProjectSettingsDialog, {
    props: { show: true, project }
  })
  await flushPromises()
  return wrapper
}

describe('ProjectSettingsDialog (C7)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(projectApi.updateSettings).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkProject() })
    )
  })

  it('打开时同步项目当前值到草稿', async () => {
    const wrapper = await mountDialog(mkProject({ memberScoringEnabled: true, contentMode: 'PERSONAL' }))
    const vm = wrapper.vm as unknown as { draftScoring: boolean; draftMode: string }
    expect(vm.draftScoring).toBe(true)
    expect(vm.draftMode).toBe('PERSONAL')
  })

  it('开关变更保存 → updateSettings 仅提交 memberScoringEnabled', async () => {
    const wrapper = await mountDialog(mkProject({ memberScoringEnabled: false, contentMode: 'SHARED' }))
    const vm = wrapper.vm as unknown as { draftScoring: boolean; save: () => Promise<void> }
    vm.draftScoring = true
    await vm.save()
    expect(projectApi.updateSettings).toHaveBeenCalledWith(7, { memberScoringEnabled: true })
    expect(wrapper.emitted('saved')).toBeTruthy()
    expect(messageMock.success).toHaveBeenCalled()
  })

  it('无变更保存 → 不调接口直接关闭', async () => {
    const wrapper = await mountDialog()
    const vm = wrapper.vm as unknown as { save: () => Promise<void> }
    await vm.save()
    expect(projectApi.updateSettings).not.toHaveBeenCalled()
    expect(messageMock.info).toHaveBeenCalled()
  })

  it('SHARED→PERSONAL → needsPersonalConfirm=true（保存钮包 popconfirm）', async () => {
    const wrapper = await mountDialog(mkProject({ contentMode: 'SHARED' }))
    const vm = wrapper.vm as unknown as { draftMode: string; needsPersonalConfirm: boolean }
    vm.draftMode = 'PERSONAL'
    await wrapper.vm.$nextTick()
    expect(vm.needsPersonalConfirm).toBe(true)
    expect(wrapper.findComponent(NPopconfirm).exists()).toBe(true)
  })

  it('PERSONAL→SHARED 回切 → 无二次确认；保存仅提交 contentMode', async () => {
    const wrapper = await mountDialog(mkProject({ contentMode: 'PERSONAL' }))
    const vm = wrapper.vm as unknown as { draftMode: string; needsPersonalConfirm: boolean; save: () => Promise<void> }
    vm.draftMode = 'SHARED'
    await wrapper.vm.$nextTick()
    expect(vm.needsPersonalConfirm).toBe(false)
    await vm.save()
    expect(projectApi.updateSettings).toHaveBeenCalledWith(7, { contentMode: 'SHARED' })
  })

  it('保存失败 → 错误提示不 emit saved', async () => {
    vi.mocked(projectApi.updateSettings).mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountDialog()
    const vm = wrapper.vm as unknown as { draftScoring: boolean; save: () => Promise<void> }
    vm.draftScoring = true
    await vm.save()
    expect(messageMock.error).toHaveBeenCalled()
    expect(wrapper.emitted('saved')).toBeFalsy()
  })
})
