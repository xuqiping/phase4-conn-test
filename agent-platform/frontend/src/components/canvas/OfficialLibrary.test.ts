import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick, ref } from 'vue'
import OfficialLibrary from './OfficialLibrary.vue'
import AssetPickerRow from './AssetPickerRow.vue'
import { publicPoolApi, assetApi } from '@/api/assets'
import type { AxiosResponse } from 'axios'
import type { AssetVO, PublicProjectSummaryVO } from '@/types/asset'

// 修复XI B2（2x 未解决②）：官方库大卡片——official 过滤请求/双栏分组/容错/空态/选择链。
// AssetPickerRow 依赖懒加载组合式，mock 成可控 url（每用例独立 ref，同 AssetPickerRow.test 范式）。
let urlRef = ref<string | null>(null)
vi.mock('@/composables/useLazyFilePreview', () => ({
  useLazyFilePreview: () => ({ url: urlRef, failed: ref(false) })
}))
const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})
vi.mock('@/api/assets', () => ({
  publicPoolApi: { list: vi.fn() },
  assetApi: { list: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}
function mkPublic(id: number, over: Partial<PublicProjectSummaryVO> = {}): PublicProjectSummaryVO {
  return {
    id,
    name: `官方项目${id}`,
    description: `官方摘要${id}`,
    publicAccessMode: 'OPEN',
    publishedBy: 1,
    publisherUsername: 'admin',
    publishedAt: '2026-09-01T10:00:00Z',
    publishedByAdmin: true,
    assetCount: 3,
    myRequestStatus: null,
    usable: true,
    ...over
  }
}
function mkAsset(id: number, mediaType: string, over: Partial<AssetVO> = {}): AssetVO {
  return {
    id, projectId: 10, mediaType, name: `资产${id}`, status: 'DRAFT',
    currentVersion: 1, roleKeys: [], content: null, genMeta: null, createdAt: '2026-08-05', ...over
  }
}
function pageResp(records: AssetVO[]) {
  return response({ code: 200, message: 'ok', data: { records, total: records.length, page: 1, size: 100 } })
}
async function settle() {
  await flushPromises()
  await nextTick()
}
/** n-modal teleport 到 body——DOM 断言走 document（wrapper 子树外）。 */
const docAll = (sel: string) => Array.from(document.querySelectorAll(sel))
async function docClick(sel: string) {
  document.querySelector(sel)!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await nextTick()
}
async function mountLib(props: { show?: boolean; pickingId?: number | null } = {}) {
  const wrapper = mount(OfficialLibrary, { props: { show: true, ...props }, attachTo: document.body })
  await settle()
  return wrapper
}
const publicListMock = vi.mocked(publicPoolApi.list)
const assetListMock = vi.mocked(assetApi.list)

beforeEach(() => {
  vi.clearAllMocks()
  urlRef = ref<string | null>(null)
  publicListMock.mockResolvedValue(response({ code: 200, message: 'ok', data: [] }))
})
/** n-modal teleport 挂 body：unmount 后残留节点手动清（同 AssetPickerRow.test 范式），防跨用例污染。 */
afterEach(() => { document.body.innerHTML = '' })

describe('OfficialLibrary · 修复XI B2 官方库大卡片', () => {
  it('① 打开即以 official=true 拉项目；渲染官方卡（名称/计数/发布者）；不可用项目过滤不显示', async () => {
    publicListMock.mockResolvedValue(response({
      code: 200, message: 'ok',
      data: [mkPublic(1), mkPublic(2, { usable: false, name: '审批中项目' })]
    }))
    const wrapper = await mountLib()
    expect(publicListMock).toHaveBeenCalledWith({ official: true })
    const cards = docAll('.olib__project')
    expect(cards).toHaveLength(1) // usable=false 过滤
    expect(cards[0].textContent).toContain('官方项目1')
    expect(cards[0].textContent).toContain('3 项资产')
    expect(cards[0].textContent).toContain('admin')
    expect(cards[0].querySelector('.olib__project-badge')!.textContent).toBe('官方')
    wrapper.unmount()
  })

  it('② 点项目 → 全量拉资产（无 type 过滤）；按 mediaTypes 词汇序分组+词汇外归「其他」+空组隐藏', async () => {
    publicListMock.mockResolvedValue(response({
      code: 200, message: 'ok',
      data: [mkPublic(1, { mediaTypes: '[{"key":"提示词","category":"TEXT"},{"key":"图片","category":"IMAGE"},{"key":"视频","category":"VIDEO"}]' })]
    }))
    const d = deferred<AxiosResponse<{ code: number; message: string; data: { records: AssetVO[]; total: number; page: number; size: number } }>>()
    assetListMock.mockImplementation(() => d.promise)
    const wrapper = await mountLib()
    await docClick('.olib__project')
    await settle()
    expect(assetListMock).toHaveBeenCalledWith(1, { page: 1, size: 100 }) // 无 type=全量
    d.resolve(pageResp([
      mkAsset(11, '提示词'), mkAsset(12, '提示词'), mkAsset(13, '图片'), mkAsset(14, '角色模型')
    ]))
    await settle()
    const heads = docAll('.olib__group-head').map((h) => h.textContent ?? '')
    expect(heads).toEqual(['提示词2', '图片1', '其他1']) // 视频空组隐藏；「角色模型」不在词汇 → 尾组其他
    const rows = wrapper.findAllComponents(AssetPickerRow)
    expect(rows).toHaveLength(4)
    wrapper.unmount()
  })

  it('③ mediaTypes 容错（plan 细化2）：非法 JSON → 单组「全部资产」不炸', async () => {
    publicListMock.mockResolvedValue(response({
      code: 200, message: 'ok', data: [mkPublic(1, { mediaTypes: 'not-json{{' })]
    }))
    assetListMock.mockResolvedValue(pageResp([mkAsset(11, '提示词'), mkAsset(12, '视频')]) as never)
    const wrapper = await mountLib()
    await docClick('.olib__project')
    await settle()
    const heads = docAll('.olib__group-head').map((h) => h.textContent ?? '')
    expect(heads).toEqual(['全部资产2'])
    wrapper.unmount()
  })

  it('④ 空态两路：无官方发布项目 / 选中项目无资产', async () => {
    const empty = await mountLib()
    expect(docAll('.olib__msg').map((m) => m.textContent)).toContain('暂无官方发布项目')
    empty.unmount()
    document.body.innerHTML = '' // 两段式用例：段间手动清残留

    publicListMock.mockResolvedValue(response({ code: 200, message: 'ok', data: [mkPublic(1)] }))
    assetListMock.mockResolvedValue(pageResp([]) as never)
    const wrapper = await mountLib()
    await docClick('.olib__project')
    await settle()
    expect(docAll('.olib__msg').map((m) => m.textContent)).toContain('该项目下无资产')
    wrapper.unmount()
  })

  it('⑤ 行选择 emit picked{asset} 且不自动关卡片（成败由父组件 B3 控制，失败卡片留）', async () => {
    publicListMock.mockResolvedValue(response({
      code: 200, message: 'ok', data: [mkPublic(1, { mediaTypes: '[{"key":"提示词","category":"TEXT"}]' })]
    }))
    assetListMock.mockResolvedValue(pageResp([mkAsset(11, '提示词')]) as never)
    const wrapper = await mountLib()
    await docClick('.olib__project')
    await settle()
    await wrapper.findComponent(AssetPickerRow).vm.$emit('pick', wrapper.findComponent(AssetPickerRow).props('asset'))
    expect(wrapper.emitted('picked')?.[0]?.[0]).toMatchObject({ asset: { id: 11, mediaType: '提示词' } })
    expect(wrapper.emitted('update:show')).toBeUndefined() // 不自闭
    wrapper.unmount()
  })

  it('⑥ pickingId 透传行按钮 loading（resolve 进行中防重入视觉）', async () => {
    publicListMock.mockResolvedValue(response({
      code: 200, message: 'ok', data: [mkPublic(1, { mediaTypes: '[{"key":"图片","category":"IMAGE"}]' })]
    }))
    assetListMock.mockResolvedValue(pageResp([mkAsset(11, '图片')]) as never)
    const wrapper = await mountLib({ pickingId: 11 })
    await docClick('.olib__project')
    await settle()
    expect(wrapper.findComponent(AssetPickerRow).props('picking')).toBe(true)
    wrapper.unmount()
  })
})
