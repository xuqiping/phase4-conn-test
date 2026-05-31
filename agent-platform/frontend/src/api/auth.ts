// ============================================================
// 认证模块API
// 对应后端 /api/auth/* 端点
// ============================================================

import request from './request'
import type { ApiResponse } from './request'
import type { UserInfo } from '@/stores/auth'

/** 登录响应数据 */
export interface LoginResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  userInfo: UserInfo
}

/** 刷新Token响应数据 */
export interface RefreshResponse {
  accessToken: string
  expiresIn: number
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
   * 获取当前用户信息
   * GET /api/auth/me
   */
  getMe() {
    return request.get<ApiResponse<UserInfo>>('/auth/me')
  }
}
