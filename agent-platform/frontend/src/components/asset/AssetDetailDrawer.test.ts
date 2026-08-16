import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import AssetDetailDrawer from './AssetDetailDrawer.vue'
import { assetApi, assetBridgeApi, versionApi, scriptApi, scoreApi } from '@/api/assets'
import type { AxiosResponse } from 'axios'
import type { AssetVO } from '@/types/asset'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/canvas', () => ({
  fetchCanvasPreview: vi.fn().mockResolvedValue('blob:preview')
}))

vi.mock('@/api/llm', () => ({
  llmApi: {
    listAvailableModels: vi.fn().mockResolvedValue({
      data: { code: 200, message: 'ok', data: [{ modelId: 'doubao-seed-2.0-code', displayName: '豆包代码', providerName: 'p', source: 'global' }] }
    })
  }
}))

const { requestGet } = vi.hoisted(() => ({ requestGet: vi.fn() }))
vi.mock('@/api/request', () => ({ default: { get: requestGet }, request: { get: requestGet } }))

vi.mock('@/api/assets', () => ({
  assetApi: { get: vi.fn(), remove: vi.fn() },
  assetBridgeApi: { usages: vi.fn() },
  versionApi: { lock: vi.fn(), unlock: vi.fn(), archive: vi.fn(), unarchive: vi.fn(), create: vi.fn() },
  scriptApi: { breakdown: vi.fn(), breakdownStoryboard: vi.fn() },
  scoreApi: { mine: vi.fn(), submit: vi.fn() },
  memberApi: { searchCandidates: vi.fn() },
  projectApi: { updateSettings: vi.fn() }
}))

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: {} as never } }
}

function mkAsset(over: Partial<AssetVO> = {}): AssetVO {
  return {
    id: 5,
    projectId: 7,
    mediaType: '提示词',
    mediaCategory: 'TEXT',
    name: '提示词A',
    description: 'desc',
    tags: ['t1'],
    status: 'DRAFT',
    content: '{"k":"v"}',
    genMeta: null,
    currentVersion: 1,
    roleKeys: ['人物'],
    fileId: undefined,
    createdAt: '2026-08-05',
    ...over
  }
}

