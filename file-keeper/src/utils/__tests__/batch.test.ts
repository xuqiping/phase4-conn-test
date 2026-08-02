import { describe, it, expect } from 'vitest'
import { canBatchOpen, canBatchDelete, canBatchMove } from '../batch'
import type { FileItem } from '../../types/file'

describe('batch utilities', () => {
  const mockFiles: FileItem[] = [
    {
      id: '1',
      name: 'test1.txt',
      path: '/path/to/test1.txt',
      type: 'file',
      tags: [],
      groupId: 'all',
      createdAt: Date.now(),
      openCount: 0
    },
    {
      id: '2',
      name: 'test2.txt',
      path: '/path/to/test2.txt',
      type: 'file',
      tags: [],
      groupId: 'all',
      createdAt: Date.now(),
    openCount: 0
    }
  ]

  it('should allow batch open for any files', () => {
    expect(canBatchOpen(mockFiles)).toBe(true)
    expect(canBatchOpen([])).toBe(false)
  })

  it('should allow batch delete for any files', () => {
    expect(canBatchDelete(mockFiles)).toBe(true)
    expect(canBatchDelete([])).toBe(false)
  })

  it('should allow batch move for any files', () => {
    expect(canBatchMove(mockFiles)).toBe(true)
    expect(canBatchMove([])).toBe(false)
  })
})
