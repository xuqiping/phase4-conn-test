import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import type { UserSummary } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref(localStorage.getItem('accessToken') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')
  const user = ref<UserSummary | null>(null)

  const isAuthenticated = computed(() => !!accessToken.value)

  async function login(identifier: string, password: string) {
    const res = await authApi.login(identifier, password)
    accessToken.value = res.accessToken
    refreshToken.value = res.refreshToken
    user.value = res.user
    localStorage.setItem('accessToken', res.accessToken)
    localStorage.setItem('refreshToken', res.refreshToken)
  }

  async function logout() {
    try {
      if (refreshToken.value) {
        await authApi.logout(refreshToken.value)
      }
    } catch {
      // 忽略登出错误
    } finally {
      accessToken.value = ''
      refreshToken.value = ''
      user.value = null
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
    }
  }

  return { accessToken, refreshToken, user, isAuthenticated, login, logout }
})
