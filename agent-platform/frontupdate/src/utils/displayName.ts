// agent-platform/frontend/src/utils/displayName.ts
/**
 * 修复III E2（12x#3）：全站人名显示统一口径——姓名（users.name）优先，缺省回落用户名。
 * 逐处替换按影响面分批（本 chunk 先换高频处：项目组成员表/审计用户列/产出用户列/@提及候选/组邀请列表）。
 */

/** 最小形状——各处 VO 只要有 name/username 两字段即可用，不必整 UserVO。 */
export interface DisplayNameLike {
  name?: string | null
  username: string
}

/** 人名显示：trim 后姓名非空用姓名，否则用户名。 */
export function displayName(user: DisplayNameLike | null | undefined): string {
  if (!user) return ''
  const name = user.name?.trim()
  return name ? name : user.username
}
