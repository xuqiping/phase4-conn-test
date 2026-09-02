import type { NarrativeRoleVocab } from '@/types/asset'

/**
 * 两级角色词汇 → n-select 分组 options（修复XI C3/C4，AssetProjectView/SaveToAssetDialog 共享）：
 * 每级一组 {label:key, children:[「key（不细分）」=挂一级本身, ...子类]}。
 * 挂「不细分」= asset 只挂一级 key；挂子类 = 挂子级 key（后端一级筛选自动展开含子类）。
 */
export function buildRoleGroupOptions(
  vocab: NarrativeRoleVocab[]
): { type: 'group'; label: string; key: string; children: { label: string; value: string }[] }[] {
  return vocab.map((r) => ({
    type: 'group' as const,
    label: r.key,
    key: r.key,
    children: [
      { label: `${r.key}（不细分）`, value: r.key },
      ...r.children.map((c) => ({ label: c, value: c }))
    ]
  }))
}
