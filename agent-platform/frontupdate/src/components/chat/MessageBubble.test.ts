import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import MessageBubble from './MessageBubble.vue'
import type { ChatMessage } from '@/api/chat'

const messageMock = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }
vi.mock('naive-ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('naive-ui')>()
  return { ...actual, useMessage: () => messageMock }
})

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    documentAssetUrl: (id: number) => `/api/knowledge/documents/${id}/asset`
  }
}))

vi.mock('@/api/file', () => ({
  fetchFilePreview: vi.fn()
}))

function msg(citations: unknown[]): ChatMessage {
  return {
    id: 1,
    sessionId: 1,
    role: 'ASSISTANT',
    content: '答案 [1]',
    metadata: JSON.stringify({ citations }),
    createdAt: '2026-08-18T00:00:00Z'
  }
}

async function mountBubble(citations: unknown[]) {
  const wrapper = mount(MessageBubble, { props: { message: msg(citations) } })
  await flushPromises()
  return wrapper
}

/** 14x#3：保密库引用（confidential=true）→ 缩略图/下载入口隐藏，引用条目本身保留（标题可见） */
describe('MessageBubble · 保密引用原件入口隐藏', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('保密 FILE 引用：无下载 chip，标题仍在', async () => {
    const wrapper = await mountBubble([
      { index: 1, documentId: 9, title: '秘密文件', docType: 'FILE', fileRef: '/api/files/f1', confidential: true }
    ])
    expect(wrapper.find('.message-bubble__citation-download').exists()).toBe(false)
    expect(wrapper.text()).toContain('秘密文件')
  })

  it('保密 IMAGE 引用：无缩略图', async () => {
    const wrapper = await mountBubble([
      { index: 1, documentId: 9, title: '秘密图', docType: 'IMAGE', fileRef: '/api/files/img1', confidential: true }
    ])
    expect(wrapper.find('.message-bubble__citation-thumb').exists()).toBe(false)
  })

  it('非保密引用：FILE 下载 chip / IMAGE 缩略图照常渲染（owner 视角标志为 false）', async () => {
    const wrapper = await mountBubble([
      { index: 1, documentId: 9, title: '普通文件', docType: 'FILE', fileRef: '/api/files/f2', confidential: false },
      { index: 2, documentId: 10, title: '普通图', docType: 'IMAGE', fileRef: '/api/files/img2', confidential: false }
    ])
    expect(wrapper.find('.message-bubble__citation-download').exists()).toBe(true)
    expect(wrapper.find('.message-bubble__citation-thumb').exists()).toBe(true)
  })
})
