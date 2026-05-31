# 需求追溯矩阵 (RTM) — 多Agent智能体平台

> 版本: 1.0 | 日期: 2026-05-25 | 状态: 初稿

---

## 一、需求追溯说明

### 1.1 编号规则

| 编号类型 | 前缀 | 格式 | 示例 |
|---------|------|------|------|
| 用户故事 | US | US-{模块}-{序号} | US-Auth-01 |
| 功能需求 | FR | FR-{模块}-{序号} | FR-AUTH-01 |
| 非功能需求 | NFR | NFR-{类别}-{序号} | NFR-PERF-01 |
| 测试用例 | TC | TC-{模块}-{序号} | TC-AUTH-001 |
| 数据库表 | DB | DB-{序号} | DB-01 |
| API接口 | API | API-{模块}-{序号} | API-AUTH-001 |

### 1.2 模块缩写

| 缩写 | 模块 |
|------|------|
| AUTH | 登录认证 |
| HALL | Agent大厅 |
| DETAIL | Agent详情 |
| FLOW | 工作流编排 |
| RBAC | 权限管理 |
| THEME | 主题系统 |

---

## 二、需求ID到功能模块映射表

### 2.1 功能需求映射

| 需求ID | 需求名称 | 所属模块 | 前端组件 | 后端服务 | 数据库表 | API接口 | 优先级 | MVP |
|--------|---------|---------|---------|---------|---------|---------|--------|-----|
| FR-AUTH-01 | 用户名密码登录 | AUTH | LoginView | AuthService | DB-01 users | API-AUTH-001 | P0 | Y |
| FR-AUTH-02 | JWT Token管理 | AUTH | Token拦截器 | JwtService | DB-01 users, DB-11 refresh_tokens | API-AUTH-002 | P0 | Y |
| FR-AUTH-03 | 账户安全策略 | AUTH | LoginForm | AuthService, LockService | DB-01 users, DB-12 login_attempts | API-AUTH-003 | P0 | Y |
| FR-AUTH-04 | 密码修改 | AUTH | PasswordModal | UserService | DB-01 users | API-AUTH-004 | P1 | Y |
| FR-AUTH-05 | 会话管理 | AUTH | 全局Store | SessionService | DB-11 refresh_tokens | API-AUTH-005 | P0 | Y |
| FR-HALL-01 | Agent卡片展示 | HALL | AgentHallView, AgentCard | AgentService | DB-03 agents, DB-04 agent_tags | API-HALL-001 | P0 | Y |
| FR-HALL-02 | Agent分类体系 | HALL | CategoryFilter | AgentService | DB-04 agent_tags, DB-13 tag_categories | API-HALL-002 | P0 | Y |
| FR-HALL-03 | 全文搜索 | HALL | SearchBar | AgentSearchService | DB-03 agents (全文索引) | API-HALL-003 | P0 | Y |
| FR-HALL-04 | 排序功能 | HALL | SortDropdown | AgentService | DB-03 agents | API-HALL-004 | P2 | N |
| FR-HALL-05 | 收藏功能 | HALL | FavoriteBtn | FavoriteService | DB-06 user_favorites | API-HALL-005 | P2 | N |
| FR-DETAIL-01 | Agent基本信息 | DETAIL | AgentDetailView | AgentService | DB-03 agents, DB-10 agent_details | API-DETAIL-001 | P0 | Y |
| FR-DETAIL-02 | 接口规范展示 | DETAIL | InterfaceTable | AgentService | DB-10 agent_details (io_schema字段) | API-DETAIL-002 | P0 | Y |
| FR-DETAIL-03 | 关联工作流展示 | DETAIL | RelatedWorkflows | WorkflowService | DB-07 workflows, DB-05 workflow_agents | API-DETAIL-003 | P1 | N |
| FR-DETAIL-04 | 快速创建工作流 | DETAIL | CreateFlowBtn | WorkflowService | DB-07 workflows | API-DETAIL-004 | P1 | Y |
| FR-FLOW-01 | 可视化画布 | FLOW | WorkflowEditor, FlowCanvas | — | DB-07 workflows (layout字段) | — | P0 | Y |
| FR-FLOW-02 | Agent节点拖拽 | FLOW | AgentPanel, DraggableNode | — | — | — | P0 | Y |
| FR-FLOW-03 | 节点连线 | FLOW | FlowEdge, TypeChecker | WorkflowValidateService | — | API-FLOW-006 | P0 | Y |
| FR-FLOW-04 | 节点参数配置 | FLOW | NodeConfigPanel | WorkflowService | DB-07 workflows (config字段) | API-FLOW-002 | P0 | Y |
| FR-FLOW-05 | 工作流CRUD | FLOW | WorkflowList, WorkflowEditor | WorkflowService | DB-07 workflows, DB-05 workflow_agents | API-FLOW-001~005 | P0 | Y |
| FR-FLOW-06 | 工作流执行引擎 | FLOW | ExecutionPanel | ExecutionEngine, AgentExecutor | DB-08 executions, DB-09 execution_logs | API-FLOW-007~009 | P0 | Y |
| FR-FLOW-07 | 执行历史管理 | FLOW | ExecutionHistory | ExecutionService | DB-08 executions, DB-09 execution_logs | API-FLOW-010 | P0 | Y |
| FR-FLOW-08 | 检查清单节点 | FLOW | ChecklistNode | ChecklistService | DB-02 checklists, DB-05 workflow_agents | API-FLOW-011 | P1 | N |
| FR-FLOW-09 | 工作流模板 | FLOW | TemplateManager | TemplateService | DB-07 workflows (is_template字段) | API-FLOW-012~013 | P1 | N |
| FR-RBAC-01 | 角色定义 | RBAC | RoleSelector | RoleService | DB-02 roles | API-RBAC-001 | P0 | Y |
| FR-RBAC-02 | 权限矩阵 | RBAC | PermissionMatrix | PermissionService | DB-02 roles, DB-12 permissions, DB-12 role_permissions | API-RBAC-002 | P0 | Y |
| FR-RBAC-03 | 用户管理 | RBAC | UserManagement | UserService | DB-01 users, DB-02 user_roles | API-RBAC-003~005 | P0 | Y |
| FR-THEME-01 | 三套暗色主题 | THEME | ThemeSwitcher | UserPreferenceService | DB-01 users (theme_preference字段) | API-THEME-001 | P1 | Y |
| FR-THEME-02 | 主题切换 | THEME | ThemeSwitcher | UserPreferenceService | — | API-THEME-001 | P1 | Y |
| FR-THEME-03 | CSS变量体系 | THEME | theme.css, variables.css | — | — | — | P1 | Y |

