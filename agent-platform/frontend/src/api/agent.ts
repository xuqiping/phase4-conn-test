// ============================================================
// Agent模块API
// 对应后端 /api/agent-groups、/api/agents、/api/skills 端点
// ============================================================

import request from './request'
import type { ApiResponse } from './request'

// === 类型定义 ===

/** Agent分组 */
export interface AgentGroup {
  id: number
  name: string
  icon: string | null
  description: string | null
  sortOrder: number
  agentCount: number
  createdAt: string
}

/** Agent列表项 */
export interface Agent {
  id: number
  name: string
  description: string | null
  avatar: string | null
  status: string
  groupId: number | null
  groupName: string | null
  skillCount: number
  createdAt: string
}

/** 技能概要 */
export interface Skill {
  id: number
  name: string
  description: string | null
  type: string | null
  sortOrder: number
  createdAt: string
}

/** Agent详情 */
export interface AgentDetail {
  id: number
  name: string
  description: string | null
  avatar: string | null
  status: string
  config: string | null
  groupId: number | null
  groupName: string | null
  skills: Skill[]
  createdAt: string
  updatedAt: string
}

/** 技能步骤 */
export interface SkillStep {
  id: number
  stepOrder: number
  name: string
  action: string | null
  config: string | null
}

/** 技能详情 */
export interface SkillDetail {
  id: number
  agentId: number
  agentName: string
  name: string
  description: string | null
  type: string | null
  config: string | null
  sortOrder: number
  steps: SkillStep[]
  createdAt: string
  updatedAt: string
}

// === API函数 ===

export const agentApi = {
  /**
   * 获取Agent分组列表
   * GET /api/agent-groups
   */
  getGroups() {
    return request.get<ApiResponse<AgentGroup[]>>('/agent-groups')
  },

  /**
   * 获取Agent列表（可选分组和关键词筛选）
   * GET /api/agents?groupId=&keyword=
   */
  listAgents(params?: { groupId?: number; keyword?: string }) {
    return request.get<ApiResponse<Agent[]>>('/agents', { params })
  },

  /**
   * 获取Agent详情（含skills）
   * GET /api/agents/{id}
   */
  getAgentDetail(id: number) {
    return request.get<ApiResponse<AgentDetail>>(`/agents/${id}`)
  },

  /**
   * 获取Agent的技能列表
   * GET /api/agents/{id}/skills
   */
  getSkills(agentId: number) {
    return request.get<ApiResponse<Skill[]>>(`/agents/${agentId}/skills`)
  },

  /**
   * 获取技能详情（含steps）
   * GET /api/skills/{id}
   */
  getSkillDetail(id: number) {
    return request.get<ApiResponse<SkillDetail>>(`/skills/${id}`)
  },

  createAgent(data: { name: string; description?: string; avatar?: string; groupId: number }) {
    return request.post<ApiResponse<Agent>>('/agents', data)
  },

  updateAgent(id: number, data: { name?: string; description?: string; avatar?: string; groupId?: number }) {
    return request.put<ApiResponse<Agent>>(`/agents/${id}`, data)
  },

  deleteAgent(id: number) {
    return request.delete<ApiResponse<void>>(`/agents/${id}`)
  },

  updateAgentStatus(id: number, status: string) {
    return request.put<ApiResponse<void>>(`/agents/${id}/status`, { status })
  }
}
