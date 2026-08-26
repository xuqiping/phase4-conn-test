/**
 * 8x-2 B3：审计显示层字典对齐测试。
 * 后端 AuditLabelDictionary MODULE_LABEL 键集（17 有写入模块 + file）与前端
 * AuditLogView moduleOptions / detailLabels AUDIT_MODULE_CN 三方对齐。
 * 新增审计模块时：后端字典 + moduleOptions + AUDIT_MODULE_CN + 本清单四改。
 */
import { describe, expect, it } from 'vitest'
import { AUDIT_MODULE_CN, DETAIL_KEY_CN, DETAIL_VALUE_CN } from './detailLabels'

/** 与后端 AuditLabelDictionaryCompletenessTest.KNOWN_CODES 模块集同源（B4 后 19 模块）。 */
const KNOWN_MODULES = [
  'auth', 'user', 'role', 'agent', 'kb', 'system', 'billing', 'asset',
  'memory', 'media', 'llm', 'chat', 'canvas', 'file',
  'security', 'feedback', 'project-group', 'audit', 'workflow'
]

describe('8x-2 B3 审计字典对齐', () => {
  it('AUDIT_MODULE_CN 覆盖全部 19 个后端模块且无幽灵码', () => {
    expect(Object.keys(AUDIT_MODULE_CN).sort()).toEqual([...KNOWN_MODULES].sort())
  })

  it('P0 手工行 detail 键全有中文（B1：email/phone/ip/identifier/hit/openid/userId/channel）', () => {
    for (const key of ['email', 'phone', 'ip', 'identifier', 'hit', 'openid', 'userId', 'channel']) {
      expect(DETAIL_KEY_CN[key], `缺 key: ${key}`).toBeTruthy()
      expect(DETAIL_KEY_CN[key]).not.toBe(key)
    }
  })

  it('P0 手工行 reason 码全有中文（B1 失败原因枚举）', () => {
    for (const code of [
      'verification_off', 'token_blank', 'token_invalid', 'redis_error', 'email_blank',
      'no_verified_phone', 'no_resettable_email', 'already_verified',
      'code_active', 'code_wrong', 'already_bound'
    ]) {
      expect(DETAIL_VALUE_CN[code], `缺 reason 码: ${code}`).toBeTruthy()
    }
  })
})
