import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useProjectGroupStore } from './projectGroup'

// mock 两个 API 模块（store 只关心 R<...> 的 data.data 形状）
vi.mock('@/api/projectGroup', () => ({
  projectGroupApi: { mine: vi.fn() }
}))
vi.mock('@/api/billing', () => ({
  billingApi: { myWallet: vi.fn() }
}))
// 修复VIII B2：events WS 4401 处理链依赖（单飞刷新/跳登录）——mock 掉不发真请求
vi.mock('@/api/request', () => ({
  default: {},
  tryRefreshAccessToken: vi.fn().mockResolvedValue(null),
  redirectToLogin: vi.fn()
}))

import { projectGroupApi } from '@/api/projectGroup'
import { billingApi } from '@/api/billing'
import { tryRefreshAccessToken, redirectToLogin } from '@/api/request'
const mockedMine = vi.mocked(projectGroupApi.mine)
const mockedWallet = vi.mocked(billingApi.myWallet)
const mockedRefresh = vi.mocked(tryRefreshAccessToken)
const mockedRedirect = vi.mocked(redirectToLogin)

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

// ============================================================
// 修复VIII B2（VIII-3）：/ws/events 首消息鉴权——token 出 URL 改走首帧载荷；
// auth_ok 前业务帧忽略；重连补拉移到 auth_ok 后；4401 单飞刷新一次重连、再失败跳登录。
// （真实 '@/utils/storage'：token 需 JSON 引号形式落 localStorage）
// ============================================================

/** 测试替身 WebSocket：记录 URL 与已发帧，由测试手动驱动 open/message/close。 */
class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  static CONNECTING = 0
  static OPEN = 1
  static CLOSING = 2
  static CLOSED = 3
  url: string
  readyState = 0
  sent: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((ev: { data: string }) => void) | null = null
  onclose: ((ev: { code: number }) => void) | null = null
  onerror: (() => void) | null = null

  constructor(url: string) {
    this.url = url
    FakeWebSocket.instances.push(this)
  }

  send(data: string) {
    this.sent.push(data)
  }

  close() {
    this.readyState = 3
  }

  // ---- 测试驱动（模拟服务端行为）----
  open() {
    this.readyState = 1
    this.onopen?.()
  }

  serverMessage(data: unknown) {
    this.onmessage?.({ data: JSON.stringify(data) })
  }

  serverClose(code: number) {
    this.readyState = 3
    this.onclose?.({ code })
  }
}

