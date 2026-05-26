// ============================================================
// Axios 请求封装
// - 自动添加JWT Authorization请求头
// - 401时自动刷新token
// - 统一错误处理（Naive UI message提示）
// ============================================================

import axios from 'axios'
import type { AxiosInstance, InternalAxiosRequestConfig, AxiosResponse } from 'axios'
import { getStorage, setStorage, removeStorage, STORAGE_KEYS } from '@/utils/storage'

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

// 是否正在刷新token的标记（防止并发刷新）
let isRefreshing = false
// 等待token刷新的请求队列
let pendingRequests: Array<(token: string) => void> = []

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
    const res = response.data

    // 业务错误码处理
    if (res.code !== 200 && res.code !== 201 && res.code !== 202) {
      showErrorMessage(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }

    return response
  },
  async (error) => {
    const originalRequest = error.config

    // 401 Token过期 — 尝试刷新
    if (error.response?.status === 401 && !originalRequest._retry) {
      const errorCode = error.response?.data?.code

      // Token过期（业务码40101），尝试刷新
      if (errorCode === 40101) {
        if (isRefreshing) {
          // 正在刷新中，将请求加入等待队列
          return new Promise((resolve) => {
            pendingRequests.push((token: string) => {
              originalRequest.headers.Authorization = `Bearer ${token}`
              resolve(request(originalRequest))
            })
          })
        }

        originalRequest._retry = true
        isRefreshing = true

        try {
          const refreshToken = getStorage<string>(STORAGE_KEYS.REFRESH_TOKEN)
          if (!refreshToken) {
            throw new Error('无刷新令牌')
          }

          // 发起刷新请求
          const res = await axios.post<ApiResponse<{ accessToken: string }>>(
            '/api/auth/refresh',
            { refreshToken }
          )

          const newToken = res.data.data.accessToken
          setStorage(STORAGE_KEYS.ACCESS_TOKEN, newToken)

          // 执行等待队列中的请求
          pendingRequests.forEach(cb => cb(newToken))
          pendingRequests = []

          // 重试原始请求
          originalRequest.headers.Authorization = `Bearer ${newToken}`
          return request(originalRequest)
        } catch (refreshError) {
          // 刷新失败，清除认证信息，跳转登录页
          pendingRequests = []
          removeStorage(STORAGE_KEYS.ACCESS_TOKEN)
          removeStorage(STORAGE_KEYS.REFRESH_TOKEN)
          removeStorage(STORAGE_KEYS.USER_INFO)
          window.location.href = '/login'
          return Promise.reject(refreshError)
        } finally {
          isRefreshing = false
        }
      }
    }

    // 其他错误
    const message = error.response?.data?.message || error.message || '网络错误'
    showErrorMessage(message)
    return Promise.reject(error)
  }
)

/**
 * 显示错误消息（使用Naive UI的discrete message API）
 * 因为拦截器在组件外部运行，需要使用discrete方式创建message实例
 */
function showErrorMessage(message: string) {
  // 动态导入Naive UI的discrete API
  import('naive-ui').then(({ createDiscreteApi, darkTheme }) => {
    const { message } = createDiscreteApi(['message'], {
      configProviderProps: {
        theme: darkTheme
      }
    })
    message.error(message, { duration: 3000 })
  })
}

export default request
