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
import { getStorage, setStorage } from '@/utils/storage'

/** 全局唯一持久化键（区别于旧五入口分键；旧键在 adoptLegacy 收养后不再读）。 */
const STORAGE_KEY = 'project_group_id'

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

  /** AppHeader 挂载初始化（组列表 + 个人钱包并行；失败静默降级）。 */
  async function init() {
    await Promise.all([loadGroups(), loadWallet()])
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

  return {
    groupId, groups, personalPoints, loadedGroups, loadedWallet,
    currentGroup, groupBalance,
    loadGroups, loadWallet, init, setGroup, adoptLegacy
  }
})
