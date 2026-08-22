// ============================================================
// Axios 请求封装
// - 自动添加JWT Authorization请求头
// - 401时自动刷新token
// - 统一错误处理（Naive UI message提示）
// ============================================================

import axios from 'axios'
import type { AxiosInstance, InternalAxiosRequestConfig, AxiosResponse } from 'axios'
import { createDiscreteApi } from 'naive-ui'
import { getStorage, clearAuthStorage, STORAGE_KEYS } from '@/utils/storage'
import { isDingTalkClient, isDingTalkEnabled, redirectToDingTalkAuth } from '@/utils/dingtalk'

declare module 'axios' {
  interface AxiosRequestConfig {
    /**
     * 2x#断路误伤（四轮 Step1）：标记为后台型请求（轮询/blob 预取/断点续轮）。
     * true → 网络层失败不计连续错误、不触发断路跳登录，toast 换「后台任务网络波动」；
     * 失败仍 console.warn 留诊断痕迹（运维考量）。用户主动操作不要标——豁免只给后台。
     */
    _background?: boolean
  }
  interface InternalAxiosRequestConfig extends AxiosRequestConfig {
    _retry?: boolean
  }
}

/** 后端统一响应格式 */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
}

/** 创建Axios实例 */
const request: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json'
  }
})

let isRedirectingToLogin = false

/**
 * Phase4 修正（S5 交叉审查）：401 先尝试静默刷新 access token（15min 短命），
 * 刷新成功重放原请求；失败/无 refresh → 跳登录。此前拦截器直接跳登录——
 * stores/auth.refreshAccessToken 是死代码，用户每 15min 被踢一次。
 * 单飞（single-flight）：并发多请求同时 401 只发一次 /auth/refresh，共享同一 Promise。
 */
let refreshPromise: Promise<string | null> | null = null

async function tryRefreshAccessToken(): Promise<string | null> {
  const rt = getStorage<string>(STORAGE_KEYS.REFRESH_TOKEN)
  if (!rt) return null
  if (!refreshPromise) {
    refreshPromise = (async () => {
      // 动态 import：request.ts ← stores/auth.ts ← api/auth.ts ← request.ts 静态环
      const { useAuthStore } = await import('@/stores/auth')
      const store = useAuthStore()
      const newAt = await store.refreshAccessToken()
      return newAt
    })()
      .catch(() => null)
      .finally(() => {
        // 微任务清引用：本轮并发 401 已拿到引用，下一轮可重新发起
        setTimeout(() => {
          refreshPromise = null
        }, 0)
      })
  }
  return refreshPromise
}

/** 连续网络层错误（无 response：超时/断网/后端不可达）计数。任一成功响应归零。 */
let consecutiveNetErrors = 0
/** 上次「网络异常」toast 时间戳，用于节流，避免轮询风暴刷屏。 */
let lastNetToastAt = 0
/** 上次「后台网络波动」toast 时间戳（独立节流 30s：后台退避本身在拉长间隔，提示无需更频）。 */
let lastBgToastAt = 0
/** 连续网络错误达此阈值 → 视同会话/服务失效，跳登录（卸载页面即停止轮询，打破死亡螺旋）。 */
const NET_ERROR_CIRCUIT_THRESHOLD = 5

/**
 * 请求拦截器 — 自动添加Authorization头
 */
request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN)
    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

/**
 * 响应拦截器 — 统一错误处理 + 自动刷新token
 */
