/**
 * 8x-2 / 13x-1：审计日志 & 安全事件 detailJson 显示层中文字典。
 * DB 仍存原始码（脱敏+截断后的 JSON），这里只做展示翻译——与后端
 * AuditLabelDictionary 同思路：显示层字典不写库。
 *
 * 未知 key 原样回落（新字段上线不至于显示空白）。
 */

/** 审计/安全 detail 通用 key → 中文（两处共用一张表，键冲突时含义一致）。 */
export const DETAIL_KEY_CN: Record<string, string> = {
  // 认证类
  username: '账号',
  password: '密码(脱敏)',
  reason: '原因',
  error: '错误信息',
  fails: '连续失败次数',
  distinctAccounts: '尝试的不同账号数',
  // P0 手工审计行（8x-1 B1：EmailService/SmsService/MfaService 等手工 detail 键）
  email: '邮箱',
  phone: '手机号',
  ip: '来源IP',
  identifier: '账号标识',
  hit: '是否命中账号',
  openid: '微信openid',
  userId: '用户ID',
  channel: '重置渠道',
  // 请求路径/防护类（安全事件）
  path: '请求路径',
  max: '阈值上限',
  snippet: '命中内容',
  hits: '累计命中次数',
  from: '此前登录地',
  to: '本次登录地',
  hoursDiff: '间隔(小时)',
  resourceType: '资源类型',
  windowCount: '窗口内次数',
  windowSec: '窗口时长(秒)',
  matchedSig: '命中特征',
  repeatCount: '重复次数',
  // 特权变更/凌晨敏感操作（13x-1：detail 里最核心的三个字段）
  action: '具体操作',
  targetType: '操作对象类型',
  at: '发生时刻',
  // 其余冷规则缺失 key（13x-1 一并补齐，未知 key 仍原样回落）
  distinctIps: '不同IP数',
  windowSpent: '窗口内消耗积分',
  balanceAfter: '操作后余额',
  windowCostFen: '窗口内消耗(分)',
  taskCount: '任务数',
  // 模型调用/计费类（审计）
  model: '模型',
  kind: '任务类型',
  pointsConsumed: '消耗积分',
  imageCount: '图片数量',
  tokensInput: '输入token',
  tokensOutput: '输出token',
  durationMs: '耗时(毫秒)',
  // 通用参数名（@AuditLog 切面按方法参数名采集）
  id: '对象ID',
  ids: '对象ID列表',
  taskId: '任务ID',
  agentId: '智能体ID',
  kbId: '知识库ID',
  projectId: '项目ID',
  fileId: '文件ID',
  targetId: '目标ID',
  request: '请求参数(脱敏)',
  response: '响应(脱敏)',
  count: '数量',
  status: '状态',
  settingKey: '设置项',
  settingValue: '设置值(脱敏)',
  amount: '数额',
  points: '积分'
}

/** 枚举值 → 中文（value 级翻译，只翻译确定语义的码）。 */
export const DETAIL_VALUE_CN: Record<string, string> = {
  // kind
  CHAT: '对话',
  VIDEO: '视频生成',
  IMAGE: '图片生成',
  // resourceType
  USER: '用户',
  // targetType（特权变更/凌晨敏感操作的操作对象，13x-1）
  role: '角色',
  permission: '权限',
  user: '用户账号',
  security_event: '安全事件',
  security_rule: '安全规则',
  pricing_rule: '价表规则',
  ratio_tier: '积分阶梯',
  system_setting: '系统设置',
  llm_provider: '模型供应商',
  // 常见 reason 码（auth）
  user_not_found: '账号不存在',
  bad_password: '密码错误',
  user_disabled: '账号已被禁用',
  session_kicked: '会话已被踢出(密码变更/管理员下线)',
  user_not_active: '账号非活跃状态',
  credential_last_one: '最后一个可用凭证，不允许解绑',
  rate_limited: '触发限流',
  // B1（8x-1）：P0 手工行 reason 码
  verification_off: '验证功能未开启',
  token_blank: '令牌为空',
  token_invalid: '令牌无效',
  redis_error: '缓存读取失败',
  email_blank: '邮箱为空',
  no_verified_phone: '账号无已验证手机号',
  no_resettable_email: '账号无可用于重置的邮箱',
  already_verified: '邮箱已验证',
  code_active: '验证码已发未过期',
  code_wrong: '验证码错误',
  already_bound: '已绑定',
  // 媒体常见 reason
  provider_failed: '服务商调用失败',
  timeout: '超时',
  insufficient_points: '积分不足',
  model_not_available: '模型不可用',
  policy_rejected: '内容安全策略拒绝'
}

