# Agent权限功能开发计划

> 创建时间：2026-06-12  
> 适用项目：agent-platform  
> 目标：支持 Agent 持有者和管理员向其他用户分发单个 Agent 的使用、提示词可读、复制编辑权限，并保证非持有者不能直接修改原 Agent。

---

## 一、背景与目标

当前系统的 `agent:read`、`agent:create`、`agent:update` 等权限属于角色级系统权限，只能表达“某类用户是否具备 Agent 模块能力”，不能表达“某个用户是否可以使用某一个 Agent”。

本计划新增 Agent 对象级授权能力：

1. Agent 持有者、管理员可以给其他用户分发指定 Agent 的权限。
2. 被授权用户可在 Agent 大厅、聊天、工作流中使用被授权 Agent。
3. “可读权限”允许用户查看 Agent/Skill 的提示词等敏感配置。
4. “可复制权限”允许用户基于原 Agent 编辑并保存为自己的新 Agent，不会修改原 Agent 数据。

---

## 二、权限定义

### 2.1 三类对象级权限

| 权限 | 字段建议 | 含义 |
|------|----------|------|
| 使用权限 | `can_use` | 可在 Agent 大厅查看基础信息，可在聊天、工作流、运行时调用该 Agent |
| 可读权限 | `can_read_prompt` | 可查看 Agent 配置、Skill Step 配置里的提示词、模型参数等敏感字段 |
| 可复制权限 | `can_copy` | 可进入编辑态，但保存时创建为当前用户的新 Agent，不修改原 Agent |

### 2.2 默认权限规则

1. Agent 创建者默认拥有使用、可读、可复制、管理授权的全部权限。
2. 管理员默认拥有全部 Agent 的使用、可读、可复制、管理授权权限。
3. `can_read_prompt = true` 应隐含 `can_use = true`。
4. `can_copy = true` 应隐含 `can_use = true`。
5. 被授权用户即使拥有 `can_copy`，也不能通过更新接口修改原 Agent。

---

## 三、数据设计

### 3.1 新增表：`agent_permissions`

建议新增 Flyway 迁移：

```text
backend/src/main/resources/db/migration/V16__create_agent_permissions.sql
```

表结构建议：

```sql
CREATE TABLE agent_permissions (
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    can_use BOOLEAN NOT NULL DEFAULT TRUE,
    can_read_prompt BOOLEAN NOT NULL DEFAULT FALSE,
    can_copy BOOLEAN NOT NULL DEFAULT FALSE,
    granted_by BIGINT REFERENCES users(id),
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted INTEGER NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_agent_permissions_agent_user UNIQUE (agent_id, user_id)
);

CREATE INDEX idx_agent_permissions_user ON agent_permissions(user_id) WHERE deleted = 0;
CREATE INDEX idx_agent_permissions_agent ON agent_permissions(agent_id) WHERE deleted = 0;
```

测试库同步更新：

```text
backend/src/test/resources/schema-h2.sql
```

---

## 四、后端开发计划

### Task 1：新增 AgentPermission 实体、Mapper、DTO

涉及文件：

- 新增：`backend/src/main/java/com/superprogrammer/agent/entity/AgentPermission.java`
- 新增：`backend/src/main/java/com/superprogrammer/agent/mapper/AgentPermissionMapper.java`
- 新增：`backend/src/main/java/com/superprogrammer/agent/dto/AgentPermissionVO.java`
- 新增：`backend/src/main/java/com/superprogrammer/agent/dto/AgentPermissionSaveRequest.java`

验收点：

- 能按 `agent_id + user_id` 查询对象级授权。
- VO 返回用户名、用户ID、三类权限、授权人、更新时间。

建议测试：

```bash
cd agent-platform/backend
mvn -q "-Dtest=AgentPermissionServiceTest" test
```

---

### Task 2：新增 AgentPermissionService

新增服务：

```text
backend/src/main/java/com/superprogrammer/agent/service/AgentPermissionService.java
```

核心方法：

```java
boolean canManage(Long agentId, Long userId, boolean admin);
boolean canUse(Long agentId, Long userId, boolean admin);
boolean canReadPrompt(Long agentId, Long userId, boolean admin);
boolean canCopy(Long agentId, Long userId, boolean admin);
AgentAccess resolveAccess(Long agentId, Long userId, boolean admin);
List<AgentPermissionVO> listPermissions(Long agentId, Long operatorId, boolean admin);
void savePermissions(Long agentId, List<AgentPermissionSaveRequest> requests, Long operatorId, boolean admin);
```

规则：

- `canManage` 只允许管理员或 Agent 创建者。
- `canReadPrompt` 为真时，`canUse` 等价为真。
- `canCopy` 为真时，`canUse` 等价为真。
- 授权保存接口必须拒绝非管理员、非 Agent 创建者。

建议测试场景：

- 创建者拥有所有权限。
- 管理员拥有所有权限。
- 未授权普通用户没有对象级权限。
- `can_read_prompt` 隐含使用权限。
- `can_copy` 隐含使用权限。
- 非创建者不能分发权限。

