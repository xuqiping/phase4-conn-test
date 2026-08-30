import { describe, expect, it } from 'vitest'
import {
  kindsFormFromAllowed, allowedFromKindsForm,
  visFormFromOverrides, overridesFromVisForm
} from './groupPerms'

describe('groupPerms 功能开关表单互转（17x#2 V139）', () => {
  it('null=不限 → unlimited=true 且全勾；往返仍 null', () => {
    const form = kindsFormFromAllowed(null)
    expect(form.unlimited).toBe(true)
    expect(form.kinds).toEqual(['CHAT', 'EMBED', 'RERANK', 'IMAGE', 'VIDEO'])
    expect(allowedFromKindsForm(form)).toBeNull()
  })

  it('空数组=全禁 → unlimited=false 且全不勾；往返仍 []', () => {
    const form = kindsFormFromAllowed([])
    expect(form.unlimited).toBe(false)
    expect(form.kinds).toEqual([])
    expect(allowedFromKindsForm(form)).toEqual([])
  })

  it('白名单子集往返保持；未知 kind 宽容丢弃', () => {
    expect(kindsFormFromAllowed(['CHAT', 'IMAGE']).kinds).toEqual(['CHAT', 'IMAGE'])
    expect(kindsFormFromAllowed(['CHAT', 'HACK']).kinds).toEqual(['CHAT'])
    expect(allowedFromKindsForm({ unlimited: false, kinds: ['VIDEO'] })).toEqual(['VIDEO'])
  })
})

describe('groupPerms 成员可见性三态互转（17x#2 V139）', () => {
  it('null 覆盖 → 全 FOLLOW；往返 → {}（清空语义）', () => {
    const form = visFormFromOverrides(null)
    expect(form).toEqual({ CHAT: 'FOLLOW', EMBED: 'FOLLOW', RERANK: 'FOLLOW', IMAGE: 'FOLLOW', VIDEO: 'FOLLOW' })
    expect(overridesFromVisForm(form)).toEqual({})
  })

  it('稀疏覆盖 → 对应模块取值，其余 FOLLOW；往返保持稀疏', () => {
    const form = visFormFromOverrides({ VIDEO: 'ALL', CHAT: 'OWN' })
    expect(form.VIDEO).toBe('ALL')
    expect(form.CHAT).toBe('OWN')
    expect(form.IMAGE).toBe('FOLLOW')
    expect(overridesFromVisForm(form)).toEqual({ VIDEO: 'ALL', CHAT: 'OWN' })
  })

  it('未知 kind/非法值宽容当 FOLLOW', () => {
    const form = visFormFromOverrides({ HACK: 'ALL', IMAGE: 'X' } as Record<string, string>)
    expect(form.IMAGE).toBe('FOLLOW')
    expect(overridesFromVisForm(form)).toEqual({})
  })
})
