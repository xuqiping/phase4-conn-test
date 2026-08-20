import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useProjectGroupStore } from './projectGroup'

// mock 两个 API 模块（store 只关心 R<...> 的 data.data 形状）
vi.mock('@/api/projectGroup', () => ({
  projectGroupApi: { mine: vi.fn() }
}))
vi.mock('@/api/billing', () => ({
  billingApi: { myWallet: vi.fn() }
}))

import { projectGroupApi } from '@/api/projectGroup'
import { billingApi } from '@/api/billing'
const mockedMine = vi.mocked(projectGroupApi.mine)
const mockedWallet = vi.mocked(billingApi.myWallet)

function r<T>(data: T) {
  return { data: { code: 200, msg: 'success', data } }
}

describe('projectGroup store（7x 统一入口）', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('默认 null=个人钱包计费，不写持久化键', () => {
    const s = useProjectGroupStore()
    expect(s.groupId).toBeNull()
    expect(localStorage.getItem('project_group_id')).toBeNull()
  })

  it('setGroup 写全局并持久化单键', () => {
    const s = useProjectGroupStore()
    s.setGroup(3)
    expect(s.groupId).toBe(3)
    expect(localStorage.getItem('project_group_id')).toBe('3')
    s.setGroup(null)
    expect(s.groupId).toBeNull()
    expect(localStorage.getItem('project_group_id')).toBe('null')
  })

  it('刷新后从 localStorage 恢复（单键唯一真相）', () => {
    localStorage.setItem('project_group_id', '7')
    const s = useProjectGroupStore()
    expect(s.groupId).toBe(7)
  })

  it('adoptLegacy：全局未设且候选有值→收养第一个非空并持久化', () => {
    const s = useProjectGroupStore()
    s.adoptLegacy(null, undefined, 5, 9)
    expect(s.groupId).toBe(5)
    expect(localStorage.getItem('project_group_id')).toBe('5')
  })

  it('adoptLegacy：全局已设→不收养（页顶全局胜出）', () => {
    localStorage.setItem('project_group_id', '2')
    const s = useProjectGroupStore()
    s.adoptLegacy(5)
    expect(s.groupId).toBe(2)
  })

  it('adoptLegacy：全空候选→保持 null 不写键', () => {
    const s = useProjectGroupStore()
    s.adoptLegacy(null, undefined)
    expect(s.groupId).toBeNull()
    expect(localStorage.getItem('project_group_id')).toBeNull()
  })

  it('loadGroups/loadWallet 填充列表与个人积分；currentGroup/groupBalance 派生', async () => {
    mockedMine.mockResolvedValue(r([
      { id: 1, name: 'A组', balancePoints: 120.5, quotaPoints: 1000 },
      { id: 2, name: 'B组', balancePoints: 80, quotaPoints: 500 }
    ]) as never)
    mockedWallet.mockResolvedValue(r({ balance: 666, recentLedger: [] }) as never)
    const s = useProjectGroupStore()
    await s.init()
    expect(s.groups).toHaveLength(2)
    expect(s.personalPoints).toBe(666)
    s.setGroup(1)
    expect(s.currentGroup?.name).toBe('A组')
    expect(s.groupBalance).toBe(120.5)
    s.setGroup(null)
    expect(s.currentGroup).toBeNull()
    expect(s.groupBalance).toBeNull()
  })

  it('API 失败静默降级：groups 空 + personalPoints null（徽标隐藏不崩）', async () => {
    mockedMine.mockRejectedValue(new Error('boom') as never)
    mockedWallet.mockRejectedValue(new Error('boom') as never)
    const s = useProjectGroupStore()
    await s.init()
    expect(s.groups).toEqual([])
    expect(s.personalPoints).toBeNull()
    expect(s.loadedGroups).toBe(true)
    expect(s.loadedWallet).toBe(true)
  })
})