request.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    // 二进制下载（blob/arraybuffer）无 {code,msg,data} 包装，跳过业务码校验原样返回。
    // 如视频下载端点返回 video/mp4，若走 code 校验会被当业务错误弹「请求失败」。
    const rt = response.config?.responseType
    if (rt === 'blob' || rt === 'arraybuffer') {
      consecutiveNetErrors = 0
      return response
    }

    const res = response.data

    // 任一成功响应 → 清零连续网络错误计数
    consecutiveNetErrors = 0

    // 业务错误码处理
    if (res.code !== 200 && res.code !== 201 && res.code !== 202) {
      showErrorMessage(res.message || '请求失败')
      // 12x B2：错误对象挂业务码（如 40107 滑块门槛），UI 可据此分支处理
      const err = new Error(res.message || '请求失败') as Error & { code?: number }
      err.code = res.code
      return Promise.reject(err)
    }

    return response
  },
  async (error) => {
    const originalRequest = error.config

    if (error.response?.status === 401
        && originalRequest?.url !== '/auth/login'
        && originalRequest?.url !== '/auth/refresh') {
      // Phase4：静默刷新一次并重放原请求；刷新失败或已重放过 → 跳登录
      if (!originalRequest._retry) {
        const newAt = await tryRefreshAccessToken()
        if (newAt) {
          originalRequest._retry = true
          originalRequest.headers = originalRequest.headers ?? {}
          originalRequest.headers.Authorization = `Bearer ${newAt}`
          return request(originalRequest)
        }
      }
      redirectToLogin()
      return Promise.reject(error)
    }

    // 网络层错误（无 response：超时 / 断网 / 后端不可达）。
    // RB-001 死亡螺旋：此前轮询接口超时不触发 401 跳转，每 3s 刷屏且不停止。
    // 现累计连续网络错误：节流提示（5s 内不重复弹）+ 达阈值视同会话失效跳登录（卸载页面即停轮询）。
    if (!error.response) {
      // 2x 四轮 Step1：后台型请求（轮询/blob 预取）豁免断路与计数——断网 30s 不弹「服务不可达」
      // 不踢会话，任务恢复后自动续跑；错误仍进 console 供诊断。主动操作不受此分支影响（安全语义不降级）。
      if (originalRequest?._background) {
        console.warn('[bg-request] 网络波动', originalRequest.url, error.message)
        const now = Date.now()
        if (now - lastBgToastAt > 30000) {
          lastBgToastAt = now
          showErrorMessage('后台任务网络波动，恢复后自动重试')
        }
        return Promise.reject(error)
      }
      consecutiveNetErrors++
      const now = Date.now()
      if (now - lastNetToastAt > 5000) {
        lastNetToastAt = now
        showErrorMessage('网络异常：服务暂不可达，请检查网络或稍后重试')
      }
      if (consecutiveNetErrors >= NET_ERROR_CIRCUIT_THRESHOLD) {
        consecutiveNetErrors = 0
        redirectToLogin()
      }
      return Promise.reject(error)
    }

    // 其他错误 — 拼装可读的错误信息
    const status = error.response?.status
    const serverMsg = error.response?.data?.message
    const errMsg = serverMsg || error.message || '网络错误'
    const displayMsg = status ? `${status} · ${errMsg}` : errMsg
    showErrorMessage(displayMsg)
    return Promise.reject(error)
  }
)

function redirectToLogin() {
  clearAuthStorage()
  if (isRedirectingToLogin) return
  isRedirectingToLogin = true
  // 钉钉容器内 → 重新免登；否则回账密登录页
  if (isDingTalkClient() && isDingTalkEnabled()) {
    redirectToDingTalkAuth('dt')
    return
  }
  const current = window.location.pathname + window.location.search
  const redirect = current && current !== '/login' ? `?redirect=${encodeURIComponent(current)}` : ''
  window.location.href = `/login${redirect}`
}

/**
 * 17x#2 成员功能开关（V139）：后端拦截文案带 kind 英文码（如「该类模型（CHAT）」），
 * 统一翻成中文模块名再弹，用户不用猜码。只动已知码，其余原文。
 */
const GROUP_KIND_ZH: Record<string, string> = {
  CHAT: '对话', EMBED: '嵌入', RERANK: '重排', IMAGE: '图片', VIDEO: '视频'
}
function localizeKindCode(msg: string): string {
  return msg.replace(/（(CHAT|EMBED|RERANK|IMAGE|VIDEO)）/g, (_m, k: string) => `（${GROUP_KIND_ZH[k] ?? k}）`)
}

/**
 * 显示错误消息（使用Naive UI的discrete message API）
 * 因为拦截器在组件外部运行，需要使用discrete方式创建message实例
 */
let messageApi: ReturnType<typeof createDiscreteApi>['message'] | null = null

function showErrorMessage(msg: string) {
  msg = localizeKindCode(msg)
  import('naive-ui').then(({ createDiscreteApi, darkTheme }) => {
    if (!messageApi) {
      const api = createDiscreteApi(['message'], {
        configProviderProps: { theme: darkTheme }
      })
      messageApi = api.message
    }
    messageApi!.error(msg, { duration: 4000 })
  })
}

export default request
