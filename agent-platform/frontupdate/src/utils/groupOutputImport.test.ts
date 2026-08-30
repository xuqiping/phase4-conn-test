import { describe, expect, it } from 'vitest'
import { isImportable, parseImportedSet } from './groupOutputImport'

/** 修复III F2（17x#1）：组产出「入库」列判定与已入库态解析。 */
describe('groupOutputImport', () => {
  describe('isImportable（与预览列同口径：SUCCEEDED 才有产物 fileId）', () => {
    it('IMAGE：有 imageFileIds 可入库，空数组/空值不可', () => {
      expect(isImportable({ kind: 'IMAGE', imageFileIds: ['f1'], resultFileId: null, chatResult: null })).toBe(true)
      expect(isImportable({ kind: 'IMAGE', imageFileIds: [], resultFileId: null, chatResult: null })).toBe(false)
      expect(isImportable({ kind: 'IMAGE', imageFileIds: null, resultFileId: null, chatResult: null })).toBe(false)
    })

    it('VIDEO：有 resultFileId 可入库，无则不可（未成功/不可见）', () => {
      expect(isImportable({ kind: 'VIDEO', imageFileIds: null, resultFileId: 'vf-1', chatResult: null })).toBe(true)
      expect(isImportable({ kind: 'VIDEO', imageFileIds: null, resultFileId: null, chatResult: null })).toBe(false)
    })

    it('CHAT：有 assistant 回复可入库，无回复（预估行/失败）不可', () => {
      expect(isImportable({ kind: 'CHAT', imageFileIds: null, resultFileId: null, chatResult: '内容' })).toBe(true)
      expect(isImportable({ kind: 'CHAT', imageFileIds: null, resultFileId: null, chatResult: null })).toBe(false)
      expect(isImportable({ kind: 'CHAT', imageFileIds: null, resultFileId: null, chatResult: '' })).toBe(false)
    })

    it('EMBED/RERANK 恒不可入库（无产物文件语义）', () => {
      expect(isImportable({ kind: 'EMBED', imageFileIds: null, resultFileId: 'x', chatResult: 'x' })).toBe(false)
      expect(isImportable({ kind: 'RERANK', imageFileIds: ['x'], resultFileId: 'x', chatResult: 'x' })).toBe(false)
    })
  })

  describe('parseImportedSet（JSON 字符串键 → taskId 集合）', () => {
    it('数字键转集合（后端 Map<Long,Long> 序列化键为字符串）', () => {
      expect(parseImportedSet({ '7': 11, '9': 13 })).toEqual(new Set([7, 9]))
    })

    it('脏键忽略、空入参空集', () => {
      expect(parseImportedSet({ 'abc': 1, '-1': 2, '1.5': 3, '12': 4 })).toEqual(new Set([12]))
      expect(parseImportedSet(null)).toEqual(new Set())
      expect(parseImportedSet(undefined)).toEqual(new Set())
      expect(parseImportedSet({})).toEqual(new Set())
    })
  })
})
