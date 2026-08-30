// ============================================================
// 修复III F2（17x#1）：组产出一键入库——行可入库判定与已入库态解析
// （从 ProjectGroupsView 产出列抽出纯函数，供单测；渲染层只管 tag/按钮）
// ============================================================

import type { ProjectGroupOutputVO } from '@/api/projectGroup'

/**
 * 行是否可入库（与预览列同口径——SUCCEEDED 才有产物 fileId）：
 * IMAGE=有 imageFileIds；VIDEO=有 resultFileId；CHAT=有 assistant 回复；EMBED/RERANK 恒不可。
 */
export function isImportable(row: Pick<ProjectGroupOutputVO, 'kind' | 'imageFileIds' | 'resultFileId' | 'chatResult'>): boolean {
  if (row.kind === 'IMAGE') return !!row.imageFileIds?.length
  if (row.kind === 'VIDEO') return !!row.resultFileId
  if (row.kind === 'CHAT') return !!row.chatResult
  return false
}

/**
 * 解析 GET /assets/exists-by-source 响应（JSON 键字符串 taskId→assetId）为已入库 taskId 集合。
 * 非数字键忽略（脏数据兜底）；null/undefined 返回空集。
 */
export function parseImportedSet(data: Record<string, number> | null | undefined): Set<number> {
  if (!data) return new Set()
  const set = new Set<number>()
  for (const key of Object.keys(data)) {
    const id = Number(key)
    if (Number.isInteger(id) && id > 0) set.add(id)
  }
  return set
}
