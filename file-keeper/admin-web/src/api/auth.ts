import request, { unwrap } from './request'
import type { AuthResponse } from '@/types'

export function login(identifier: string, password: string): Promise<AuthResponse> {
  return unwrap(request.post('/api/admin/auth/login', { identifier, password }))
}

export function refreshAccessToken(refreshToken: string): Promise<AuthResponse> {
  return unwrap(request.post('/api/admin/auth/refresh', { refreshToken }))
}

export function logout(refreshToken: string): Promise<void> {
  return unwrap(request.post('/api/admin/auth/logout', { refreshToken }))
}
