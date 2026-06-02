# Agent大厅页 + Agent详情页 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**目标：** 实现Agent大厅页面（分组筛选+搜索+卡片网格）和Agent详情页面（技能列表+技能详情+工作流时间线），替换Plan 3中的占位视图。

**前提条件：** Plan 3已完成。Vue3+TS+Vite项目就绪，3套暗色主题CSS变量驱动，Pinia stores（auth/theme）、Vue Router、Axios JWT封装、MainLayout（侧栏+顶栏）、Naive UI全局注册全部可用。

**架构：** 替换占位视图，新增4个业务组件 + 1个API封装模块。数据流：API -> 视图 -> 子组件。CSS变量适配3套主题。

**技术栈：** Vue 3.4+, TypeScript 5, Naive UI 2, Axios, Sass, @vicons/ionicons5

**API参考：** `docs/superpowers/plans/plan2-agent-workflow.md`（Agent/Workflow模块API端点）

---

## 文件结构

```
frontend/src/
├── api/
│   └── agent.ts                    # Agent相关API（getGroups/listAgents/getDetail/getSkills/getSkillDetail）
├── views/
│   ├── AgentHallView.vue           # 替换占位 - Agent大厅
│   └── AgentDetailView.vue         # 替换占位 - Agent详情
├── components/
│   ├── AgentCard.vue               # Agent卡片组件（渐变色条+图标+名称+技能预览）
│   ├── SkillList.vue               # 技能列表组件（左侧技能项）
│   ├── SkillDetail.vue             # 技能详情组件（右侧：目的+工作流步骤时间线）
│   └── WorkflowTimeline.vue        # 工作流步骤时间线（垂直编号+连线）
```

---

### Task 1: Agent API封装

**Files:**
- Create: `agent-platform/frontend/src/api/agent.ts`

API端点对应后端Controller：
- GET /api/agent-groups → 分组列表
- GET /api/agents?groupId=&keyword= → Agent列表（可选筛选）
- GET /api/agents/{id} → Agent详情（含skills数组）
- GET /api/agents/{id}/skills → Agent的技能列表
- GET /api/skills/{id} → 技能详情（含steps数组）

后端VO结构：
- AgentGroupVO: id, name, icon, description, sortOrder, agentCount, createdAt
- AgentVO: id, name, description, avatar, status, groupId, groupName, skillCount, createdAt
- AgentDetailVO: id, name, description, avatar, status, config, groupId, groupName, skills(List<SkillVO>), createdAt, updatedAt
- SkillVO: id, name, description, type, sortOrder, createdAt
- SkillDetailVO: id, agentId, agentName, name, description, type, config, sortOrder, steps(List<SkillStepVO>), createdAt, updatedAt
- SkillStepVO: id, stepOrder, name, action, config

- [ ] **Step 1: 创建 api/agent.ts — Agent API封装**

```typescript
// api/agent.ts
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
  /** 获取Agent分组列表 */
  getGroups() {
    return request.get<ApiResponse<AgentGroup[]>>('/agent-groups')
  },

  /** 获取Agent列表（可选分组和关键词筛选） */
  listAgents(params?: { groupId?: number; keyword?: string }) {
    return request.get<ApiResponse<Agent[]>>('/agents', { params })
  },

  /** 获取Agent详情（含skills） */
  getAgentDetail(id: number) {
    return request.get<ApiResponse<AgentDetail>>(`/agents/${id}`)
  },

  /** 获取Agent的技能列表 */
  getSkills(agentId: number) {
    return request.get<ApiResponse<Skill[]>>(`/agents/${agentId}/skills`)
  },

  /** 获取技能详情（含steps） */
  getSkillDetail(id: number) {
    return request.get<ApiResponse<SkillDetail>>(`/skills/${id}`)
  }
}
```

---

### Task 2: AgentCard组件

**Files:**
- Create: `agent-platform/frontend/src/components/AgentCard.vue`

- [ ] **Step 1: 创建 AgentCard.vue — Agent卡片组件**

功能要点：
- Props: agent对象（id/name/description/avatar/status/skillCount/skills数组）
- 顶部渐变色条（使用主题渐变色）
- Agent图标（avatar或首字母占位）+ 名称 + 描述
- 技能数量徽章
- hover效果：上浮4px + border发光
- 点击导航到 /agents/{id}
- CSS变量适配3套主题

---

### Task 3: Agent大厅页（AgentHallView.vue）

