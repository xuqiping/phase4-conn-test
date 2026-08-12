// ============================================================
// 二期 P3（FR-203）· MessageFileCard 单测
// 断言：名称/类型/块数渲染；原文件已删除 → 禁下载/展开；展开分块懒加载页码锚点。
// ============================================================
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import MessageFileCard from './MessageFileCard.vue'
import type { RecalledFileCard } from '@/api/memory'

const listAttachmentChunks = vi.fn()
vi.mock('@/api/memory', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/memory')>()
  return {
    ...original,
    memoryApi: {
      listAttachmentChunks: (...args: unknown[]) => listAttachmentChunks(...args)
    }
  }
})
vi.mock('@/api/file', () => ({
  fetchFilePreview: vi.fn().mockResolvedValue('blob:mock')
}))

function card(partial: Partial<RecalledFileCard> = {}): RecalledFileCard {
  return {
    memoryId: 7,
    fileId: 'f-abc.pdf',
    originalName: '课件.pdf',
    fileKind: 'PDF',
    chunkCount: 12,
    weakMemory: false,
    fileCleaned: false,
    downloadable: true,
    l1: '《课件.pdf》：讲 hooks 原理',
    l2: null,
    ...partial
  }
}

describe('MessageFileCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders name, kind label, chunk count and l1 summary', () => {
    const wrapper = mount(MessageFileCard, { props: { card: card() } })

    expect(wrapper.text()).toContain('课件.pdf')
    expect(wrapper.text()).toContain('PDF 文档')
    expect(wrapper.text()).toContain('共 12 块')
    expect(wrapper.text()).toContain('讲 hooks 原理')
    expect(wrapper.text()).toContain('⬇ 下载')
  })

  it('cleaned file hides download/expand and shows 原文件已删除', () => {
    const wrapper = mount(MessageFileCard, {
      props: { card: card({ fileCleaned: true, downloadable: false }) }
    })

    expect(wrapper.text()).toContain('原文件已删除')
    expect(wrapper.text()).not.toContain('⬇ 下载')
    expect(wrapper.text()).not.toContain('展开分块')
  })

  it('expand lazily loads chunks with page anchors', async () => {
    listAttachmentChunks.mockResolvedValue({
      data: { data: [{ chunkNo: 3, pageRef: '第3页', chunkText: 'hooks 的依赖数组' }] }
    })
    const wrapper = mount(MessageFileCard, { props: { card: card() } })

    const expandBtn = wrapper.findAll('button').find(b => b.text().includes('展开分块'))!
    await expandBtn.trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('hooks 的依赖数组'))

    expect(listAttachmentChunks).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('[第3页]')
  })
})
