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
  // 常见 reason 码（auth）
  user_not_found: '账号不存在',
  bad_password: '密码错误',
  user_disabled: '账号已被禁用',
  session_kicked: '会话已被踢出(密码变更/管理员下线)',
  user_not_active: '账号非活跃状态',
  credential_last_one: '最后一个可用凭证，不允许解绑',
  rate_limited: '触发限流',
  // 媒体常见 reason
  provider_failed: '服务商调用失败',
  timeout: '超时',
  insufficient_points: '积分不足',
  model_not_available: '模型不可用',
  policy_rejected: '内容安全策略拒绝'
}

/** 翻译单个 key；未知原样返回。 */
export function detailKeyCn(key: string): string {
  return DETAIL_KEY_CN[key] ?? key
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
