// ============================================================
// 钉钉 H5 免登工具：UA 判定 + 授权重定向
// ============================================================

const APP_KEY = import.meta.env.VITE_DINGTALK_APP_KEY as string | undefined
const REDIRECT_URI = import.meta.env.VITE_DINGTALK_REDIRECT_URI as string | undefined

/** 当前是否运行在钉钉客户端 webview 内 */
export function isDingTalkClient(): boolean {
  if (typeof navigator === 'undefined') return false
  return /DingTalk/i.test(navigator.userAgent)
}

/** 钉钉免登是否已配置可用 */
export function isDingTalkEnabled(): boolean {
  return !!APP_KEY && !!REDIRECT_URI
}

/**
 * 跳转钉钉授权页。授权后钉钉带 authCode 回到 REDIRECT_URI。
 * @param state 透传状态，防 CSRF（回调页校验）
 */
export function redirectToDingTalkAuth(state = 'dt'): void {
  if (!isDingTalkEnabled()) {
    throw new Error('钉钉免登未配置 VITE_DINGTALK_APP_KEY / VITE_DINGTALK_REDIRECT_URI')
  }
  const params = new URLSearchParams({
    redirect_uri: REDIRECT_URI!,
    response_type: 'code',
    client_id: APP_KEY!,
    scope: 'openid',
    state,
    prompt: 'consent'
  })
  window.location.href = `https://login.dingtalk.com/oauth2/auth?${params.toString()}`
}
