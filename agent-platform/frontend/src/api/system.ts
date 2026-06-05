import request from './request'
import type { ApiResponse } from './request'

export interface AuthSettings {
  accessTokenExpirationMs: number
}

export const systemApi = {
  getAuthSettings() {
    return request.get<ApiResponse<AuthSettings>>('/system/settings/auth')
  },

  updateAuthSettings(data: AuthSettings) {
    return request.put<ApiResponse<AuthSettings>>('/system/settings/auth', data)
  }
}
