// ============================================================
// 钉钉 H5 免登工具：UA 判定 + 容器内 JSAPI 免登 + 外部浏览器 OAuth2 降级
// ============================================================
import * as dd from 'dingtalk-jsapi'

const APP_KEY = import.meta.env.VITE_DINGTALK_APP_KEY as string | undefined
const REDIRECT_URI = import.meta.env.VITE_DINGTALK_REDIRECT_URI as string | undefined
const CORP_ID = import.meta.env.VITE_DINGTALK_CORP_ID as string | undefined

/** 当前是否运行在钉钉客户端 webview 内 */
export function isDingTalkClient(): boolean {
  if (typeof navigator === 'undefined') return false
  return /DingTalk/i.test(navigator.userAgent)
}

/** 钉钉免登是否已配置可用（容器内 JSAPI 至少需 appKey + corpId） */
export function isDingTalkEnabled(): boolean {
  return !!APP_KEY && (!!REDIRECT_URI || !!CORP_ID)
}

/**
 * 容器内 JSAPI 免登：静默拿 authCode，不跳转、不扫码、不要 redirect_uri/公网域名。
 * 前端拿到 authCode 后直接 POST 后端换 JWT。
 * @returns authCode
 */
export function requestDingTalkAuthCode(): Promise<string> {
  console.log('[DingTalk] JSAPI requestAuthCode 开始', {
    corpId: CORP_ID ? `${CORP_ID.slice(0, 6)}...(len=${CORP_ID.length})` : '(empty)',
    hint: CORP_ID ? '' : '⚠️ VITE_DINGTALK_CORP_ID 未填，去钉钉开放平台 > 企业信息拿 corpId 填 .env 后重启前端'
  })
  if (!CORP_ID) {
    return Promise.reject(new Error('VITE_DINGTALK_CORP_ID 未配置（容器内 JSAPI 免登必填 corpId）'))
  }
  return new Promise((resolve, reject) => {
    try {
      // dingtalk-jsapi 2.x：requestAuthCode 返回 promise，参数仅 corpId
      const ret = dd.runtime.permission.requestAuthCode({ corpId: CORP_ID })
      const p = (ret as unknown as Promise<{ code?: string; authCode?: string }>)
      p.then((res) => {
        const code = res?.code || res?.authCode
        console.log('[DingTalk] JSAPI requestAuthCode 返回:', { hasCode: !!code, codeLen: code?.length, keys: res ? Object.keys(res) : [] })
        if (!code) {
          reject(new Error('钉钉 JSAPI 未返回 authCode'))
          return
        }
        resolve(code)
      }).catch((err: unknown) => {
        console.error('[DingTalk] JSAPI requestAuthCode 失败:', err)
        reject(new Error('钉钉 JSAPI 拿 authCode 失败: ' + (err instanceof Error ? err.message : JSON.stringify(err))))
      })
    } catch (e) {
      console.error('[DingTalk] JSAPI 调用异常:', e)
      reject(e instanceof Error ? e : new Error('钉钉 JSAPI 调用异常'))
    }
  })
}

/**
 * 外部浏览器降级：跳转钉钉 OAuth2 授权页。授权后钉钉带 authCode 回到 REDIRECT_URI。
 * 注意：OAuth2 路径需公网 https 回调域名 + 钉钉「网页应用/登录回调域名」白名单，本地容器内不用走这条。
 * @param state 透传状态，防 CSRF（回调页校验）
 */
export function redirectToDingTalkAuth(state = 'dt'): void {
  const appKeyMasked = APP_KEY ? `${APP_KEY.slice(0, 6)}...${APP_KEY.slice(-4)}(len=${APP_KEY.length})` : '(empty)'
  console.log('[DingTalk] OAuth2 降级跳转 ->', {
    appKey: appKeyMasked,
    redirectUri: REDIRECT_URI ?? '(empty)',
    inDingTalkClient: isDingTalkClient()
  })
  if (!APP_KEY || !REDIRECT_URI) {
    console.error('[DingTalk] OAuth2 降级未配置 appKey/redirectUri')
    throw new Error('钉钉 OAuth2 降级未配置 VITE_DINGTALK_APP_KEY / VITE_DINGTALK_REDIRECT_URI')
  }
  const params = new URLSearchParams({
    redirect_uri: REDIRECT_URI!,
    response_type: 'code',
    client_id: APP_KEY!,
    scope: 'openid',
    state,
    prompt: 'consent'
  })
  const target = `https://login.dingtalk.com/oauth2/auth?${params.toString()}`
  console.log('[DingTalk] OAuth2 跳转 URL:', target)
  window.location.href = target
}
