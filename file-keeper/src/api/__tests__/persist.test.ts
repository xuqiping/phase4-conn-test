import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPersistAPI } from '../persist'

const mockSet = vi.fn()
const mockGet = vi.fn()
const mockSave = vi.fn()

vi.mock('@tauri-apps/plugin-store', () => ({
  Store: {
    load: vi.fn(() => Promise.resolve({
      set: mockSet,
      get: mockGet,
      save: mockSave
    }))
  }
}))

describe('PersistAPI', () => {
  beforeEach(() => {
    mockSet.mockClear()
    mockGet.mockClear()
    mockSave.mockClear()
  })

  it('load 从存储读取数据', async () => {
    mockGet.mockResolvedValueOnce({ files: [{ id: '1', name: 'test' }] })
    const api = await createPersistAPI('test.json')
    const result = await api.load('files', [])
    expect(result).toEqual({ files: [{ id: '1', name: 'test' }] })
  })

  it('load 数据不存在时返回默认值', async () => {
    mockGet.mockResolvedValueOnce(undefined)
    const api = await createPersistAPI('test.json')
    const result = await api.load('files', [])
    expect(result).toEqual([])
  })

  it('save 写入数据到存储', async () => {
    const api = await createPersistAPI('test.json')
    await api.save('files', [{ id: '1' }])
    expect(mockSet).toHaveBeenCalledWith('files', [{ id: '1' }])
  })

  it('flush 立即调用 save 强制落盘', async () => {
    const api = await createPersistAPI('test.json')
    await api.flush()
    expect(mockSave).toHaveBeenCalled()
  })

  it('load 在加载失败时返回默认值并记录错误', async () => {
    mockGet.mockRejectedValueOnce(new Error('disk error'))
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const api = await createPersistAPI('test.json')
    const result = await api.load('files', ['default'])
    expect(result).toEqual(['default'])
    expect(consoleSpy).toHaveBeenCalled()
    consoleSpy.mockRestore()
  })
})
