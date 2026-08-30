// ============================================================
// 微信扫码登录工具：跳转授权 + 回调 token 解析
// 安全：token 经 URL fragment（# 后）传递，不进 server log / referer（沉淀约束）
// ============================================================

import { authApi } from '@/api/auth'

/** 微信回调落地结果。 */
export interface WechatCallbackResult {
  /** 成功标志 */
  ok: boolean
  /** 访问令牌（ok=true 时） */
  accessToken?: string
  /** 刷新令牌（ok=true 时） */
  refreshToken?: string
  /** 错误码（ok=false 时），如 wechat_failed */
  error?: string
}

/**
 * 跳转微信扫码授权（前端调后端拿授权 URL，再 window.location 跳过去）。
 * 后端生成的 state 已存 Redis（5min 单次有效，防 CSRF）。
 */
export async function redirectToWechatAuth(): Promise<void> {
  const res = await authApi.getWechatRedirectUrl()
  const url = res.data.data
  if (!url) {
    throw new Error('微信登录未开启或授权地址生成失败')
  }
  // 整页跳转到微信授权页（用户扫码确认后微信带 code/state 回调后端 callback）
  window.location.href = url
}

/**
 * 解析微信回调落地参数。
 *
 * 后端 callback 成功后重定向到 {@code /#/login?token=xxx&refreshToken=xxx}（fragment）；
 * 失败重定向到 {@code /#/login?error=wechat_failed}。
 *
 * token 放 fragment（# 后）的设计：fragment 不随 HTTP request 发往服务器、不进 referer，
 * 避免 JWT 泄漏到 server access log / 第三方 referer。
 *
 * @returns 解析结果；非微信回调场景返回 null
 */
export function parseWechatCallback(): WechatCallbackResult | null {
  // hash 形如 "#/login?token=xxx&refreshToken=yyy" 或 "#/login?error=wechat_failed"
  const hash = window.location.hash || ''
  // vue-router hash/history 两种模式都兼容：先从 query 取，再从 hash 取
  const params = new URLSearchParams()

  // 1. 优先从 query string 取（history 模式回调可能拼在 query）
  const query = new URLSearchParams(window.location.search)
  const hasWechatParam =
    query.has('token') || query.has('error') || query.get('wechat') === '1'

  // 2. 从 hash 里提取 query 部分（history 模式 hash 为空；hash 模式 hash 含路径+query）
  const hashQueryMatch = hash.match(/\?(.+)$/)
  if (hashQueryMatch) {
    const hashQuery = new URLSearchParams(hashQueryMatch[1])
    hashQuery.forEach((v, k) => params.set(k, v))
  }
  query.forEach((v, k) => {
    if (!params.has(k)) params.set(k, v)
  })

  const token = params.get('token')
  const refreshToken = params.get('refreshToken')
  const error = params.get('error')

  // 没有任何微信回调标志 → 非微信回调
  if (!token && !error && !hasWechatParam && !hashQueryMatch?.[1]?.includes('token')) {
    return null
  }

  if (token && refreshToken) {
    return { ok: true, accessToken: token, refreshToken }
  }
  if (error) {
    return { ok: false, error }
  }
  // 有 token 无 refreshToken → 视为不完整回调
  return { ok: false, error: 'wechat_callback_incomplete' }
}

/**
 * 清除 URL 上的微信回调参数（登录成功/失败处理后调用，避免刷新重复触发）。
 * 用 history.replaceState 静默替换，不触发导航。
 */
export function clearWechatCallbackParams(): void {
  try {
    // 保留路径（通常是 /login），去掉 query 与 hash 里的 token/error
    const path = window.location.pathname || '/login'
    window.history.replaceState(null, '', path)
  } catch {
    // replaceState 失败不影响主流程
  }
}
