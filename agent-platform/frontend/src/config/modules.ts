/**
 * 项目级功能模块开关 + 模块所需权限码映射。
 *
 * 设计目的：
 * - 单一真相源，控制「某模块在本项目是否展示」。改一处 → 菜单 + 路由 + 入口同步生效。
 * - 与 RBAC 权限（`hasPermission`）叠加：模块展示 = `isModuleEnabled(key) && hasPermission(perm)`。
 *   开关管「项目要不要这个模块」（含 admin），权限管「这个用户能不能用」（admin 默认有）。
 *
 * 使用：
 * - 菜单 Sidebar、路由守卫、页内入口都用 `isModuleEnabled('xxx')` 兜底。
 * - 恢复某模块：把对应布尔改成 true 即可，不改代码逻辑。
 *
 * 当前状态（2026-08-11，问题单 10x-5）：
 * - Agent大厅 / 工作流 / 执行监控 本项目暂未启用 → false（对所有人隐藏，含 admin）。
 *   后端 Controller、前端 view 文件全部保留，仅入口/路由断开，便于未来恢复。
 */
export type ModuleKey =
  | 'agentHall'
  | 'chat'
  | 'workflow'
  | 'execution'
  | 'knowledge'
  | 'videoGen'
  | 'imageGen'
  | 'videoEdit'
  | 'canvas'
  | 'assets'
  | 'wallet'
  | 'projectGroups'
  | 'feedback'
  | 'settings'

/**
 * 模块开关表：true=本项目展示此模块，false=对所有人隐藏（含 admin）。
 *
 * 后端能力不受此表影响——此表只管前端可见性与路由可达性，后端 @RequirePermission 是事实授权闸。
 */
export const ENABLED_MODULES: Record<ModuleKey, boolean> = {
  // 本项目暂未启用的三大模块（问题 10x-5）：保留代码、隐藏入口
  agentHall: false,
  workflow: false,
  execution: false,
  // 常驻启用模块
  chat: true,
  knowledge: true,
  videoGen: true,
  imageGen: true,
  videoEdit: true,
  canvas: true,
  assets: true,
  wallet: true,
  projectGroups: true,
  // 19x：反馈与帮助（建议台/提问台/说明台三合一；用户侧无权限码，登录即可）
  feedback: true,
  settings: true
}

/**
 * 模块 → 所需权限码映射（RBAC 兜底）。
 * - 列出的模块需用户持该权限码才在菜单可见（admin 默认有全权限）。
 * - 未列出的模块（如 chat/knowledge/wallet）不卡权限码，仅受开关控制。
 * - 权限码取自后端 `permissions` 表种子（V2__seed_data.sql）。
 */
export const MODULE_PERMISSION_MAP: Partial<Record<ModuleKey, string>> = {
  agentHall: 'agent:read',
  workflow: 'workflow:read',
  execution: 'execution:read',
  videoGen: 'media:gen',
  imageGen: 'media:gen',
  videoEdit: 'media:edit',
  canvas: 'canvas:write',
  assets: 'asset:write',
  // 计划5：项目组模块（推进页+选择器数据源同权限码；后端 /api/project-groups/** 全端点同码兜底）
  projectGroups: 'project-group:manage'
}

/** 模块是否启用（开关）。 */
export function isModuleEnabled(key: ModuleKey): boolean {
  return ENABLED_MODULES[key] === true
}

/** 取模块所需权限码（无则 undefined=不卡权限码）。 */
export function getModulePermission(key: ModuleKey): string | undefined {
  return MODULE_PERMISSION_MAP[key]
}
