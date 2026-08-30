// ============================================================
// 节点资产徽标 computed（S12 画布↔资产库打通）
// 5 类节点共用：从 node.data 平铺三字段（assetId/assetName/assetVersion/assetHasUpdate）
// 算出 CanvasNodeBase 需要的 AssetBadge | undefined。DRY，避免每个节点重复 computed。
// ============================================================
import { computed, type ComputedRef } from 'vue'
import type { AssetBadge } from '@/types/canvas'

/**
 * 从节点 data（响应式 bag）算资产徽标。
 * assetId 缺失 → undefined（基座不渲染徽标）。
 * version 缺省 1；name 缺省「资产」。
 *
 * 接受 Record<string, unknown> 而非窄接口：节点 props.data 是宽 bag
 * （assetId 等经 updateNodeData 运行时挂上），窄接口会与各节点的具体 data 类型交叉摩擦。
 */
export function useNodeAssetBadge(
  data: Record<string, unknown> | undefined
): ComputedRef<AssetBadge | undefined> {
  return computed(() => {
    if (!data) return undefined
    const assetId = data.assetId
    if (assetId == null) return undefined
    return {
      name: (data.assetName as string | undefined) ?? '资产',
      version: (data.assetVersion as number | undefined) ?? 1,
      hasUpdate: data.assetHasUpdate as boolean | undefined
    }
  })
}