describe('projectGroup store /ws/events 首消息鉴权（修复VIII B2）', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockedRefresh.mockResolvedValue(null)
    FakeWebSocket.instances = []
    vi.stubGlobal('WebSocket', FakeWebSocket)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  /** 登录态 + init 拉底数（mine 一次 + wallet 一次），返回已启动的 store。 */
  async function bootStore(walletBalance = 100) {
    localStorage.setItem('access_token', '"tok1"')
    mockedMine.mockResolvedValue(r([
      { id: 2, name: 'G组', balancePoints: 50, quotaPoints: 500 }
    ]) as never)
    mockedWallet.mockResolvedValue(r({ balance: walletBalance, recentLedger: [] }) as never)
    const s = useProjectGroupStore()
    await s.init()
    return s
  }

  function expectedEventsUrl() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${protocol}//${window.location.host}/ws/events`
  }

  it('连接 URL 不带 token，open 后首帧发 {type:"auth"}（token 走载荷不进 URL/日志面）', async () => {
    await bootStore()
    expect(FakeWebSocket.instances).toHaveLength(1)
    const sock = FakeWebSocket.instances[0]
    expect(sock.url).toBe(expectedEventsUrl())
    expect(sock.url).not.toContain('token')

    sock.open()
    expect(sock.sent).toHaveLength(1)
    expect(JSON.parse(sock.sent[0])).toEqual({ type: 'auth', token: 'tok1' })
  })

  it('auth_ok 前业务帧忽略；auth_ok 后 connected=true 且 points.changed 徽标秒跳', async () => {
    const s = await bootStore()
    const sock = FakeWebSocket.instances[0]
    sock.open()

    // 未认证期到达的推送不生效、不算已连接
    sock.serverMessage({ type: 'points.changed', scope: 'PERSONAL', balanceAfter: 999 })
    expect(s.eventsConnected).toBe(false)
    expect(s.personalPoints).toBe(100)

    sock.serverMessage({ type: 'auth_ok' })
    expect(s.eventsConnected).toBe(true)

    sock.serverMessage({ type: 'points.changed', scope: 'PERSONAL', balanceAfter: 999 })
    expect(s.personalPoints).toBe(999)
  })

  it('断线重连：补拉 loadWallet/loadGroups 发生在 auth_ok 后（不提前不漏窗口）', async () => {
    vi.useFakeTimers()
    const s = await bootStore()
    const first = FakeWebSocket.instances[0]
    first.open()
    // init 已各拉一次；非鉴权关闭码走退避重连
    expect(mockedWallet).toHaveBeenCalledTimes(1)
    expect(mockedMine).toHaveBeenCalledTimes(1)
    first.serverClose(1006)

    vi.advanceTimersByTime(1000) // 1s 退避 → 重连
    expect(FakeWebSocket.instances).toHaveLength(2)
    const second = FakeWebSocket.instances[1]
    second.open()

    // auth_ok 前：connected 不置位、不补拉
    expect(s.eventsConnected).toBe(false)
    expect(mockedWallet).toHaveBeenCalledTimes(1)
    expect(mockedMine).toHaveBeenCalledTimes(1)

    // auth_ok 后：断线漏推窗口 → 强制全量补拉一次
    second.serverMessage({ type: 'auth_ok' })
    expect(s.eventsConnected).toBe(true)
    expect(mockedWallet).toHaveBeenCalledTimes(2)
    expect(mockedMine).toHaveBeenCalledTimes(2)
  })

  it('4401：单飞刷新一次 → 新 token 重连重走首消息鉴权', async () => {
    mockedRefresh.mockResolvedValue('new-tok')
    await bootStore()
    const first = FakeWebSocket.instances[0]
    first.open()
    first.serverClose(4401)

    expect(mockedRefresh).toHaveBeenCalledTimes(1)
    localStorage.setItem('access_token', '"new-tok"')
    await Promise.resolve()
    await Promise.resolve()

    expect(FakeWebSocket.instances).toHaveLength(2)
    const second = FakeWebSocket.instances[1]
    expect(second.url).not.toContain('token')
    second.open()
    expect(JSON.parse(second.sent[0])).toEqual({ type: 'auth', token: 'new-tok' })
    expect(mockedRedirect).not.toHaveBeenCalled()
  })

  it('重连后再 4401 → 直接跳登录（防刷新/重连风暴）', async () => {
    mockedRefresh.mockResolvedValue('new-tok')
    await bootStore()
    const first = FakeWebSocket.instances[0]
    first.open()
    first.serverClose(4401)
    localStorage.setItem('access_token', '"new-tok"')
    await Promise.resolve()
    await Promise.resolve()

    expect(FakeWebSocket.instances).toHaveLength(2)
    FakeWebSocket.instances[1].open()
    FakeWebSocket.instances[1].serverClose(4401)

    expect(mockedRedirect).toHaveBeenCalledTimes(1)
    expect(mockedRefresh).toHaveBeenCalledTimes(1)
    expect(FakeWebSocket.instances).toHaveLength(2)
  })

  it('4401 刷新失败 → 跳登录且不再重连', async () => {
    mockedRefresh.mockResolvedValue(null)
    await bootStore()
    const sock = FakeWebSocket.instances[0]
    sock.open()
    sock.serverClose(4401)
    await Promise.resolve()
    await Promise.resolve()

    expect(mockedRedirect).toHaveBeenCalledTimes(1)
    expect(FakeWebSocket.instances).toHaveLength(1)
  })

  it('token 清空（登出）：断线后重连循环自终止，不再新建连接', async () => {
    vi.useFakeTimers()
    await bootStore()
    const sock = FakeWebSocket.instances[0]
    sock.open()

    localStorage.removeItem('access_token')
    sock.serverClose(1006)

    vi.advanceTimersByTime(60_000)
    expect(FakeWebSocket.instances).toHaveLength(1)
  })
})
