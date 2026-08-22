// ============================================================
// 认证模块API
// 对应后端 /api/auth/* 端点
// ============================================================

import request from './request'
import type { ApiResponse } from './request'
import type { UserInfo } from '@/stores/auth'

/** 登录响应数据（S5 A6：已绑定 TOTP 用户第一屏只回 mfaRequired+mfaToken，token 字段缺省） */
export interface LoginResponse {
  accessToken?: string
  refreshToken?: string
  tokenType?: string
  expiresIn?: number
  userInfo?: UserInfo
  /** true=进入两步登录第二屏（此时无 token 字段） */
  mfaRequired?: boolean
  /** 两步登录中间票（5 分钟一次性，配合 verifyMfa） */
  mfaToken?: string
  /** totp.required 开 + admin 未绑定 → 引导绑定（不阻断） */
  mfaBindAdvice?: boolean
}

/** 刷新Token响应数据（安全体系 S5 A4：旋转模式下回传新 refreshToken，旧票已作废） */
export interface RefreshResponse {
  accessToken: string
  refreshToken?: string
  expiresIn: number
}

/** 认证通道开关（前端登录页渲染依赖，公开端点，无密钥）。 */
export interface AuthChannels {
  /** 账号密码登录（恒 true） */
  passwordEnabled: boolean
  /** 邮箱通道（注册验证邮件 / 找回密码邮件） */
  emailEnabled: boolean
  /** 短信通道（手机验证码登录 / 找回密码短信） */
  smsEnabled: boolean
  /** 微信扫码登录通道 */
  wechatEnabled: boolean
}

/** 滑块验证码结果（AJ-Captcha）。 */
export interface CaptchaResult {
  /** 后端 Redis key（标识本次验证码会话） */
  id?: string
  /** 背景图 base64（带缺口） */
  bgImgPath?: string
  /** 滑块缺口图 base64 */
  cutoutImgPath?: string
  /** AES 加密密钥（前端加密滑动轨迹用） */
  secretKey?: string
  /** token */
  token?: string | null
  /** 图片宽（用于轨迹加密） */
  imgX?: number
  /** 图片高 */
  imgY?: number
  /** 原始透传字段（AJ-Captcha 不同版本字段名差异） */
  [key: string]: unknown
}

