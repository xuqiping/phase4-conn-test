/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 钉钉 H5 微应用 AppKey（= OAuth client_id，客户端公开值） */
  readonly VITE_DINGTALK_APP_KEY?: string
  /** 钉钉授权回调地址，需与钉钉平台配置一致 */
  readonly VITE_DINGTALK_REDIRECT_URI?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