**Files:**
- Replace: `agent-platform/frontend/src/views/AgentHallView.vue`

- [ ] **Step 1: 替换占位视图 — Agent大厅页**

功能要点：
- 顶部：标题"全部 Agent" + 数量统计 + 分组筛选下拉 + 搜索框
- 主区域：4列响应式网格（grid布局），每列一个AgentCard
- 搜索：输入关键词过滤Agent名称/描述
- 分组筛选：下拉选择agentGroupId过滤
- 数据加载：onMounted调用listAgents和getGroups，loading状态用NSpin
- 空状态：无结果时用NEmpty显示提示
- 移动端：2列或1列（响应式）

---

### Task 4: SkillList + SkillDetail + WorkflowTimeline组件

**Files:**
- Create: `agent-platform/frontend/src/components/SkillList.vue`
- Create: `agent-platform/frontend/src/components/SkillDetail.vue`
- Create: `agent-platform/frontend/src/components/WorkflowTimeline.vue`

- [ ] **Step 1: 创建 WorkflowTimeline.vue — 工作流步骤时间线**

Props: steps数组（stepOrder, name, action, config）
垂直时间线：左侧序号圆点 + 连接线 + 右侧步骤内容
序号使用主题accent色，连接线1px虚线

- [ ] **Step 2: 创建 SkillList.vue — 技能列表组件**

Props: skills数组、selectedSkillId
渲染技能列表（名称+简短描述）
选中项高亮（左侧色条 + 背景色）
点击emit('select', skillId)

- [ ] **Step 3: 创建 SkillDetail.vue — 技能详情组件**

Props: skill对象（含steps数组）
技能名称+目的描述
内嵌WorkflowTimeline步骤时间线

---

### Task 5: Agent详情页（AgentDetailView.vue）

**Files:**
- Replace: `agent-platform/frontend/src/views/AgentDetailView.vue`

- [ ] **Step 1: 替换占位视图 — Agent详情页**

功能要点：
- 顶部面包屑：Agent大厅 > Agent名称
- Agent头部区域：渐变色条 + 图标 + 名称 + 描述 + 统计徽章（技能数）
- 左右分栏布局（左侧SkillList 300px + 右侧SkillDetail 自适应）
- onMounted：加载Agent详情（含skills），默认选中第一个skill
- 选择skill时加载skill详情（含steps）
- 加载状态：NSpin

---

### Task 6: 路由确认 + Git提交

- [ ] **Step 1: 确认路由配置正确**

router/index.ts已有 /agents 和 /agents/:id 路由指向正确的视图文件，无需修改。

- [ ] **Step 2: Git提交**

```bash
git add agent-platform/frontend/src/api/agent.ts
git add agent-platform/frontend/src/components/AgentCard.vue
git add agent-platform/frontend/src/components/SkillList.vue
git add agent-platform/frontend/src/components/SkillDetail.vue
git add agent-platform/frontend/src/components/WorkflowTimeline.vue
git add agent-platform/frontend/src/views/AgentHallView.vue
git add agent-platform/frontend/src/views/AgentDetailView.vue
git commit -m "feat: 实现Agent大厅页和Agent详情页"
```

---

## 验证清单

| 检查项 | 预期结果 |
|--------|----------|
| Agent大厅页加载 | 显示Agent卡片网格，分组筛选和搜索功能可用 |
| 分组筛选 | 下拉选择分组后，只显示对应分组的Agent |
| 搜索过滤 | 输入关键词后，按名称/描述过滤Agent |
| Agent卡片hover | 上浮4px + border发光效果 |
| 点击Agent卡片 | 导航到 /agents/{id} 详情页 |
| Agent详情页加载 | 显示Agent信息 + 技能列表 + 默认选中第一个技能 |
| 技能列表选中 | 点击技能项高亮，右侧显示技能详情 |
| 工作流时间线 | 显示技能步骤的垂直时间线 |
| 空状态 | 无Agent/无技能时显示空状态提示 |
| 响应式 | 移动端卡片网格自动变为2列或1列 |
| 主题适配 | 切换3套主题后所有页面色值正确 |

---

## 与后续Plan的衔接

| 后续Plan | 本Plan提供的接口/组件 |
|----------|----------------------|
| Plan 5: 工作流编辑器 | `SkillDetail`组件中的"在工作流编辑器中打开"按钮、`api/agent.ts` API封装 |
| Plan 6: 执行监控 | Agent详情页的统计徽章可扩展执行统计 |
