import request from './request'
import type { ApiResponse } from './request'

/** 项目（记忆 scope 容器，V33）。用户私有 + 可共享。 */
export interface Project {
  id: number
  name: string
  description: string | null
  icon: string | null
  sortOrder: number
  ownerId: number
  createdAt: string
  /** 当前用户在此项目的角色（OWNER/EDITOR/VIEWER）。 */
  myRole: string | null
  memberCount: number
}

export interface ProjectCreateRequest {
  name: string
  description?: string
  icon?: string
  sortOrder?: number
}

export interface ProjectMember {
  id: number
  projectId: number
  userId: number
  username: string | null
  role: string   // OWNER / EDITOR / VIEWER
  createdAt: string
}

export interface ProjectShareRequest {
  userId: number
  role?: string  // EDITOR / VIEWER
}

export const projectApi = {
  list() {
    return request.get<ApiResponse<Project[]>>('/projects')
  },
  get(id: number) {
    return request.get<ApiResponse<Project>>(`/projects/${id}`)
  },
  create(data: ProjectCreateRequest) {
    return request.post<ApiResponse<Project>>('/projects', data)
  },
  update(id: number, data: ProjectCreateRequest) {
    return request.put<ApiResponse<Project>>(`/projects/${id}`, data)
  },
  delete(id: number) {
    return request.delete<ApiResponse<void>>(`/projects/${id}`)
  },
  listMembers(id: number) {
    return request.get<ApiResponse<ProjectMember[]>>(`/projects/${id}/members`)
  },
  addMember(id: number, data: ProjectShareRequest) {
    return request.post<ApiResponse<ProjectMember>>(`/projects/${id}/members`, data)
  },
  removeMember(id: number, memberId: number) {
    return request.delete<ApiResponse<void>>(`/projects/${id}/members/${memberId}`)
  }
}
