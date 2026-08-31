import { ref } from 'vue'
import { getStorage, setStorage } from '@/utils/storage'

/**
 * 修复IX-2 B2（Q4 拍板）：画布「连线保留」全局开关——**一个开关治理两处**：
 * - Ctrl+C/V 粘贴：开 → 副本带跨集边（remapCrossEdges 单侧重映射）；关 → 粘贴体零跨集边。
 * - 节点「创建副本」：开 → cloneEdgesForDuplicate 连线克隆；关 → 副本零边。
 *
 * 模块级 singleton ref（两视图共用同一份实时值，切换即时生效不重挂载）；
 * localStorage 持久化，默认开（=修复VI「连线克隆一份」既有口径延续）；
 * 非法存量值回落 true。粘贴时点判定：复制后切开关，按粘贴当下开关态生效
 * （crossEdges 恒收集进剪贴板，用不用在此刻决定）。
 */
const STORAGE_KEY = 'canvas.keepLinksOnCopy'

function readStored(): boolean {
  const v = getStorage<boolean>(STORAGE_KEY)
  return typeof v === 'boolean' ? v : true
}

/** singleton 状态（模块加载即初始化，读一次存量；后续 set 写回）。 */
export const keepLinksOnCopy = ref<boolean>(readStored())

export function setKeepLinksOnCopy(v: boolean) {
  keepLinksOnCopy.value = v
  setStorage(STORAGE_KEY, v)
}

export function toggleKeepLinksOnCopy() {
  setKeepLinksOnCopy(!keepLinksOnCopy.value)
}