---

### Task 3：新增授权管理接口

修改：

```text
backend/src/main/java/com/superprogrammer/agent/controller/AgentController.java
```

新增接口：

```http
GET /api/agents/{id}/permissions
PUT /api/agents/{id}/permissions
GET /api/agents/{id}/access
```

说明：

- `GET /permissions`：返回当前 Agent 的授权列表，仅管理员或持有者可调用。
- `PUT /permissions`：批量保存授权，仅管理员或持有者可调用。
- `GET /access`：返回当前用户对该 Agent 的访问能力，前端用于控制 UI。

`AgentAccess` 建议字段：

```json
{
  "agentId": 1,
  "canManage": true,
  "canUse": true,
  "canReadPrompt": true,
  "canCopy": true
}
```

---

### Task 4：改造 Agent 列表、详情和聊天目标过滤

涉及文件：

- `backend/src/main/java/com/superprogrammer/agent/service/AgentService.java`
- `backend/src/main/java/com/superprogrammer/chat/service/ChatTargetService.java`
- `backend/src/main/java/com/superprogrammer/workflow/service/WorkflowService.java`

改造规则：

1. Agent 大厅只返回：
   - 当前用户创建的 Agent；
   - 当前用户被授予 `can_use` 的 Agent；
   - 管理员可见的 Agent。
2. 聊天目标只返回可使用 Agent。
3. 工作流组件面板只返回可使用 Agent/Skill。
4. Agent 详情无 `can_use` 时返回 403。
5. 无 `can_read_prompt` 时隐藏敏感提示词字段。

敏感字段建议包括：

- Agent `config.systemPrompt`
- Skill `config` 中的 prompt 字段
- SkillStep `config.systemPrompt`
- SkillStep `config.promptTemplate`
- SkillStep `config.model`
- SkillStep `config.temperature`

---

### Task 5：实现复制保存语义

新增接口：

```http
POST /api/agents/{id}/copy
```

请求体建议：

```json
{
  "name": "我的副本 Agent",
  "description": "基于原 Agent 调整",
  "avatar": null,
  "groupId": 1,
  "config": "{}",
  "skills": []
}
```

后端规则：

1. 管理员、创建者、有 `can_copy` 的用户可以复制。
2. 复制后新 Agent 的 `created_by` 是当前用户。
3. 原 Agent、原 Skill、原 SkillStep 不被修改。
4. 若请求体带了编辑后的 Agent/Skill/Step 内容，则创建为新 Agent 的内容。
5. 若请求体为空，则完整复制原 Agent、Skills、SkillSteps。

建议新增服务方法：

```java
AgentDetailVO copyAgent(Long sourceAgentId, AgentCopyRequest request, Long userId, boolean admin);
```

---

## 五、前端开发计划

### Task 6：扩展 Agent API 类型

涉及文件：

- `frontend/src/api/agent.ts`

新增类型：

```ts
export interface AgentAccess {
  agentId: number
  canManage: boolean
  canUse: boolean
  canReadPrompt: boolean
  canCopy: boolean
}

export interface AgentPermission {
  userId: number
  username: string
  canUse: boolean
  canReadPrompt: boolean
  canCopy: boolean
  grantedBy?: number
  updatedAt?: string
}
```

新增 API：

```ts
getAgentAccess(id: number)
listAgentPermissions(id: number)
saveAgentPermissions(id: number, permissions: AgentPermissionSaveRequest[])
copyAgent(id: number, data: AgentCopyRequest)
```

---

### Task 7：Agent 详情页增加权限分发入口

涉及文件：

- `frontend/src/views/AgentDetailView.vue`
- 新增：`frontend/src/components/AgentPermissionModal.vue`

UI 行为：

1. 只有 `canManage = true` 显示“权限分发”按钮。
2. 弹窗中选择用户并勾选三类权限。
3. 勾选“可读提示词”时自动勾选“使用权限”。
4. 勾选“可复制”时自动勾选“使用权限”。
5. 保存后刷新授权列表。

建议控件：

- 用户选择：`n-select` 或远程搜索用户。
- 权限项：`n-checkbox-group` 或三列 `n-switch`。
- 保存：`n-button type="primary"`。

---

### Task 8：Agent 详情页按访问能力控制编辑

前端模式：

| 权限 | 页面表现 |
|------|----------|
| 无使用权限 | 详情接口应 403，前端提示无权限 |
| 使用权限 | 可查看基础信息，可使用，不展示提示词，不可编辑 |
| 可读权限 | 可查看提示词，不可修改 |
| 可复制权限 | 可编辑副本，保存按钮显示“保存为我的 Agent” |
| 创建者/管理员 | 可编辑原 Agent，保存按钮显示“保存” |

保存逻辑：

- `canManage = true`：调用原 `updateAgent` / `updateSkill`。
- `canManage = false && canCopy = true`：调用 `copyAgent`。
- 其他情况：隐藏保存按钮或禁用表单。

---

### Task 9：工作流与聊天入口接入对象级权限

涉及文件：

