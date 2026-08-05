import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import SaveToAssetDialog from './SaveToAssetDialog.vue'
import { projectApi, assetBridgeApi } from '@/api/assets'
import type { AxiosResponse } from 'axios'
import type { AssetProjectVO, CanvasImportVO } from '@/types/asset'
import type { CanvasNode } from '@/types/canvas'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/assets', () => ({
  projectApi: { list: vi.fn() },
  assetBridgeApi: { importFromCanvas: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function mkProject(id: number, role: 'OWNER' | 'EDITOR' | 'VIEWER'): AssetProjectVO {
  return {
    id,
    name: `项目${id}`,
    ownerId: 1,
    narrativeRoles: id === 10 ? ['人物', '场景'] : [],
    role,
    createdAt: '2026-08-05'
  }
}

function mkNode(type: string, data: Record<string, unknown>): CanvasNode {
  return {
    id: 'node-1',
    type,
    position: { x: 0, y: 0 },
    data: { label: '节点', ...data }
  }
}

function importVO(over: Partial<CanvasImportVO>): CanvasImportVO {
  return { created: true, ...over }
}

async function settle() {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

async function mountDialog(node: CanvasNode) {
  const wrapper = mount(SaveToAssetDialog, {
    props: { show: true, node, canvasId: 5 }
  })
  await settle()
  return wrapper
}

describe('SaveToAssetDialog (S12-a 入库弹窗)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(projectApi.list).mockResolvedValue(
      response({
        code: 200,
        message: 'ok',
        data: [mkProject(10, 'OWNER'), mkProject(11, 'EDITOR'), mkProject(12, 'VIEWER')]
      })
    )
  })

  it('空产出节点 → hasOutput=false（前端预检；后端兜底同样拦截）', async () => {
    const wrapper = await mountDialog(mkNode('text', { prompt: '' }))
    const vm = wrapper.vm as unknown as { hasOutput: boolean }
    expect(vm.hasOutput).toBe(false)
  })

  it('项目下拉仅列可写项目（OWNER+EDITOR；viewer 过滤）', async () => {
    const wrapper = await mountDialog(mkNode('text', { outputText: '产出' }))
    const vm = wrapper.vm as unknown as { projectOptions: { value: number }[] }
    expect(vm.projectOptions.map((o) => o.value).sort()).toEqual([10, 11])
  })

  it('首次提交 created=true → emit imported + 关弹窗', async () => {
    vi.mocked(assetBridgeApi.importFromCanvas).mockResolvedValue(
      response({ code: 200, message: 'ok', data: importVO({ assetId: 88, name: '老板娘', version: 1 }) })
    )
    const node = mkNode('image', { fileId: 'f-1' })
    const wrapper = await mountDialog(node)
    const vm = wrapper.vm as unknown as {
      form: { projectId: number | null; name: string }
      submit: (m?: string) => Promise<void>
    }
    vm.form.projectId = 10
    vm.form.name = '老板娘'
    await vm.submit(undefined)

    expect(assetBridgeApi.importFromCanvas).toHaveBeenCalledWith(expect.objectContaining({
      canvasId: 5, nodeId: 'node-1', projectId: 10, mode: undefined
    }))
    const emitted = wrapper.emitted('imported')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toMatchObject({ node, assetId: 88, name: '老板娘', version: 1 })
    expect(wrapper.emitted('update:show')).toBeTruthy()
  })

  it('首次提交检测到重复 → duplicate 置位，按钮切三态（created=false 不 emit）', async () => {
    vi.mocked(assetBridgeApi.importFromCanvas).mockResolvedValue(
      response({
        code: 200,
        message: 'ok',
        data: importVO({ created: false, duplicateAssetId: 88, duplicateVersion: 2 })
      })
    )
    const wrapper = await mountDialog(mkNode('video', { fileId: 'f-v' }))
    const vm = wrapper.vm as unknown as {
      form: { projectId: number | null }
      duplicate: { assetId: number; version?: number } | null
      submit: (m?: string) => Promise<void>
    }
    vm.form.projectId = 10
    await vm.submit(undefined)

    expect(vm.duplicate).toEqual({ assetId: 88, version: 2 })
    expect(wrapper.emitted('imported')).toBeFalsy()
  })

  it('重复后选「存为新版本」→ 带 mode=NEW_VERSION 再调成功 emit', async () => {
    vi.mocked(assetBridgeApi.importFromCanvas)
      .mockResolvedValueOnce(
        response({ code: 200, message: 'ok', data: importVO({ created: false, duplicateAssetId: 88, duplicateVersion: 1 }) })
      )
      .mockResolvedValueOnce(
        response({ code: 200, message: 'ok', data: importVO({ assetId: 88, name: '老板娘', version: 2 }) })
      )
    const wrapper = await mountDialog(mkNode('image', { fileId: 'f-1' }))
    const vm = wrapper.vm as unknown as {
      form: { projectId: number | null }
      submit: (m?: string) => Promise<void>
    }
    vm.form.projectId = 10
    await vm.submit(undefined)
    await vm.submit('NEW_VERSION')

    const secondCall = vi.mocked(assetBridgeApi.importFromCanvas).mock.calls[1][0]
    expect(secondCall.mode).toBe('NEW_VERSION')
    expect(wrapper.emitted('imported')).toBeTruthy()
    expect((wrapper.emitted('imported')![0][0] as { version: number }).version).toBe(2)
  })

  it('name 缺省 → 用 undefined（后端兜底解析节点 label/类型）', async () => {
    vi.mocked(assetBridgeApi.importFromCanvas).mockResolvedValue(
      response({ code: 200, message: 'ok', data: importVO({ assetId: 9, version: 1 }) })
    )
    const wrapper = await mountDialog(mkNode('audio', { fileId: 'f-a' }))
    const vm = wrapper.vm as unknown as {
      form: { projectId: number | null; name: string }
      submit: (m?: string) => Promise<void>
    }
    vm.form.projectId = 11
    vm.form.name = '   '
    await vm.submit(undefined)

    expect(vi.mocked(assetBridgeApi.importFromCanvas).mock.calls[0][0].name).toBeUndefined()
  })

  it('后端报错 → message.error 不 emit', async () => {
    vi.mocked(assetBridgeApi.importFromCanvas).mockRejectedValue({ msg: '无写权限' })
    const wrapper = await mountDialog(mkNode('text', { outputText: 'x' }))
    const vm = wrapper.vm as unknown as {
      form: { projectId: number | null }
      submit: (m?: string) => Promise<void>
    }
    vm.form.projectId = 10
    await vm.submit(undefined)
    expect(messageMock.error).toHaveBeenCalledWith('无写权限')
    expect(wrapper.emitted('imported')).toBeFalsy()
  })

  it('切项目清空已选叙事角色（受控词汇按项目变）', async () => {
    const wrapper = await mountDialog(mkNode('image', { fileId: 'f-1' }))
    const vm = wrapper.vm as unknown as {
      form: { roleKeys: string[] }
      onProjectChange: () => void
    }
    vm.form.roleKeys = ['人物']
    vm.onProjectChange()
    expect(vm.form.roleKeys).toEqual([])
  })
})