---

## 三、需求ID到测试用例映射

### 3.1 登录认证模块

| 需求ID | 测试用例ID | 测试场景 | 测试类型 | 前置条件 | 预期结果 |
|--------|-----------|---------|---------|---------|---------|
| FR-AUTH-01 | TC-AUTH-001 | 正确用户名密码登录成功 | 功能测试 | 已注册用户 | 返回JWT Token，跳转Agent大厅 |
| FR-AUTH-01 | TC-AUTH-002 | 错误密码登录失败 | 功能测试 | 已注册用户 | 提示"用户名或密码错误" |
| FR-AUTH-01 | TC-AUTH-003 | 不存在的用户名登录 | 功能测试 | 无 | 提示"用户名或密码错误"（不暴露用户是否存在） |
| FR-AUTH-01 | TC-AUTH-004 | 空用户名或空密码提交 | 表单验证 | 无 | 前端校验拦截，提示必填 |
| FR-AUTH-01 | TC-AUTH-005 | 密码包含特殊字符的正确登录 | 功能测试 | 密码含特殊字符的用户 | 登录成功 |
| FR-AUTH-02 | TC-AUTH-006 | Token过期后自动刷新 | 功能测试 | 已登录，Access Token过期 | 自动使用Refresh Token获取新Access Token |
| FR-AUTH-02 | TC-AUTH-007 | Refresh Token过期后跳转登录 | 功能测试 | Refresh Token过期 | 清除本地存储，跳转登录页 |
| FR-AUTH-03 | TC-AUTH-008 | 连续5次失败后账户锁定 | 安全测试 | 已注册用户 | 第6次尝试提示"账户已锁定，请15分钟后重试" |
| FR-AUTH-03 | TC-AUTH-009 | 锁定15分钟后自动解锁 | 功能测试 | 账户处于锁定状态 | 15分钟后可正常登录 |
| FR-AUTH-03 | TC-AUTH-010 | 密码复杂度校验 | 安全测试 | 无 | 不满足复杂度要求时拒绝并给出提示 |
| FR-AUTH-04 | TC-AUTH-011 | 修改密码成功 | 功能测试 | 已登录用户 | 密码修改成功，需重新登录 |
| FR-AUTH-04 | TC-AUTH-012 | 旧密码错误时修改失败 | 功能测试 | 已登录用户 | 提示"旧密码不正确" |
| FR-AUTH-05 | TC-AUTH-013 | 退出登录清除Token | 功能测试 | 已登录用户 | Token清除，无法通过回退进入 |
| FR-AUTH-05 | TC-AUTH-014 | 多设备登录控制 | 功能测试 | 同一账号两个浏览器 | 按策略处理（允许/踢出前者） |

