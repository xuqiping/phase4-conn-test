// ============================================================
// 参与项目全局 store（7x/17x：五入口分散选择统一为页顶单一控制）
// 之前：chat/kb/image/video 各存各的 localStorage 键 + 画布快照顶层 projectGroupId，
//   五处要设五次。现：单键 project_group_id 全局唯一真相，AppHeader 选择器读写，
//   各入口只读 store；画布快照字段保留（兼容老快照一次性收养 + 随快照落库不丢）。
// ============================================================

import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { projectGroupApi, type ProjectGroupMineVO } from '@/api/projectGroup'
import { billingApi } from '@/api/billing'
import { getStorage, setStorage, STORAGE_KEYS } from '@/utils/storage'

/** 全局唯一持久化键（区别于旧五入口分键；旧键在 adoptLegacy 收养后不再读）。 */
const STORAGE_KEY = 'project_group_id'

// ============================================================
// 计划 E4（7x-3）：/ws/events 积分实时推送——徽标秒级响应
// 连接生命周期自管理：init() 启动；登出（token 清空）后重连循环自终止，
// 重新登录 AppHeader 重挂载 → init() 再启动（AppHeader 零改动）。
// ============================================================

/** 服务端 points.changed 下行帧（计划 E3 协议）。 */
export interface PointsChangedMsg {
  type: 'points.changed'
  scope: 'PERSONAL' | 'GROUP' | 'MEMBER'
  groupId?: number | null
  balanceAfter?: number | null
  delta?: number | null
  reason?: string | null
  ts?: number
}

export const useProjectGroupStore = defineStore('projectGroup', () => {
  /** 当前参与项目（null=个人钱包计费）。 */
  const groupId = ref<number | null>(getStorage<number>(STORAGE_KEY) ?? null)
  /** 我的组列表（选择器数据源 + 余额徽标）。 */
  const groups = ref<ProjectGroupMineVO[]>([])
  /** 个人积分余额（页顶展示；null=未加载/加载失败不展示）。 */
  const personalPoints = ref<number | null>(null)
  const loadedGroups = ref(false)
  const loadedWallet = ref(false)

  /** 当前选中组（含余额/限额；未选或列表未含= null）。 */
  const currentGroup = computed(() => groups.value.find(g => g.id === groupId.value) ?? null)

  /** 当前生效组池余额（未选组= null）。 */
  const groupBalance = computed(() => currentGroup.value?.balancePoints ?? null)

  async function loadGroups() {
    try {
      const res = await projectGroupApi.mine()
      groups.value = res.data.data
    } catch {
      // 静默降级为仅个人计费（选择器/徽标不崩）；错误 toast 由 AppHeader 侧提示
      groups.value = []
    } finally {
      loadedGroups.value = true
    }
  }

  async function loadWallet() {
    try {
      const res = await billingApi.myWallet()
      personalPoints.value = res.data.data.balance
    } catch {
      personalPoints.value = null
    } finally {
      loadedWallet.value = true
    }
  }

  /** AppHeader 挂载初始化（组列表 + 个人钱包并行；失败静默降级）+ 启动实时推送连接。 */
  async function init() {
    await Promise.all([loadGroups(), loadWallet()])
    startEvents()
  }

  /** 全局切组（AppHeader 选择器唯一写入口）；持久化单键。 */
  function setGroup(id: number | null) {
    groupId.value = id
    setStorage(STORAGE_KEY, id)
  }

  /**
   * 一次性收养旧入口遗留选择：全局从未设置（null）且旧键/画布快照有值 → 采用第一个非空。
   * 已有全局选择=全局胜出（不收养）。收养后旧键留在原地不清理（回滚本版本仍可用）。
   */
  function adoptLegacy(...candidates: Array<number | null | undefined>) {
    if (groupId.value != null) return
    const found = candidates.find(c => c != null) ?? null
    if (found != null) setGroup(found)
  }

  // ---- 计划 E4（7x-3）：/ws/events 实时推送 ----

  /** 最近一次积分事件（页面 watch 此值刷新列表；ts 单调）。 */
  const lastEvent = ref<PointsChangedMsg | null>(null)
  const eventsConnected = ref(false)

  let eventsWs: WebSocket | null = null
  let eventsTimer: ReturnType<typeof setTimeout> | null = null
  let eventsAttempt = 0
  let eventsStopped = true

  function eventsUrl(): string | null {
    const token = getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN)
    if (!token) return null
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${protocol}//${window.location.host}/ws/events?token=${token}`
  }

  /** 断线重连退避 1s/2s/5s/30s 封顶（防风暴）；token 已清空 → 循环自终止（登出）。 */
  function scheduleEventsReconnect() {
    if (eventsStopped) return
    if (!getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN)) {
      eventsStopped = true
      return
    }
    const backoff = [1000, 2000, 5000, 30000][Math.min(eventsAttempt, 3)]
    eventsAttempt++
    eventsTimer = setTimeout(connectEvents, backoff)
  }

  function connectEvents() {
    if (eventsStopped) return
    const url = eventsUrl()
    if (!url) {
      eventsStopped = true
      return
    }
    try {
      eventsWs = new WebSocket(url)
    } catch {
      scheduleEventsReconnect()
      return
    }
    eventsWs.onopen = () => {
      const wasReconnect = eventsAttempt > 0
      eventsAttempt = 0
      eventsConnected.value = true
      // 断线期间可能漏推——重连成功强制全量补拉一次（spec §3.3）
      if (wasReconnect) {
        void loadWallet()
        void loadGroups()
      }
    }
    eventsWs.onmessage = (ev: MessageEvent) => {
      try {
        const msg = JSON.parse(ev.data) as PointsChangedMsg
        if (msg?.type !== 'points.changed') return
        lastEvent.value = msg
        // 徽标纯 store 更新（无网络请求，零成本秒跳）
        if (msg.scope === 'PERSONAL' && msg.balanceAfter != null) {
          personalPoints.value = msg.balanceAfter
        } else if (msg.scope === 'GROUP' && msg.groupId != null && msg.balanceAfter != null) {
          const g = groups.value.find(x => x.id === msg.groupId)
          if (g) g.balancePoints = msg.balanceAfter
        }
      } catch {
        // 非 JSON 帧忽略
      }
    }
    eventsWs.onclose = () => {
      eventsConnected.value = false
      eventsWs = null
      scheduleEventsReconnect()
    }
    eventsWs.onerror = () => {
      eventsWs?.close()
    }
  }

  function startEvents() {
    eventsStopped = false
    if (!eventsWs && !eventsTimer) connectEvents()
  }

  /** 显式停连（测试/登出兜底；登出主要靠 token 清空后重连自终止）。 */
  function stopEvents() {
    eventsStopped = true
    if (eventsTimer) {
      clearTimeout(eventsTimer)
      eventsTimer = null
    }
    eventsWs?.close()
    eventsWs = null
    eventsConnected.value = false
  }

  // 页签关闭即断（服务端 30s ping 也会剔除僵尸连接）
  if (typeof window !== 'undefined') {
    window.addEventListener('beforeunload', () => stopEvents())
  }

  return {
    groupId, groups, personalPoints, loadedGroups, loadedWallet,
    currentGroup, groupBalance,
    loadGroups, loadWallet, init, setGroup, adoptLegacy,
    lastEvent, eventsConnected, stopEvents
  }
})
