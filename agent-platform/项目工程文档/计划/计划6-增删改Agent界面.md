# 计划6 — Agent增删改界面

## 目标

实现Agent的完整CRUD操作，包括：创建、编辑、删除、发布/下线，以及分组管理。当前只能通过Markdown同步导入Agent，需要在界面上提供直接操作能力。

---

## 现状分析

### 已有
- 后端：Agent查询（列表+详情）、Markdown同步
- 前端：Agent大厅（卡片网格+筛选搜索）、Agent详情页（只读）

### 缺失
- 后端：Agent的创建/更新/删除/发布API、分组CRUD API
- 前端：Agent创建/编辑弹窗、删除确认、发布/下线按钮

---

## 实现任务

### Task 1：后端 — Agent CRUD API

**文件**：`AgentController.java` + `AgentService.java`

新增端点：

| 方法 | 端点 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/agents` | 创建Agent | agent:create |
| PUT | `/api/agents/{id}` | 更新Agent | agent:update |
| DELETE | `/api/agents/{id}` | 删除Agent(逻辑删除) | agent:delete |
| PUT | `/api/agents/{id}/status` | 更新状态(发布/下线) | agent:publish |

请求体：
```json
{
  "name": "Agent名称",
  "description": "描述",
  "avatar": "头像URL",
  "groupId": 1,
  "status": "DRAFT"
}
```

### Task 2：前端 — Agent API层

**文件**：`src/api/agent.ts`

新增方法：
- `createAgent(data)` → POST /api/agents
- `updateAgent(id, data)` → PUT /api/agents/{id}
- `deleteAgent(id)` → DELETE /api/agents/{id}
- `updateAgentStatus(id, status)` → PUT /api/agents/{id}/status

### Task 3：前端 — Agent创建/编辑弹窗组件

**文件**：`src/components/AgentFormModal.vue`

功能：
- 表单字段：名称(必填)、描述、分组(下拉)、头像URL、状态(只读)
- 创建模式：空表单，提交POST
- 编辑模式：预填数据，提交PUT
- Naive UI组件：NForm + NFormItem + NInput + NSelect + NModal

### Task 4：前端 — AgentHallView集成

**文件**：`src/views/AgentHallView.vue`

改动：
- 顶部筛选区添加「新建Agent」按钮（需agent:create权限）
- AgentCard添加操作按钮（编辑/删除/发布），悬浮或右键触发

### Task 5：前端 — Agent详情页编辑

**文件**：`src/views/AgentDetailView.vue`

改动：
- 详情页顶部添加「编辑」和「删除」按钮
- 点击编辑打开AgentFormModal（编辑模式）
- 权限控制：根据authStore判断按钮显示

---

## 验收标准

- [ ] 管理员可在Agent大厅点击"新建"创建Agent
- [ ] 点击Agent卡片上的编辑按钮，弹出编辑表单，修改后保存
- [ ] 点击删除按钮，确认后逻辑删除，卡片消失
- [ ] 点击发布按钮，Agent状态变为PUBLISHED
- [ ] 普通用户只能查看，看不到创建/编辑/删除按钮