### 3.2 Agent大厅模块

| 需求ID | 测试用例ID | 测试场景 | 测试类型 | 前置条件 | 预期结果 |
|--------|-----------|---------|---------|---------|---------|
| FR-HALL-01 | TC-HALL-001 | 加载Agent列表（15个Agent） | 功能测试 | 数据库有15条Agent数据 | 展示15张Agent卡片 |
| FR-HALL-01 | TC-HALL-002 | Agent卡片内容完整性 | UI测试 | Agent数据完整 | 每张卡片含名称、图标、描述、标签 |
| FR-HALL-01 | TC-HALL-003 | 响应式布局切换 | UI测试 | 不同屏幕宽度 | 1280px:4列, 1024px:3列, 768px:2列 |
| FR-HALL-02 | TC-HALL-004 | 按分类筛选Agent | 功能测试 | Agent已标记分类 | 筛选后只显示对应分类的Agent |
| FR-HALL-02 | TC-HALL-005 | 多标签组合筛选 | 功能测试 | Agent有多个标签 | 结果为多标签的交集 |
| FR-HALL-02 | TC-HALL-006 | 清除筛选恢复全部 | 功能测试 | 当前有筛选条件 | 清除后恢复展示所有Agent |
| FR-HALL-03 | TC-HALL-007 | 按名称搜索Agent | 功能测试 | 无 | 返回名称匹配的Agent |
| FR-HALL-03 | TC-HALL-008 | 按描述搜索Agent | 功能测试 | 无 | 返回描述中包含关键词的Agent |
| FR-HALL-03 | TC-HALL-009 | 搜索无结果 | 功能测试 | 输入不存在的关键词 | 显示"未找到匹配的Agent" |
| FR-HALL-03 | TC-HALL-010 | 搜索防抖验证 | 性能测试 | 快速输入多个字符 | 仅在停止输入300ms后发起搜索 |

### 3.3 Agent详情模块

| 需求ID | 测试用例ID | 测试场景 | 测试类型 | 前置条件 | 预期结果 |
|--------|-----------|---------|---------|---------|---------|
| FR-DETAIL-01 | TC-DETAIL-001 | 查看Agent基本信息 | 功能测试 | 从大厅点击Agent卡片 | 正确展示名称、描述、分类、场景 |
| FR-DETAIL-02 | TC-DETAIL-002 | 查看输入参数列表 | 功能测试 | Agent有输入参数定义 | 表格展示参数名、类型、必填、默认值、说明 |
| FR-DETAIL-02 | TC-DETAIL-003 | 查看输出参数列表 | 功能测试 | Agent有输出参数定义 | 表格展示参数名、类型、说明 |
| FR-DETAIL-04 | TC-DETAIL-004 | 从详情页创建工作流 | 功能测试 | 已登录用户 | 打开编辑器并自动添加该Agent节点 |

### 3.4 工作流编排模块