/** 13x-1：@AuditLog module → 中文（与后端 @AuditLog(module=...) 同源盘点）。
 *  8x-2 B3：删幽灵码 workflow/points/project（后端无写入），补 feedback/project-group/audit。 */
export const AUDIT_MODULE_CN: Record<string, string> = {
  user: '用户管理', role: '角色权限', auth: '认证账号', security: '安全管理',
  billing: '计费积分', llm: '模型供应', media: '媒体生成', kb: '知识库',
  agent: '智能体', asset: '资产库', canvas: '无限画布',
  chat: '智能对话', memory: '记忆库', system: '系统设置', file: '文件',
  feedback: '公告建议台', 'project-group': '项目组', audit: '审计链', workflow: '工作流',
  project: '协作项目'
}

/** 13x-1：action 单词 → 中文（含全部会触发特权变更的敏感动作）。 */
export const AUDIT_ACTION_CN: Record<string, string> = {
  update_status: '修改账号状态',
  assign_roles: '分配角色',
  update_permissions: '修改权限',
  reset_password: '重置密码',
  password_change: '修改密码',
  credential_bind: '绑定登录凭证',
  credential_unbind: '解绑登录凭证',
  event_ack: '处置安全事件',
  event_batch_delete: '批量删除安全事件',
  ip_block: '封禁IP',
  ip_unblock: '解封IP',
  rule_config_update: '修改安全规则',
  pricing_create: '新建价表',
  pricing_update: '修改价表',
  pricing_delete: '删除价表',
  pricing_import: '导入价表',
  pricing_export: '导出价表',
  pricing_template_download: '下载价表模板',
  ratio_create: '新建积分阶梯',
  ratio_update: '修改积分阶梯',
  ratio_delete: '删除积分阶梯',
  admin_recharge: '管理员充值',
  provider_import: '导入供应商',
  provider_export: '导出供应商',
  update_auth_channels: '修改认证渠道',
  update_auth_settings: '修改认证设置',
  update_billing_settings: '修改计费设置',
  update_llm_model_defaults: '修改模型默认配置',
  update_rag_memory_settings: '修改RAG记忆设置',
  update_rag_recall_settings: '修改RAG召回设置',
  update_web_search_settings: '修改联网搜索设置',
  upload_file: '上传文件',
  grant: '授权',
  revoke: '回收授权',
  block: '封禁',
  unblock: '解封',
  ban: '封禁账号',
  unlock: '解锁账号',
  create: '新建',
  update: '修改',
  delete: '删除'
}

/** 13x-1：翻译 "module:action" 形式的敏感操作码（如 security:event_ack → 安全管理 · 处置安全事件）。 */
export function auditActionCn(value: string): string {
  const idx = value.indexOf(':')
  if (idx <= 0 || idx >= value.length - 1) return DETAIL_VALUE_CN[value] ?? value
  const module = value.slice(0, idx)
  const action = value.slice(idx + 1)
  const m = AUDIT_MODULE_CN[module] ?? module
  const a = AUDIT_ACTION_CN[action] ?? action
  return `${m} · ${a}`
}

/** 翻译单个 key；未知原样返回。 */
export function detailKeyCn(key: string): string {
  return DETAIL_KEY_CN[key] ?? key
}

/** 按上下文 key 翻译 value：action 字段是 "module:action" 操作码，需要组合翻译（13x-1）。 */
export function detailValueCnForKey(key: string, value: unknown): string {
  if (typeof value === 'string' && (key === 'action' || key === 'moduleAction')) {
    return auditActionCn(value)
  }
  return detailValueCn(value)
}

/** 翻译单个 value（仅字符串枚举命中才翻译，其余原样）。 */
export function detailValueCn(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'string') return DETAIL_VALUE_CN[value] ?? value
  if (typeof value === 'number') return String(value)
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}
