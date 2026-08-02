import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  AuthApiError,
  checkVerificationCode,
  login,
  logout,
  refresh,
  register,
  sendVerificationCode,
  type AuthResponse,
  type UserSummary
} from '../auth'

const mocks = vi.hoisted(() => ({
  fetch: vi.fn()
}))

const user: UserSummary = {
  id: 10,
  email: 'user@example.com',
  phone: null,
  role: 'user',
  status: 'pending_review',
  emailVerified: true,
  phoneVerified: false,
  deviceLimit: 2,
  offlineCacheMinutes: 1440
}

const authResponse: AuthResponse = {
  accessToken: 'access-token',
  refreshToken: 'refresh-token',
  expiresInSeconds: 900,
  user
}

describe('client auth API', () => {
  beforeEach(() => {
    mocks.fetch.mockReset()
    vi.stubGlobal('fetch', mocks.fetch)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('sends verification code with contact type and contact', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: null })
    })

    await sendVerificationCode('http://localhost:8080/', {
      contactType: 'email',
      contact: 'user@example.com'
    })

    expect(mocks.fetch).toHaveBeenCalledWith('http://localhost:8080/api/client/verification/send', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ contactType: 'email', contact: 'user@example.com' })
    })
  })

  it('checks verification code and returns verified flag', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: { verified: true } })
    })

    const result = await checkVerificationCode('http://localhost:8080', {
      contactType: 'phone',
      contact: '13800138000',
      code: '123456'
    })

    expect(mocks.fetch).toHaveBeenCalledWith('http://localhost:8080/api/client/verification/check', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ contactType: 'phone', contact: '13800138000', code: '123456' })
    })
    expect(result).toBe(true)
  })

  it('registers user and returns user summary', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: user })
    })

    const result = await register('http://localhost:8080/', {
      email: 'user@example.com',
      phone: null,
      password: 'Secret123!'
    })

    expect(mocks.fetch).toHaveBeenCalledWith('http://localhost:8080/api/client/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'user@example.com', phone: null, password: 'Secret123!' })
    })
    expect(result).toEqual(user)
  })

  it('logs in with identifier and password and returns auth response', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: authResponse })
    })

    const result = await login('http://localhost:8080', 'user@example.com', 'Secret123!')

    expect(mocks.fetch).toHaveBeenCalledWith('http://localhost:8080/api/client/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ identifier: 'user@example.com', password: 'Secret123!' })
    })
    expect(result).toEqual(authResponse)
  })

  it('refreshes access token with refresh token', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: authResponse })
    })

    const result = await refresh('http://localhost:8080/', 'refresh-token')

    expect(mocks.fetch).toHaveBeenCalledWith('http://localhost:8080/api/client/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: 'refresh-token' })
    })
    expect(result).toEqual(authResponse)
  })

  it('logs out with refresh token', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: null })
    })

    await logout('http://localhost:8080', 'refresh-token')

    expect(mocks.fetch).toHaveBeenCalledWith('http://localhost:8080/api/client/auth/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: 'refresh-token' })
    })
  })

  it('throws structured API error when API response code is not success', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 409, msg: '验证码错误', data: null })
    })

    const result = checkVerificationCode('http://localhost:8080', {
      contactType: 'email',
      contact: 'user@example.com',
      code: '000000'
    })

    await expect(result).rejects.toBeInstanceOf(AuthApiError)
    await expect(result).rejects.toMatchObject({
      message: '验证码错误',
      status: 200,
      code: 409
    })
  })

  it('throws structured API error with HTTP status when response is not ok and backend message is absent', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: () => Promise.resolve({ code: 500, msg: '', data: null })
    })

    const result = login('http://localhost:8080', 'user@example.com', 'Secret123!')

    await expect(result).rejects.toBeInstanceOf(AuthApiError)
    await expect(result).rejects.toMatchObject({
      message: '请求失败：500',
      status: 500,
      code: 500
    })
  })

  it('does not wrap fetch network failures', async () => {
    const networkError = new TypeError('Failed to fetch')
    mocks.fetch.mockRejectedValueOnce(networkError)

    await expect(refresh('http://localhost:8080', 'refresh-token')).rejects.toBe(networkError)
  })
})