| 需求ID | 测试用例ID | 测试场景 | 测试类型 | 前置条件 | 预期结果 |
|--------|-----------|---------|---------|---------|---------|
| FR-FLOW-01 | TC-FLOW-001 | 创建空白工作流 | 功能测试 | 已登录用户 | 画布加载，显示空白编辑区 |
| FR-FLOW-01 | TC-FLOW-002 | 画布缩放操作 | UI测试 | 已打开工作流编辑器 | 支持10%-200%缩放，滚轮+按钮均可 |
| FR-FLOW-01 | TC-FLOW-003 | 画布平移操作 | UI测试 | 已打开工作流编辑器 | 按住空格+拖拽或鼠标中键可平移 |
| FR-FLOW-02 | TC-FLOW-004 | 拖拽Agent到画布 | 功能测试 | Agent面板已加载 | 拖拽后画布出现新节点，含Agent图标和名称 |
| FR-FLOW-02 | TC-FLOW-005 | 拖拽同一Agent多次 | 功能测试 | 已拖入一个Agent | 可拖入同一Agent的多个实例 |
| FR-FLOW-03 | TC-FLOW-006 | 创建有效连线 | 功能测试 | 画布上有两个Agent节点 | 连线成功，显示数据流箭头 |
| FR-FLOW-03 | TC-FLOW-007 | 类型不兼容连线提示 | 功能测试 | 输出类型与输入类型不匹配 | 连线失败，提示类型不兼容 |
| FR-FLOW-03 | TC-FLOW-008 | 删除连线 | 功能测试 | 已有连线 | 选中连线后按Delete可删除 |
| FR-FLOW-04 | TC-FLOW-009 | 打开参数配置面板 | 功能测试 | 画布上有Agent节点 | 点击节点弹出右侧参数面板 |
| FR-FLOW-04 | TC-FLOW-010 | 填写必填参数 | 功能测试 | 节点有必填参数 | 填写后节点警告消失 |
| FR-FLOW-04 | TC-FLOW-011 | 必填参数未填写警告 | 功能测试 | 必填参数为空 | 节点显示橙色警告标记 |
| FR-FLOW-05 | TC-FLOW-012 | 保存工作流 | 功能测试 | 已编排工作流 | 保存成功，提示"已保存" |
| FR-FLOW-05 | TC-FLOW-013 | 自动保存验证 | 功能测试 | 编辑工作流后等待5秒 | 无操作5秒后自动保存，不弹提示 |
| FR-FLOW-05 | TC-FLOW-014 | 删除工作流确认 | 功能测试 | 有已保存的工作流 | 弹出确认对话框，确认后删除 |
| FR-FLOW-06 | TC-FLOW-015 | 执行单节点工作流 | 功能测试 | 画布有1个Agent节点 | 执行成功，显示结果 |
| FR-FLOW-06 | TC-FLOW-016 | 执行多节点线性工作流 | 功能测试 | 3个节点A→B→C依次连接 | 按顺序执行A→B→C |
| FR-FLOW-06 | TC-FLOW-017 | 执行中实时状态展示 | 功能测试 | 工作流正在执行 | 当前节点显示"执行中"动画，已完成节点显示绿色 |
| FR-FLOW-06 | TC-FLOW-018 | 执行失败定位 | 功能测试 | 第2个节点执行失败 | 第2个节点显示红色错误标记，后续节点不执行 |
| FR-FLOW-06 | TC-FLOW-019 | 取消执行中的工作流 | 功能测试 | 工作流正在执行 | 点击取消，当前节点完成后停止，显示"已取消" |
| FR-FLOW-07 | TC-FLOW-020 | 查看执行历史列表 | 功能测试 | 有历史执行记录 | 列表展示时间、状态、耗时 |
| FR-FLOW-07 | TC-FLOW-021 | 按状态筛选历史 | 功能测试 | 有成功和失败的记录 | 筛选后只显示对应状态的记录 |

### 3.5 权限管理模块

| 需求ID | 测试用例ID | 测试场景 | 测试类型 | 前置条件 | 预期结果 |
|--------|-----------|---------|---------|---------|---------|
| FR-RBAC-01 | TC-RBAC-001 | 开发者角色权限验证 | 权限测试 | 以developer角色登录 | 可查看Agent、创建工作流；不可管理用户 |
| FR-RBAC-01 | TC-RBAC-002 | 团队负责人角色权限验证 | 权限测试 | 以team_lead角色登录 | 可创建工作流模板、查看团队统计 |
| FR-RBAC-01 | TC-RBAC-003 | 管理员角色权限验证 | 权限测试 | 以admin角色登录 | 可管理用户、角色、系统配置 |
| FR-RBAC-01 | TC-RBAC-004 | 超级管理员权限验证 | 权限测试 | 以super_admin角色登录 | 拥有所有权限 |
| FR-RBAC-02 | TC-RBAC-005 | 越权操作拦截 | 安全测试 | developer角色尝试访问用户管理API | 返回403 Forbidden |
| FR-RBAC-02 | TC-RBAC-006 | Token伪造检测 | 安全测试 | 修改Token中的role字段 | 签名校验失败，返回401 |
| FR-RBAC-03 | TC-RBAC-007 | 创建新用户并分配角色 | 功能测试 | 管理员登录 | 创建成功，新用户可登录并拥有对应权限 |
| FR-RBAC-03 | TC-RBAC-008 | 禁用用户账号 | 功能测试 | 管理员禁用某用户 | 该用户立即无法登录，Token失效 |