- `frontend/src/components/workflow/ComponentPalette.vue`
- `frontend/src/components/workflow/PropertyPanel.vue`
- `frontend/src/stores/chat.ts`
- `frontend/src/components/chat/TargetSelector.vue`

要求：

1. 工作流组件面板只展示后端返回的可用 Agent/Skill。
2. Skill 节点提示词配置是否可见，按后端返回的 `promptConfigVisible` 控制。
3. Chat 目标选择器只展示可用 Agent。
4. 若后端返回 403，前端给出明确提示。

---

## 六、测试计划

### 6.1 后端单元测试

建议新增或扩展：

```text
backend/src/test/java/com/superprogrammer/agent/service/AgentPermissionServiceTest.java
backend/src/test/java/com/superprogrammer/agent/controller/AgentPermissionControllerTest.java
backend/src/test/java/com/superprogrammer/agent/service/AgentServiceTest.java
backend/src/test/java/com/superprogrammer/chat/service/ChatTargetServiceTest.java
backend/src/test/java/com/superprogrammer/workflow/service/WorkflowServiceTest.java
```

关键测试：

- 创建者可分发权限。
- 管理员可分发权限。
- 普通用户不可分发权限。
- 有使用权限的用户能在列表看到 Agent。
- 无使用权限的用户看不到 Agent。
- 无可读权限时提示词脱敏。
- 有可读权限时提示词可见。
- 有可复制权限时复制保存为新 Agent。
- 有可复制权限的用户不能修改原 Agent。

运行命令：

```bash
cd agent-platform/backend
mvn -q "-Dtest=AgentPermissionServiceTest,AgentControllerTest,AgentServiceTest,ChatTargetServiceTest,WorkflowServiceTest" test
```

### 6.2 前端单元测试

建议新增或扩展：

```text
frontend/src/api/agent.test.ts
frontend/src/views/AgentDetailView.test.ts
frontend/src/components/AgentPermissionModal.test.ts
frontend/src/components/workflow/PropertyPanel.test.ts
frontend/src/components/chat/TargetSelector.test.ts
```

关键测试：

- `canManage` 时展示权限分发按钮。
- 非 `canManage` 时隐藏权限分发按钮。
- `canReadPrompt` 时展示提示词。
- 无 `canReadPrompt` 时隐藏提示词。
- `canCopy` 且非持有者时保存调用复制接口。
- 仅 `canUse` 时保存按钮不可用。

运行命令：

```bash
cd agent-platform/frontend
npm test -- src/api/agent.test.ts src/views/AgentDetailView.test.ts src/components/AgentPermissionModal.test.ts
```

### 6.3 构建验证

```bash
cd agent-platform/backend
mvn -q test

cd ../frontend
npm test -- --run
npm run build
```

---

## 七、手工验收场景

### 场景 1：创建者授权使用权限

1. 使用 admin 或 Agent 创建者登录。
2. 打开某个 Agent 详情。
3. 给普通用户 A 授予“使用权限”。
4. 用户 A 登录。
5. 用户 A 可以在 Agent 大厅、聊天目标、工作流组件面板看到该 Agent。
6. 用户 A 看不到提示词，不能编辑保存。

### 场景 2：授权可读权限

1. 创建者给用户 A 授予“可读权限”。
2. 用户 A 打开 Agent 详情。
3. 用户 A 可以看到提示词、模型参数、Skill Step 配置。
4. 用户 A 不能保存修改原 Agent。

### 场景 3：授权可复制权限

1. 创建者给用户 A 授予“可复制权限”。
2. 用户 A 打开 Agent 详情并修改配置。
3. 用户 A 点击“保存为我的 Agent”。
4. 系统创建一个新的 Agent，`created_by = 用户 A`。
5. 原 Agent 的数据库记录不变。

### 场景 4：未授权用户访问

1. 用户 B 未获得任何对象级授权。
2. 用户 B 不应在 Agent 大厅、聊天目标、工作流组件面板看到该 Agent。
3. 用户 B 直接访问详情接口应返回 403。

---

## 八、实施顺序建议

1. 先做数据库迁移和 `AgentPermissionService`，建立统一权限判断入口。
2. 再改 Agent 查询过滤和详情脱敏，确保数据安全先成立。
3. 然后实现复制保存语义，防止非持有者误改原 Agent。
4. 最后做前端权限分发 UI、详情页编辑模式和工作流/聊天入口体验优化。

---

## 九、风险与注意事项

1. 不要只做前端禁用，所有权限必须在后端校验。
2. `can_copy` 的保存语义必须明确：创建新 Agent，不更新原 Agent。
3. 提示词脱敏要覆盖 Agent config、Skill config、SkillStep config、工作流 Skill 节点配置。
4. 工作流运行时也必须校验 `can_use`，不能只依赖组件面板过滤。
5. 管理员角色判断要沿用现有后端 `ROLE_admin` / `ROLE_ADMIN` 兼容逻辑。
6. 若 Agent 有子 Agent 层级，复制时需要明确是否复制子 Agent；建议第一版只复制当前 Agent 及其 Skills，子 Agent 复制作为后续增强。

