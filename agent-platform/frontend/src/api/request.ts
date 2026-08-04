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
  interface InternalAxiosRequestConfig {
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

/** 连续网络层错误（无 response：超时/断网/后端不可达）计数。任一成功响应归零。 */
let consecutiveNetErrors = 0
/** 上次「网络异常」toast 时间戳，用于节流，避免轮询风暴刷屏。 */
let lastNetToastAt = 0
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
      return Promise.reject(new Error(res.message || '请求失败'))
    }

    return response
  },
  async (error) => {
    const originalRequest = error.config

    if (error.response?.status === 401 && originalRequest?.url !== '/auth/login') {
      redirectToLogin()
      return Promise.reject(error)
    }

    // 网络层错误（无 response：超时 / 断网 / 后端不可达）。
    // RB-001 死亡螺旋：此前轮询接口超时不触发 401 跳转，每 3s 刷屏且不停止。
    // 现累计连续网络错误：节流提示（5s 内不重复弹）+ 达阈值视同会话失效跳登录（卸载页面即停轮询）。
    if (!error.response) {
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
 * 显示错误消息（使用Naive UI的discrete message API）
 * 因为拦截器在组件外部运行，需要使用discrete方式创建message实例
 */
let messageApi: ReturnType<typeof createDiscreteApi>['message'] | null = null

function showErrorMessage(msg: string) {
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