### 3.6 主题系统模块

| 需求ID | 测试用例ID | 测试场景 | 测试类型 | 前置条件 | 预期结果 |
|--------|-----------|---------|---------|---------|---------|
| FR-THEME-01 | TC-THEME-001 | 切换到Deep Space主题 | UI测试 | 已登录 | 界面变为深蓝黑色调 |
| FR-THEME-01 | TC-THEME-002 | 切换到Dark Pro主题 | UI测试 | 已登录 | 界面变为中性灰色调 |
| FR-THEME-01 | TC-THEME-003 | 切换到Cyber Glow主题 | UI测试 | 已登录 | 界面变为深紫色调+霓虹色点缀 |
| FR-THEME-02 | TC-THEME-004 | 主题偏好持久化 | 功能测试 | 切换主题后刷新页面 | 刷新后仍保持上次选择的主题 |
| FR-THEME-03 | TC-THEME-005 | CSS变量覆盖验证 | UI测试 | 检查DOM样式 | 所有颜色值均来自CSS变量 |

### 3.7 非功能需求测试用例

| 需求ID | 测试用例ID | 测试场景 | 测试类型 | 预期结果 |
|--------|-----------|---------|---------|---------|
| NFR-PERF-01 | TC-PERF-001 | 首屏加载时间 | 性能测试 | Lighthouse Performance Score ≥ 85, LCP ≤ 2s |
| NFR-PERF-02 | TC-PERF-002 | Agent大厅15张卡片渲染 | 性能测试 | 首次内容渲染 ≤ 1s |
| NFR-PERF-03 | TC-PERF-003 | 节点拖拽延迟 | 性能测试 | 帧率 ≥ 30fps, 延迟 ≤ 50ms |
| NFR-PERF-04 | TC-PERF-004 | API响应时间 | 性能测试 | P50 ≤ 100ms, P95 ≤ 500ms |
| NFR-SEC-01 | TC-SEC-001 | HTTPS强制验证 | 安全测试 | HTTP请求自动重定向到HTTPS |
| NFR-SEC-02 | TC-SEC-002 | 密码存储加密 | 安全测试 | 数据库中密码为BCrypt哈希，非明文 |
| NFR-SEC-04 | TC-SEC-003 | XSS注入防护 | 安全测试 | 输入`<script>`标签不执行 |
| NFR-SEC-04 | TC-SEC-004 | SQL注入防护 | 安全测试 | 输入`' OR 1=1`不影响查询结果 |
| NFR-SEC-06 | TC-SEC-005 | API速率限制 | 安全测试 | 超过限制返回429 Too Many Requests |
| NFR-UX-05 | TC-UX-001 | Chrome浏览器兼容 | 兼容性测试 | Chrome 90+功能正常 |
| NFR-UX-05 | TC-UX-002 | Firefox浏览器兼容 | 兼容性测试 | Firefox 88+功能正常 |

---

## 四、数据库表到需求映射

| 表编号 | 表名 | 对应需求ID | 说明 |
|--------|------|-----------|------|
| DB-01 | users | FR-AUTH-01~05, FR-RBAC-03 | 用户表，存储账号信息和偏好 |
| DB-02 | roles | FR-RBAC-01~02 | 角色表，4种预置角色 |
| DB-03 | permissions | FR-RBAC-02 | 权限表，功能操作权限定义 |
| DB-04 | role_permissions | FR-RBAC-02 | 角色-权限关联表 |
| DB-05 | user_roles | FR-RBAC-01, FR-RBAC-03 | 用户-角色关联表 |
| DB-06 | agents | FR-HALL-01~05, FR-DETAIL-01~02 | Agent主表，15个Agent记录 |
| DB-07 | agent_tags | FR-HALL-02 | Agent-标签关联表 |
| DB-08 | tag_categories | FR-HALL-02 | 标签分类表 |
| DB-09 | agent_details | FR-DETAIL-01~02 | Agent详情表，含IO Schema |
| DB-10 | workflows | FR-FLOW-01~09 | 工作流主表 |
| DB-11 | workflow_agents | FR-FLOW-02~03, FR-FLOW-08 | 工作流-Agent节点关联表 |
| DB-12 | executions | FR-FLOW-06~07 | 工作流执行记录表 |
| DB-13 | execution_logs | FR-FLOW-06~07 | 执行日志表，节点级粒度 |