async function settle() {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

async function mountDrawer(
  props: Partial<{
    show: boolean
    assetId: number | null
    canEdit: boolean
    canScore: boolean
    personalMode: boolean
    currentUserId: number | null
  }> = {}
) {
  const wrapper = mount(AssetDetailDrawer, {
    props: { show: true, assetId: 5, canEdit: true, ...props }
  })
  await settle()
  return wrapper
}

describe('AssetDetailDrawer (S10-10a)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(assetApi.get).mockResolvedValue(response({ code: 200, message: 'ok', data: mkAsset() }))
    vi.mocked(assetBridgeApi.usages).mockResolvedValue(response({ code: 200, message: 'ok', data: [] }))
    vi.mocked(scoreApi.mine).mockResolvedValue(
      response({ code: 200, message: 'ok', data: { myScore: null, ownerScore: null, memberAvgScore: null, memberCount: 0 } })
    )
  })

  it('加载资产 + usages', async () => {
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { asset: AssetVO | null }
    expect(assetApi.get).toHaveBeenCalledWith(5)
    expect(assetBridgeApi.usages).toHaveBeenCalledWith(5)
    expect(vm.asset?.id).toBe(5)
  })

  it('IMAGE 资产拉预览 objectURL', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ mediaType: '图片', mediaCategory: 'IMAGE', fileId: 'fid-1' }) })
    )
    const { fetchCanvasPreview } = await import('@/api/canvas')
    const wrapper = await mountDrawer()
    expect(fetchCanvasPreview).toHaveBeenCalledWith('fid-1')
    expect((wrapper.vm as unknown as { previewUrl: string | null }).previewUrl).toBe('blob:preview')
  })

  it('定稿调 versionApi.lock + emit changed（L2）', async () => {
    vi.mocked(versionApi.lock).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ status: 'LOCKED' }) })
    )
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { doAction: (a: 'lock' | 'unlock' | 'archive' | 'unarchive') => Promise<void> }
    await vm.doAction('lock')
    expect(versionApi.lock).toHaveBeenCalledWith(5)
    expect(wrapper.emitted('changed')).toBeTruthy()
    expect((wrapper.vm as unknown as { asset: AssetVO | null }).asset?.status).toBe('LOCKED')
  })

  it('状态机返 meta-only（content/fileId=null）→ 保留抽屉已加载值不丢失（FIX-B）', async () => {
    // 初始加载 IMAGE 资产带 fileId + content
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ mediaType: '图片', mediaCategory: 'IMAGE', fileId: 'fid-1', content: '{"k":"v"}' }) })
    )
    // lock 返 meta-only：content=null + fileId=null（懒加载语义）
    vi.mocked(versionApi.lock).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ status: 'LOCKED', content: null as unknown as string, fileId: null as unknown as string }) })
    )
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { doAction: (a: 'lock') => Promise<void>; asset: AssetVO | null }
    await vm.doAction('lock')
    expect(vm.asset?.status).toBe('LOCKED')
    // 关键：保留旧值，不显「无正文」/下载不失效
    expect(vm.asset?.content).toBe('{"k":"v"}')
    expect(vm.asset?.fileId).toBe('fid-1')
  })

  it('归档调 versionApi.archive（L3）', async () => {
    vi.mocked(versionApi.archive).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ status: 'ARCHIVED' }) })
    )
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { doAction: (a: 'lock' | 'unlock' | 'archive' | 'unarchive') => Promise<void> }
    await vm.doAction('archive')
    expect(versionApi.archive).toHaveBeenCalledWith(5)
  })

  it('下载调 request.get blob', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ mediaType: '视频', mediaCategory: 'VIDEO', fileId: 'fid-2' }) })
    )
    requestGet.mockResolvedValue(response(new Blob(['x'])))
    const wrapper = await mountDrawer()

    // 挂载后再 spy createElement，且仅对 'a' 返假 anchor（避免破坏 Vue 渲染）
    const clickSpy = vi.fn()
    const anchor = { click: clickSpy, remove: vi.fn(), href: '', download: '' } as unknown as HTMLAnchorElement
    const realCreate = document.createElement.bind(document)
    const createSpy = vi.spyOn(document, 'createElement').mockImplementation((tag: string) =>
      tag === 'a' ? anchor : realCreate(tag)
    )
    vi.spyOn(document.body, 'appendChild').mockImplementation(() => anchor)

    const vm = wrapper.vm as unknown as { download: () => Promise<void> }
    await vm.download()
    expect(requestGet).toHaveBeenCalledWith('/files/fid-2', { responseType: 'blob' })
    expect(clickSpy).toHaveBeenCalled()
    createSpy.mockRestore()
  })

  // ---------- C3 剧本 UI（正文编辑 + AI 分场 + 分场渲染） ----------

  it('AC-C3-1 SCRIPT 资产 → 解析 synopsis + 渲染分场列表（不再 JSON dump）', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({
        code: 200, message: 'ok',
        data: mkAsset({
          mediaType: '剧本',
          content: JSON.stringify({
            synopsis: '主角登场',
            scenes: [{ index: 1, description: '开场' }, { index: 2, description: '高潮' }]
          })
        })
      })
    )
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { synopsis: string; scenes: { index: number }[] }
    expect(vm.synopsis).toBe('主角登场')
    expect(vm.scenes).toHaveLength(2)
    // n-drawer teleport 到 body，DOM 查询走 document.body
    expect(document.body.querySelectorAll('.script-scenes__item')).toHaveLength(2)
  })

  it('AC-C3-2 saveSynopsis → versionApi.create 写 {synopsis} 新版本', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ mediaType: '剧本', content: JSON.stringify({ synopsis: '旧文' }) }) })
    )
    vi.mocked(versionApi.create).mockResolvedValue(response({ code: 200, message: 'ok', data: 2 }))
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { synopsis: string; saveSynopsis: () => Promise<void> }
    vm.synopsis = '新文'
    await vm.saveSynopsis()
    expect(versionApi.create).toHaveBeenCalledWith(5, {
      content: JSON.stringify({ synopsis: '新文' }),
      changeNote: '编辑剧本正文'
    })
    expect(messageMock.success).toHaveBeenCalled()
  })

  it('AC-C3-3 runBreakdown：正文脏 → 警告不调；干净 → 调 scriptApi.breakdown', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ mediaType: '剧本', content: JSON.stringify({ synopsis: '原文' }) }) })
    )
    vi.mocked(scriptApi.breakdown).mockResolvedValue(response({ code: 200, message: 'ok', data: { version: 2, scenes: [] } }))
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { synopsis: string; runBreakdown: () => Promise<void> }
    // 正文脏（改未保存）→ 警告，不调
    vm.synopsis = '改了'
    await vm.runBreakdown()
    expect(scriptApi.breakdown).not.toHaveBeenCalled()
    expect(messageMock.warning).toHaveBeenCalled()
    // 回到原文（干净）→ 调
    messageMock.warning.mockClear()
    vm.synopsis = '原文'
    await vm.runBreakdown()
    expect(scriptApi.breakdown).toHaveBeenCalledWith(5, undefined)
  })

  it('AC-C3-4 选中拆解模型 → runBreakdown 透传 model', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ mediaType: '剧本', content: JSON.stringify({ synopsis: '原文' }) }) })
    )
    vi.mocked(scriptApi.breakdown).mockResolvedValue(response({ code: 200, message: 'ok', data: { version: 2, scenes: [] } }))
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { scriptModel: string | null; runBreakdown: () => Promise<void> }
    vm.scriptModel = 'gpt-4o-mini'
    await vm.runBreakdown()
    expect(scriptApi.breakdown).toHaveBeenCalledWith(5, 'gpt-4o-mini')
  })

  // ---------- S19 拆解规范 + 一键分镜（plan §S19） ----------

  it('AC-S19-1 解析 content.template → 拆解规范草稿', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({
        code: 200, message: 'ok',
        data: mkAsset({ mediaType: '剧本', content: JSON.stringify({ synopsis: '正文', template: '每镜含景别' }) })
      })
    )
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { templateDraft: string }
    expect(vm.templateDraft).toBe('每镜含景别')
  })

  it('AC-S19-2 saveTemplate → versionApi.create 写 content.template（保留 synopsis）', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({
        code: 200, message: 'ok',
        data: mkAsset({ mediaType: '剧本', content: JSON.stringify({ synopsis: '正文' }) })
      })
    )
    vi.mocked(versionApi.create).mockResolvedValue(response({ code: 200, message: 'ok', data: 2 }))
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { templateDraft: string; saveTemplate: () => Promise<void> }
    vm.templateDraft = '新规范'
    await vm.saveTemplate()
    expect(versionApi.create).toHaveBeenCalledWith(5, {
      content: JSON.stringify({ synopsis: '正文', template: '新规范' }),
      changeNote: '编辑拆解规范'
    })
    expect(messageMock.success).toHaveBeenCalled()
  })

  it('AC-S19-3 runStoryboardBreakdown：正文脏→警告不调；干净→调 scriptApi.breakdownStoryboard + 显 N 个', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ mediaType: '剧本', content: JSON.stringify({ synopsis: '原文' }) }) })
    )
    vi.mocked(scriptApi.breakdownStoryboard).mockResolvedValue(
      response({ code: 200, message: 'ok', data: { count: 3, createdAssetIds: [100, 101, 102], model: 'm', version: 2 } })
    )
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { synopsis: string; runStoryboardBreakdown: () => Promise<void> }
    vm.synopsis = '改了'
    await vm.runStoryboardBreakdown()
    expect(scriptApi.breakdownStoryboard).not.toHaveBeenCalled()
    expect(messageMock.warning).toHaveBeenCalled()
    messageMock.warning.mockClear()
    vm.synopsis = '原文'
    await vm.runStoryboardBreakdown()
    expect(scriptApi.breakdownStoryboard).toHaveBeenCalledWith(5, undefined)
    expect(messageMock.success).toHaveBeenCalledWith('已生成 3 个分镜资产')
  })

  it('AC-S19-4 选中拆解模型 → runStoryboardBreakdown 透传 model', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ mediaType: '剧本', content: JSON.stringify({ synopsis: '原文' }) }) })
    )
    vi.mocked(scriptApi.breakdownStoryboard).mockResolvedValue(
      response({ code: 200, message: 'ok', data: { count: 1, createdAssetIds: [1], model: 'm', version: 2 } })
    )
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { synopsis: string; scriptModel: string | null; runStoryboardBreakdown: () => Promise<void> }
    vm.scriptModel = 'gpt-4o-mini'
    await vm.runStoryboardBreakdown()
    expect(scriptApi.breakdownStoryboard).toHaveBeenCalledWith(5, 'gpt-4o-mini')
  })

  // ---------- S15 提示词/非剧本 TEXT 编辑器 + 删除（Bug①②） ----------

  it('AC-S15-1 PROMPT 资产 → 解析 content.body 为正文草稿（不再 JSON dump 只读）', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ mediaType: '提示词', content: JSON.stringify({ body: '原文正文' }) }) })
    )
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { textBody: string }
    expect(vm.textBody).toBe('原文正文')
  })

  it('AC-S15-2 saveTextBody → versionApi.create 写 {body} 新版本（保留其他键）', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({
        code: 200, message: 'ok',
        data: mkAsset({ mediaType: '提示词', content: JSON.stringify({ body: '旧', extras: 'keep' }) })
      })
    )
    vi.mocked(versionApi.create).mockResolvedValue(response({ code: 200, message: 'ok', data: 2 }))
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { textBody: string; saveTextBody: () => Promise<void> }
    vm.textBody = '新正文'
    await vm.saveTextBody()
    expect(versionApi.create).toHaveBeenCalledWith(5, {
      content: JSON.stringify({ body: '新正文', extras: 'keep' }),
      changeNote: '编辑正文'
    })
    expect(messageMock.success).toHaveBeenCalled()
  })

  it('AC-S15-3 deleteAsset → assetApi.remove + emit changed + 关抽屉（L11）', async () => {
    vi.mocked(assetApi.remove).mockResolvedValue(response({ code: 200, message: 'ok', data: undefined }))
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { deleteAsset: () => Promise<void> }
    await vm.deleteAsset()
    expect(assetApi.remove).toHaveBeenCalledWith(5)
    expect(wrapper.emitted('changed')).toBeTruthy()
    const shows = wrapper.emitted('update:show')
    expect(shows && shows[shows.length - 1][0]).toBe(false)
  })

  it('AC-S15-4 非法 JSON 旧 content → readTextBody 兜底裸文本不崩', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ mediaType: '提示词', content: '裸文本内容' }) })
    )
    const wrapper = await mountDrawer()
    const vm = wrapper.vm as unknown as { textBody: string }
    expect(vm.textBody).toBe('裸文本内容')
  })

  // ---------- C7 评分区 + PERSONAL 门控（2x第三轮） ----------

  it('C7-1 打开抽屉并行拉评分聚合（scoreApi.mine）+ 我的分回显草稿', async () => {
    vi.mocked(scoreApi.mine).mockResolvedValue(
      response({ code: 200, message: 'ok', data: { myScore: 80, ownerScore: 88, memberAvgScore: 90, memberCount: 3 } })
    )
    const wrapper = await mountDrawer()
    expect(scoreApi.mine).toHaveBeenCalledWith(5)
    const vm = wrapper.vm as unknown as { scoreInfo: { myScore: number | null }; myScoreDraft: number | null }
    expect(vm.scoreInfo?.myScore).toBe(80)
    expect(vm.myScoreDraft).toBe(80)
  })

  it('C7-2 双轨评分展示：拥有者 ★88 + 成员均分 90 · 3人（teleport 到 body）', async () => {
    vi.mocked(scoreApi.mine).mockResolvedValue(
      response({ code: 200, message: 'ok', data: { myScore: null, ownerScore: 88, memberAvgScore: 90, memberCount: 3 } })
    )
    await mountDrawer()
    expect(document.body.textContent).toContain('拥有者 ★88')
    expect(document.body.textContent).toContain('成员均分 90 · 3人')
  })

  it('C7-3 canScore → submitScore 调 scoreApi.submit + 成功刷新聚合 + emit changed（L5）', async () => {
    vi.mocked(scoreApi.submit).mockResolvedValue(
      response({ code: 200, message: 'ok', data: { myScore: 75, ownerScore: null, memberAvgScore: 75, memberCount: 1 } })
    )
    const wrapper = await mountDrawer({ canScore: true })
    const vm = wrapper.vm as unknown as { myScoreDraft: number | null; submitScore: () => Promise<void>; scoreInfo: { myScore: number | null } }
    vm.myScoreDraft = 75
    await vm.submitScore()
    expect(scoreApi.submit).toHaveBeenCalledWith(5, 75)
    expect(vm.scoreInfo?.myScore).toBe(75)
    expect(messageMock.success).toHaveBeenCalled()
    expect(wrapper.emitted('changed')).toBeTruthy()
  })

  it('C7-4 PERSONAL 非本人内容 → canModify=false 隐藏状态机动作组（canEdit 仍 true）', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ createdBy: 999 }) })
    )
    // teleport 内容跨测试残留 body → 用增量计数断言（本次挂载不新增动作组）
    const actionsBefore = document.body.querySelectorAll('.asset-detail__actions').length
    const wrapper = await mountDrawer({ canEdit: true, personalMode: true, currentUserId: 1 })
    const vm = wrapper.vm as unknown as { canModify: boolean; canOperateThis: boolean }
    expect(vm.canOperateThis).toBe(false)
    expect(vm.canModify).toBe(false)
    expect(document.body.querySelectorAll('.asset-detail__actions').length).toBe(actionsBefore)
  })

  it('C7-5 PERSONAL 本人内容 → canModify=true 正常操作', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ createdBy: 1 }) })
    )
    const wrapper = await mountDrawer({ canEdit: true, personalMode: true, currentUserId: 1 })
    const vm = wrapper.vm as unknown as { canModify: boolean }
    expect(vm.canModify).toBe(true)
  })

  it('C7-6 SHARED 模式零回归 → personalMode 缺省 canModify 恒等于 canEdit', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ createdBy: 999 }) })
    )
    const wrapper = await mountDrawer({ canEdit: true })
    const vm = wrapper.vm as unknown as { canModify: boolean }
    expect(vm.canModify).toBe(true)
  })

  it('C7-7 上传者展示：username 优先，缺失回退 #id', async () => {
    vi.mocked(assetApi.get).mockResolvedValue(
      response({ code: 200, message: 'ok', data: mkAsset({ createdBy: 42, createdByUsername: 'zhang3' }) })
    )
    await mountDrawer()
    expect(document.body.textContent).toContain('zhang3')
  })
})
