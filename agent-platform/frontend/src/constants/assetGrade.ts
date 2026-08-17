// ============================================================
// 资产评分等级常量（2x#7）——与后端 asset/service/AssetGrade.java 对齐
// 唯一真相源在后端（VO 派生 grade 字段）；本常量仅两处用途：
//   1. 打分 slider 实时等级预览（未保存草稿，前端先算）
//   2. 筛选条等级下拉 → scoreMin/scoreMax 区间换算
// 对齐单测 assetGrade.test.ts 镜像后端边界，改档位两处同改。
// ============================================================

/** 等级全集（下拉顺序：高→低）。 */
export const ASSET_GRADES = ['A+', 'A', 'B', 'C', 'D'] as const
export type AssetGrade = (typeof ASSET_GRADES)[number]

/** 等级→分数区间 [min,max]（含边界；等级快捷筛选换算用）。 */
export const ASSET_GRADE_RANGE: Record<AssetGrade, [number, number]> = {
  'A+': [95, 100],
  A: [90, 94],
  B: [80, 89],
  C: [70, 79],
  D: [0, 69]
}

/** 分数→等级；null/undefined（未评）→ null。均分展示前已由后端取整。 */
export function gradeFromScore(score?: number | null): AssetGrade | null {
  if (score == null) return null
  if (score >= 95) return 'A+'
  if (score >= 90) return 'A'
  if (score >= 80) return 'B'
  if (score >= 70) return 'C'
  return 'D'
}
