// ============================================================
// 17x#2 成员权限（V139）纯函数助手 — ProjectGroupsView 弹窗表单 ↔ 后端稀疏语义互转
// 后端口径：allowedKinds null=不限 / []=全禁 / 子集=白名单；
//           memberVisibilityOverrides 稀疏 map（缺 key=跟随组设置）。
// ============================================================

/** 组内可开关的功能模块（与后端 ProjectGroupVisibilityService.OUTPUT_KINDS 对齐）。 */
export const GROUP_KINDS = ['CHAT', 'EMBED', 'RERANK', 'IMAGE', 'VIDEO'] as const
export type GroupKind = typeof GROUP_KINDS[number]

export const GROUP_KIND_LABEL: Record<GroupKind, string> = {
  CHAT: '对话', EMBED: '嵌入', RERANK: '重排', IMAGE: '图片', VIDEO: '视频'
}

/** 功能开关弹窗表单态。 */
export interface KindsForm {
  /** true=不限制（提交 null）；false=按 kinds 白名单（空数组=全禁） */
  unlimited: boolean
  kinds: string[]
}

/** 后端 allowedKinds → 弹窗表单（坏值/未知 kind 宽容丢弃）。 */
export function kindsFormFromAllowed(allowed: string[] | null | undefined): KindsForm {
  if (allowed == null) return { unlimited: true, kinds: [...GROUP_KINDS] }
  return { unlimited: false, kinds: allowed.filter(k => (GROUP_KINDS as readonly string[]).includes(k)) }
}

/** 弹窗表单 → 后端 allowedKinds（unlimited → null）。 */
export function allowedFromKindsForm(form: KindsForm): string[] | null {
  return form.unlimited ? null : [...form.kinds]
}

/** 成员级可见性弹窗：每个模块三态。FOLLOW=跟随组设置（不落覆盖表）。 */
export type VisChoice = 'FOLLOW' | 'OWN' | 'ALL'
export type VisForm = Record<GroupKind, VisChoice>

/** 后端稀疏覆盖 map → 全量三态表单（未知 kind/非法值宽容当 FOLLOW）。 */
export function visFormFromOverrides(overrides: Record<string, string> | null | undefined): VisForm {
  const form = { CHAT: 'FOLLOW', EMBED: 'FOLLOW', RERANK: 'FOLLOW', IMAGE: 'FOLLOW', VIDEO: 'FOLLOW' } as VisForm
  if (!overrides) return form
  for (const k of GROUP_KINDS) {
    const v = overrides[k]
    if (v === 'OWN' || v === 'ALL') form[k] = v
  }
  return form
}

/** 全量三态表单 → 后端稀疏覆盖 map（FOLLOW 不落；全 FOLLOW → {}=清空）。 */
export function overridesFromVisForm(form: VisForm): Record<string, 'OWN' | 'ALL'> {
  const out: Record<string, 'OWN' | 'ALL'> = {}
  for (const k of GROUP_KINDS) {
    if (form[k] !== 'FOLLOW') out[k] = form[k] as 'OWN' | 'ALL'
  }
  return out
}