---

## 五、变更控制流程

### 5.1 变更分类

| 变更级别 | 定义 | 审批权限 | 响应时间 |
|---------|------|---------|---------|
| 紧急变更 | 影响线上功能可用性或安全漏洞 | 技术负责人口头批准，事后补流程 | 4小时内 |
| 重大变更 | 影响MVP核心路径或数据库结构 | 产品负责人 + 技术负责人联合审批 | 2个工作日内 |
| 一般变更 | 新增P2功能或优化已有功能 | 产品负责人独立审批 | 5个工作日内 |
| 轻微变更 | UI文案调整、Bug修复 | 开发人员自行决定 | Sprint内 |

### 5.2 变更流程

```
Step 1: 提出变更
  │  填写《需求变更申请表》：变更原因、影响范围、工作量估算
  ▼
Step 2: 影响分析
  │  技术负责人评估：工作量、技术风险、对现有功能的影响
  │  测试负责人评估：回归测试范围、新增测试用例数量
  ▼
Step 3: RICE重评（如需）
  │  产品负责人根据影响分析重新评分
  │  确定是否调整优先级
  ▼
Step 4: 审批决策
  │  按变更级别提交对应审批人
  │  批准 → 进入Step 5 | 驳回 → 通知申请人并说明原因
  ▼
Step 5: 更新文档
  │  更新本RTM文档
  │  更新用户故事地图（如涉及新用户故事）
  │  更新优先级矩阵（如涉及RICE评分变化）
  ▼
Step 6: 实施与验证
  │  开发实施 → 代码审查 → 测试验证 → 上线
  ▼
Step 7: 变更记录归档
     记录变更ID、变更内容、影响的需求ID、实施日期
```

### 5.3 变更记录模板

| 字段 | 说明 |
|------|------|
| 变更ID | CHG-{YYYY}-{序号}，如 CHG-2026-001 |
| 提出日期 | 变更申请提交日期 |
| 提出人 | 变更申请人 |
| 变更级别 | 紧急/重大/一般/轻微 |
| 变更描述 | 变更的具体内容 |
| 影响的需求ID | 受影响的FR/NFR编号 |
| 影响的测试用例 | 受影响的TC编号 |
| 工作量估算 | 人天 |
| 审批人 | 审批决策人 |
| 审批结果 | 批准/驳回/延期 |
| 实施日期 | 实际完成日期 |
| 验证结果 | 通过/未通过 |

### 5.4 变更影响追踪

当需求发生变更时，需要同步更新以下关联项：

```
需求变更 → 更新功能需求(FR)
         → 更新用户故事(US)
         → 检查测试用例(TC)是否需要新增/修改
         → 检查数据库表(DB)是否需要DDL变更
         → 检查API接口是否需要新增/修改
         → 检查非功能需求(NFR)是否受影响
         → 更新RICE评分（如影响范围或工作量）
         → 更新版本计划（如影响里程碑时间）
```

---

## 六、追溯矩阵完整度检查

### 6.1 覆盖率统计

| 检查维度 | 总数 | 已映射 | 覆盖率 |
|---------|------|--------|--------|
| 功能需求(FR) | 27 | 27 | 100% |
| 用户故事(MVP) | 20 | 20 | 100% |
| 测试用例 | 54 | 54 | 100% |
| 数据库表 | 13 | 13 | 100% |
| API接口 | 28 | 28 | 100% |

### 6.2 孤儿项检查

| 检查项 | 结果 |
|--------|------|
| 无测试用例的功能需求 | 0个（所有FR至少有1个TC） |
| 无功能需求的用户故事 | 0个（所有US映射到至少1个FR） |
| 无API接口的后端服务 | 0个 |
| 无需求对应的数据库表 | 0个 |

**矩阵完整度：100%**