/** 认证API */
export const authApi = {
  /**
   * 用户登录
   * POST /api/auth/login
   */
  login(params: { username: string; password: string }) {
    return request.post<ApiResponse<LoginResponse>>('/auth/login', params)
  },

  /**
   * 用户注册
   * POST /api/auth/register
   */
  register(params: { username: string; email: string; password: string }) {
    return request.post<ApiResponse<void>>('/auth/register', params)
  },

  /**
   * 刷新访问令牌
   * POST /api/auth/refresh
   */
  refresh(refreshToken: string) {
    return request.post<ApiResponse<RefreshResponse>>('/auth/refresh', { refreshToken })
  },

  /**
   * 用户登出
   * POST /api/auth/logout
   */
  logout(refreshToken: string) {
    return request.post<ApiResponse<void>>('/auth/logout', { refreshToken })
  },

  /**
   * 钉钉免登登录
   * POST /api/auth/login/dingtalk
   * @param source 'jsapi'(容器内免登码,走 oapi) | 'oauth2'(网页授权码,默认)
   */
  dingTalkLogin(authCode: string, source: 'jsapi' | 'oauth2' = 'oauth2') {
    return request.post<ApiResponse<LoginResponse>>('/auth/login/dingtalk', { authCode, source })
  },

  /**
   * 获取当前用户信息
   * GET /api/auth/me
   */
  getMe() {
    return request.get<ApiResponse<UserInfo>>('/auth/me')
  },

  // ==================== 认证系统增强（多通道） ====================

  /**
   * 获取认证通道开关（公开端点，前端登录页渲染依赖）
   * GET /api/auth/channels
   */
  getChannels() {
    return request.get<ApiResponse<AuthChannels>>('/auth/channels')
  },

  /**
   * 获取滑块验证码（AJ-Captcha，发码/登录前置闸门）
   * GET /api/auth/captcha
   */
  getCaptcha() {
    return request.get<ApiResponse<CaptchaResult>>('/auth/captcha')
  },

  /**
   * 校验滑块轨迹（独立校验入口；发码时后端也会复验）
   * POST /api/auth/captcha/verify
   * @param captchaVerification 前端滑块组件产出的加密轨迹串
   */
  verifyCaptcha(captchaVerification: string) {
    return request.post<ApiResponse<void>>('/auth/captcha/verify', { captchaVerification })
  },

  /**
   * 发送短信验证码（前置滑块校验 + 限流三档）
   * POST /api/auth/sms/code
   * @param phone 国内手机号 ^1[3-9]\d{9}$
   * @param captchaToken 滑块验证码产出的加密轨迹串（后端复验，单次有效防重放）
   */
  sendSmsCode(phone: string, captchaToken: string) {
    return request.post<ApiResponse<string>>('/auth/sms/code', { phone, captchaToken })
  },

  /**
   * 手机验证码登录（新号自动建号）
   * POST /api/auth/login/sms
   */
  loginBySms(phone: string, code: string) {
    return request.post<ApiResponse<LoginResponse>>('/auth/login/sms', { phone, code })
  },

  /**
   * 获取微信扫码授权跳转 URL（前端 window.location 跳转）
   * GET /api/auth/login/wechat/redirect
   */
  getWechatRedirectUrl() {
    return request.get<ApiResponse<string>>('/auth/login/wechat/redirect')
  },

  /**
   * 邮箱激活（用户点激活链接后前端调）
   * POST /api/auth/verify/email
   */
  verifyEmail(token: string) {
    return request.post<ApiResponse<void>>('/auth/verify/email', { token })
  },

  /**
   * 重发验证邮件（统一话术防枚举：不区分邮箱是否存在/已验证）
   * POST /api/auth/resend/email
   */
  resendVerifyEmail(email: string) {
    return request.post<ApiResponse<string>>('/auth/resend/email', { email })
  },

  /**
   * 发起找回密码（统一话术"若账号存在，重置链接/码已发送"防枚举）
   * POST /api/auth/password/forgot
   * @param identifier 用户名/邮箱/手机号
   * @param channel EMAIL（邮件链接）| SMS（短信码）
   */
  forgotPassword(identifier: string, channel: 'EMAIL' | 'SMS' = 'EMAIL') {
    return request.post<ApiResponse<string>>('/auth/password/forgot', { identifier, channel })
  },

  /**
   * 重置密码（校验 token/码 + 新密码 + 踢所有会话）
   * POST /api/auth/password/reset
   * @param token 重置 token（邮件链接）或短信码（channel=SMS）
   * @param newPassword 新密码
   * @param channel EMAIL | SMS（默认 EMAIL）
   * @param phone channel=SMS 时必填（匹配重置码）
   */
  resetPassword(
    token: string,
    newPassword: string,
    channel: 'EMAIL' | 'SMS' = 'EMAIL',
    phone?: string
  ) {
    return request.post<ApiResponse<void>>('/auth/password/reset', {
      token,
      newPassword,
      channel,
      phone
    })
  },

  // ==================== 账号安全设置（Chunk G，需登录态） ====================

  /**
   * 当前登录用户凭证列表（identifier 脱敏）
   * GET /api/me/credentials
   */
  getCredentials() {
    return request.get<ApiResponse<CredentialItem[]>>('/me/credentials')
  },

  /**
   * 绑定邮箱（建 EMAIL 凭证 verified=FALSE + 触发激活邮件）
   * POST /api/me/credential/bind-email
   * 12x B4：账号已开 TOTP 时 totpCode 必填（后端强校验）
   */
  bindEmail(email: string, totpCode?: string) {
    return request.post<ApiResponse<void>>('/me/credential/bind-email', { email, totpCode })
  },

  /**
   * 解绑凭证（至少留一种，PASSWORD 不可解绑）
   * POST /api/me/credential/unbind
   * @param credentialType EMAIL/PHONE/WECHAT/DINGTALK
   */
  unbindCredential(credentialType: string) {
    return request.post<ApiResponse<void>>('/me/credential/unbind', { credentialType })
  },

  /**
   * 修改密码（验旧密码 + PasswordPolicy + 踢所有会话）
   * POST /api/me/password/change
   * 成功后当前 token 即刻失效，前端须登出跳登录页。
   */
  changePassword(oldPassword: string, newPassword: string) {
    return request.post<ApiResponse<void>>('/me/password/change', { oldPassword, newPassword })
  },

  // ==================== 安全体系 S5 · A6 TOTP 两步验证 ====================

  /**
   * 两步登录第二屏：mfaToken + TOTP 码/恢复码 → 双 token
   * POST /api/auth/mfa/verify（公开端点，mfaToken 即凭证）
   */
  verifyMfa(mfaToken: string, code: string) {
    return request.post<ApiResponse<LoginResponse>>('/auth/mfa/verify', { mfaToken, code })
  },

  /** 当前用户 MFA 绑定状态（设置页渲染） */
  getMfaStatus() {
    return request.get<ApiResponse<MfaStatus>>('/auth/mfa/status')
  },

  /** 发起绑定：返回 secret + otpauth URI（确认前不生效） */
  mfaBind() {
    return request.post<ApiResponse<MfaBindStart>>('/auth/mfa/bind')
  },

  /** 确认绑定：验证器首个 code → 发放 8 组一次性恢复码（明文仅此一次） */
  mfaBindConfirm(code: string) {
    return request.post<ApiResponse<MfaBindConfirm>>('/auth/mfa/bind/confirm', { code })
  },

  /** 解绑：需当前有效验证码/恢复码 */
  mfaUnbind(code: string) {
    return request.post<ApiResponse<void>>('/auth/mfa/unbind', { code })
  },

  // ==================== 安全体系 S5 · J2 注销 ====================

  /**
   * 注销账号（登录态 + 密码确认 → 软删匿名化）
   * DELETE /api/auth/account
   * 成功即当前 token 全部拉黑——前端须清本地态并跳登录页。
   */
  deleteAccount(password: string) {
    return request.delete<ApiResponse<void>>('/auth/account', { data: { password } })
  }
}

/** MFA 绑定状态（S5 A6）。 */
export interface MfaStatus {
  bound: boolean
  required: boolean
}

/** 绑定发起响应：secret 手动加入验证器 App，或粘贴 otpauthUri。 */
export interface MfaBindStart {
  secret: string
  otpauthUri: string
}

/** 绑定确认响应：8 组一次性恢复码（仅本次返回，请提示用户保存）。 */
export interface MfaBindConfirm {
  recoveryCodes: string[]
}

/** 凭证列表项（设置页展示，identifier 已脱敏）。 */
export interface CredentialItem {
  /** 凭证类型：PASSWORD/EMAIL/PHONE/WECHAT/DINGTALK */
  credentialType: string
  /** 脱敏标识（手机 138****8000 / 邮箱 a***@x.com） */
  identifier: string
  /** 是否已验证（未验证邮箱不可用于找回密码） */
  verified: boolean
  /** 首次验证时间 */
  verifiedAt: string | null
}
