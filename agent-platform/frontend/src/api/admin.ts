import request from './request'
import type { ApiResponse } from './request'

// === 类型定义 ===

export interface UserVO {
  id: number
  username: string
  email: string | null
  avatar: string | null
  status: string
  lastLoginAt: string | null
  createdAt: string
  roles: string[]
  permissions: string[]
}

export interface Role {
  id: number
  name: string
  code: string
  description: string | null
}

export interface Permission {
  id: number
  name: string
  code: string
  resource: string
  action: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

// === API函数 ===

export const adminApi = {
  // 用户管理
  listUsers(page = 1, size = 10) {
    return request.get<ApiResponse<PageResult<UserVO>>>('/users', { params: { page, size } })
  },
  getUser(id: number) {
    return request.get<ApiResponse<UserVO>>(`/users/${id}`)
  },
  updateUserStatus(id: number, status: string) {
    return request.put<ApiResponse<void>>(`/users/${id}/status`, { status })
  },
  assignRoles(id: number, roleIds: number[]) {
    return request.put<ApiResponse<void>>(`/users/${id}/roles`, roleIds)
  },

  // 角色管理
  listAllRoles() {
    return request.get<ApiResponse<Role[]>>('/roles/all')
  },
  getRolePermissions(roleId: number) {
    return request.get<ApiResponse<number[]>>(`/roles/${roleId}/permissions`)
  },
  updateRolePermissions(roleId: number, permissionIds: number[]) {
    return request.put<ApiResponse<void>>(`/roles/${roleId}/permissions`, permissionIds)
  },
  listAllPermissions() {
    return request.get<ApiResponse<Permission[]>>('/roles/permissions/all')
  }
}
