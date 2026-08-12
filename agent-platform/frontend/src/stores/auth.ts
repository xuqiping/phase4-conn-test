// ============================================================
// 认证状态管理 — Pinia Store
// 管理用户登录态、token、用户信息
// ============================================================

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/api/auth'
import {
  STORAGE_KEYS,
  getStorage,
  setStorage,
  clearAuthStorage
} from '@/utils/storage'

/** 用户信息接口 */
export interface UserInfo {
  id: number
  username: string
  /** 显示名/真实姓名（钉钉 nick），为空时回退 username */
  name?: string | null
  /** 主部门名（右上角/用户列表显示「部门 - 姓名」用），为空时不显示部门 */
  primaryDepartmentName?: string | null
  email: string | null
  avatar: string | null
  roles: string[]
  permissions: string[]
}

/** 登录请求参数 */
export interface LoginParams {
  username: string
  password: string
}

/** 注册请求参数 */
export interface RegisterParams {
  username: string
  email: string
  password: string
}

export const useAuthStore = defineStore('auth', () => {
  // === 状态 ===
  const accessToken = ref<string | null>(getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN))
  const refreshToken = ref<string | null>(getStorage<string>(STORAGE_KEYS.REFRESH_TOKEN))
  const userInfo = ref<UserInfo | null>(getStorage<UserInfo>(STORAGE_KEYS.USER_INFO))
  const loading = ref(false)

  // === 计算属性 ===
  /** 是否已登录 */
  const isLoggedIn = computed(() => !!accessToken.value && !!userInfo.value)

  /** 是否是管理员 */
  const isAdmin = computed(() => userInfo.value?.roles?.includes('admin') ?? false)

  // === Actions ===

  /**
   * 用户登录
   * 调用登录API，存储token和用户信息
   */
  async function login(params: LoginParams) {
    loading.value = true
    try {
      const res = await authApi.login(params)
      const { accessToken: at, refreshToken: rt, userInfo: info } = res.data.data

      accessToken.value = at
      refreshToken.value = rt
      userInfo.value = info

      // 持久化到 localStorage
      setStorage(STORAGE_KEYS.ACCESS_TOKEN, at)
      setStorage(STORAGE_KEYS.REFRESH_TOKEN, rt)
      setStorage(STORAGE_KEYS.USER_INFO, info)
    } finally {
      loading.value = false
    }
  }

  /**
   * 用户注册
   */
  async function register(params: RegisterParams) {
    loading.value = true
    try {
      await authApi.register(params)
    } finally {
      loading.value = false
    }
  }

  /**
   * 用户登出
   * 清除本地状态，可选调用后端登出接口
   */
  async function logout() {
    try {
      if (refreshToken.value) {
        await authApi.logout(refreshToken.value)
      }
    } catch {
      // 登出接口失败不影响本地清理
    } finally {
      // 清除状态
      accessToken.value = null
      refreshToken.value = null
      userInfo.value = null
      clearAuthStorage()
    }
  }

  /**
   * 获取当前用户信息（刷新页面时调用）
   */
  async function fetchUserInfo() {
    try {
      const res = await authApi.getMe()
      userInfo.value = res.data.data
      setStorage(STORAGE_KEYS.USER_INFO, res.data.data)
    } catch {
      // 获取失败，可能token已过期
      await logout()
    }
  }

  /**
   * 刷新访问令牌
   * @returns 新的访问令牌
   */
  async function refreshAccessToken(): Promise<string> {
    if (!refreshToken.value) {
      throw new Error('无刷新令牌')
    }

    const res = await authApi.refresh(refreshToken.value)
    const newToken = res.data.data.accessToken

    accessToken.value = newToken
    setStorage(STORAGE_KEYS.ACCESS_TOKEN, newToken)

    return newToken
  }

  /**
   * 把一组 token + 用户信息写入状态 + 持久化（多通道登录共用）。
   */
  function applyTokenPair(at: string, rt: string, info: UserInfo) {
    accessToken.value = at
    refreshToken.value = rt
    userInfo.value = info
    setStorage(STORAGE_KEYS.ACCESS_TOKEN, at)
    setStorage(STORAGE_KEYS.REFRESH_TOKEN, rt)
    setStorage(STORAGE_KEYS.USER_INFO, info)
  }

  /**
   * 钉钉免登：用 authCode 换 token
   * @param source 'jsapi'(容器内免登码,走 oapi 老链路) | 'oauth2'(网页授权码)
   */
  async function loginByDingTalk(authCode: string, source: 'jsapi' | 'oauth2' = 'oauth2') {
    loading.value = true
    try {
      const res = await authApi.dingTalkLogin(authCode, source)
      const { accessToken: at, refreshToken: rt, userInfo: info } = res.data.data
      applyTokenPair(at, rt, info)
    } finally {
      loading.value = false
    }
  }

  /**
   * 手机验证码登录（新号自动建号）。
   */
  async function loginBySms(phone: string, code: string) {
    loading.value = true
    try {
      const res = await authApi.loginBySms(phone, code)
      const { accessToken: at, refreshToken: rt, userInfo: info } = res.data.data
      applyTokenPair(at, rt, info)
    } finally {
      loading.value = false
    }
  }

  /**
   * 微信扫码登录回调落地：后端回调已换好 JWT，前端用 token 落地。
   * @param at 访问令牌
   * @param rt 刷新令牌
   * @param info 用户信息（可选；缺失时调 getMe 补全）
   */
  async function loginByWechatToken(at: string, rt: string, info?: UserInfo) {
    loading.value = true
    try {
      if (!info) {
        // 回调只给了 token，用户信息用 /me 补全
        accessToken.value = at
        refreshToken.value = rt
        setStorage(STORAGE_KEYS.ACCESS_TOKEN, at)
        setStorage(STORAGE_KEYS.REFRESH_TOKEN, rt)
        await fetchUserInfo()
      } else {
        applyTokenPair(at, rt, info)
      }
    } finally {
      loading.value = false
    }
  }

  /**
   * 检查用户是否拥有指定权限
   * @param permission 权限代码，如 'agent:create'
   */
  function hasPermission(permission: string): boolean {
    return userInfo.value?.permissions?.includes(permission) ?? false
  }

  return {
    // 状态
    accessToken,
    refreshToken,
    userInfo,
    loading,
    // 计算属性
    isLoggedIn,
    isAdmin,
    // Actions
    login,
    register,
    logout,
    fetchUserInfo,
    refreshAccessToken,
    loginByDingTalk,
    loginBySms,
    loginByWechatToken,
    hasPermission
  }
})
