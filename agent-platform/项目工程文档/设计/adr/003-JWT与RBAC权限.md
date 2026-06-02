# ADR-003: JWT + RBAC 认证方案

## 元信息

| 项目 | 内容 |
|------|------|
| 状态 | 已采纳 (Accepted) |
| 日期 | 2026-05-25 |
| 决策者 | 架构组 |
| 上下文 | 用户认证与授权方案选择 |

---

## 背景

多 Agent 智能体平台需要实现用户认证和授权系统。核心需求：

1. 用户登录后获取身份凭证，后续请求携带凭证
2. 验证用户对资源的操作权限（如：谁能创建工作流、谁能发布 Agent）
3. 支持角色和权限的灵活管理
4. Token 失效机制（登出、密码修改后立即使已发放的 Token 失效）
5. 支持多端同时登录

### 安全需求矩阵

| 操作 | 游客 | 普通用户 | Agent管理员 | 系统管理员 |
|------|------|---------|-----------|-----------|
| 浏览 Agent 大厅 | 是 | 是 | 是 | 是 |
| 查看 Agent 详情 | 是 | 是 | 是 | 是 |
| 创建工作流 | 否 | 是 | 是 | 是 |
| 执行工作流 | 否 | 是 | 是 | 是 |
| 创建 Agent | 否 | 否 | 是 | 是 |
| 发布 Agent | 否 | 否 | 是 | 是 |
| 管理用户 | 否 | 否 | 否 | 是 |
| 管理角色权限 | 否 | 否 | 否 | 是 |

---

## 决策1：JWT vs Session

### 方案A：JWT (JSON Web Token) — 已采纳

```
登录流程:
  Client ──[POST /auth/login]──▶ Server
                                    │
                                    ├─ 验证用户名密码
                                    ├─ 生成 Access Token (30min)
                                    ├─ 生成 Refresh Token (7d)
                                    └─ 返回两个 Token
                                    │
  Client ◀──[ tokens ]─────────────┘

请求认证:
  Client ──[GET /agents + Authorization: Bearer <token>]──▶ Server
                                                              │
                                                              ├─ 解析 Token
                                                              ├─ 检查 Redis 黑名单
                                                              ├─ 提取 userId/roles
                                                              └─ 继续处理请求

Token 刷新:
  Client ──[POST /auth/refresh + refreshToken]──▶ Server
                                                     │
                                                     ├─ 验证 Refresh Token
                                                     ├─ 生成新 Access Token
                                                     └─ 返回新 Access Token

登出:
  Client ──[POST /auth/logout]──▶ Server
                                     │
                                     ├─ 将 Access Token 加入 Redis 黑名单
                                     └─ 将 Refresh Token 加入 Redis 黑名单
```

### 方案B：Session + Cookie

```
登录流程:
  Client ──[POST /auth/login]──▶ Server
                                    │
                                    ├─ 验证用户名密码
                                    ├─ 创建 Session（存入 Redis）
                                    ├─ Set-Cookie: sessionId
                                    └─ 返回用户信息

请求认证:
  Client ──[GET /agents + Cookie: sessionId]──▶ Server
                                                    │
                                                    ├─ 从 Redis 读取 Session
                                                    ├─ 检查 Session 有效性
                                                    └─ 继续处理请求

登出:
  Client ──[POST /auth/logout]──▶ Server
                                     │
                                     └─ 删除 Redis Session
```

### 对比决策矩阵

| 评估维度 | 权重 | JWT | Session |
|---------|------|-----|---------|
| 无状态性 | 15% | 10 | 3 |
| 水平扩展友好 | 15% | 10 | 7 |
| 跨域支持 | 10% | 9 | 4 |
| 即时失效能力 | 15% | 5 | 10 |
| 实现复杂度 | 10% | 7 | 9 |
| 安全性 | 15% | 8 | 8 |
| 移动端适配 | 10% | 10 | 5 |
| 服务端存储压力 | 10% | 3 | 8 |
| **加权总分** | 100% | **7.75** | **6.55** |

### 决策理由

选择 JWT 的核心理由：
1. **水平扩展友好**：JWT 是无状态的，不需要在服务端存储会话信息。多实例部署时不需要 Session 粘滞（Sticky Session）或 Session 复制。
2. **跨域支持**：前后端分离架构下，JWT 通过 Authorization 头传输，不受 Cookie SameSite 策略限制。
3. **移动端适配**：未来如果需要开发移动端，JWT 方案可以直接复用，Session + Cookie 方案在移动端适配性差。

### JWT 即时失效的解决方案

JWT 的天然缺点是无法即时失效（Token 签发后在有效期内始终有效）。采用 Redis 黑名单方案解决：

```java
// 登出时将 Token 加入黑名单
public void logout(String accessToken, String refreshToken) {
    // 获取 Token 剩余有效期
    long accessTtl = jwtUtil.getRemainingTtl(accessToken);
    long refreshTtl = jwtUtil.getRemainingTtl(refreshToken);

    // 加入 Redis 黑名单，TTL 为 Token 剩余有效期
    redisTemplate.opsForValue().set(
        "token:blacklist:" + jwtUtil.getTokenId(accessToken),
        "1", accessTtl, TimeUnit.MILLISECONDS
    );
    redisTemplate.opsForValue().set(
        "token:blacklist:" + jwtUtil.getTokenId(refreshToken),
        "1", refreshTtl, TimeUnit.MILLISECONDS
    );
}

// 请求认证时检查黑名单
public boolean isTokenValid(String token) {
    if (jwtUtil.isTokenExpired(token)) return false;
    String tokenId = jwtUtil.getTokenId(token);
    return !redisTemplate.hasKey("token:blacklist:" + tokenId);
}
```

**为什么不直接把所有 Token 存 Redis？** 因为黑名单方案只需要存储已失效的 Token，存储量远小于存储所有活跃 Token。

### JWT Token 结构设计

```json
// Access Token Payload
{
  "sub": "12345",          // 用户ID
  "username": "zhangsan",  // 用户名
  "roles": ["ADMIN"],      // 角色列表
  "jti": "uuid-xxx",       // Token 唯一ID（用于黑名单）
  "iat": 1716600000,       // 签发时间
  "exp": 1716601800        // 过期时间（30分钟后）
}

// Refresh Token Payload
{
  "sub": "12345",          // 用户ID
  "type": "refresh",       // Token 类型标识
  "jti": "uuid-yyy",       // Token 唯一ID
  "iat": 1716600000,       // 签发时间
  "exp": 1717204800        // 过期时间（7天后）
}
```

---

## 决策2：RBAC vs ABAC

### 方案A：RBAC (基于角色的访问控制) — 已采纳

```
用户 ──N:N──▶ 角色 ──N:N──▶ 权限

权限定义：
  resource:action 格式
  例如：
    agent:create    — 创建Agent
    agent:publish   — 发布Agent
    workflow:create — 创建工作流
    workflow:execute — 执行工作流
    user:manage     — 管理用户
    role:manage     — 管理角色

预定义角色：
  · 普通用户 (user)
    → workflow:create, workflow:execute, workflow:read
    → agent:read

  · Agent管理员 (agent_admin)
    → agent:create, agent:update, agent:delete, agent:publish
    → skill:create, skill:update
    → 包含普通用户所有权限

  · 系统管理员 (admin)
    → user:manage, role:manage, permission:manage
    → 包含所有权限
```

### 方案B：ABAC (基于属性的访问控制)

```
策略定义格式：
  IF subject.role == "admin" AND resource.type == "agent" AND action == "publish"
     AND environment.time IN business_hours
  THEN ALLOW

属性来源：
  · 主体属性 (Subject)：角色、部门、职级
  · 资源属性 (Resource)：类型、所有者、密级
  · 环境属性 (Environment)：时间、IP、设备
  · 操作属性 (Action)：读、写、删除
```

### 对比决策矩阵

| 评估维度 | 权重 | RBAC | ABAC |
|---------|------|------|------|
| 实现复杂度 | 20% | 9 | 4 |
| 满足当前需求 | 20% | 9 | 10 |
| 灵活性 | 15% | 6 | 10 |
| 性能开销 | 15% | 9 | 5 |
| 管理复杂度 | 15% | 9 | 4 |
| 可维护性 | 10% | 9 | 5 |
| 审计友好 | 5% | 8 | 7 |
| **加权总分** | 100% | **8.40** | **6.05** |

### 决策理由

选择 RBAC 的核心理由：
1. **当前需求完全覆盖**：平台只有 4 个角色（游客、普通用户、Agent管理员、系统管理员），权限维度仅涉及资源和操作，不需要基于环境属性的动态策略。
2. **实现简单**：RBAC 的数据模型清晰（用户-角色-权限三张关联表），查询逻辑简单（用户 → 角色 → 权限），Redis 缓存效率高。
3. **管理直观**：管理员可以在 UI 上直接看到"这个角色有哪些权限"，ABAC 的策略规则理解成本高。
4. **性能优势**：RBAC 的权限检查只需要一次集合包含判断，ABAC 需要评估多条策略规则。

---

## 完整认证授权流程

### 登录流程时序

```
┌──────┐          ┌──────────┐        ┌──────────┐       ┌───────┐
│Client│          │Controller│        │ Service  │       │ Redis │
└──┬───┘          └────┬─────┘        └────┬─────┘       └───┬───┘
   │  POST /auth/login  │                   │                 │
   │──────────────────▶ │                   │                 │
   │                    │  login(username, pwd)               │
   │                    │──────────────────▶│                 │
   │                    │                   │                 │
   │                    │                   ├─ 查询用户       │
   │                    │                   │  (PostgreSQL)   │
   │                    │                   │                 │
   │                    │                   ├─ BCrypt验证密码  │
   │                    │                   │                 │
   │                    │                   ├─ 生成JWT Token  │
   │                    │                   │                 │
   │                    │                   ├─ 查询用户权限   │
   │                    │                   │  (PostgreSQL)   │
   │                    │                   │                 │
   │                    │                   ├─ 缓存权限到Redis│
   │                    │                   │────────────────▶│
   │                    │                   │                 │
   │  {accessToken,     │◀──────────────────│                 │
   │   refreshToken,    │                   │                 │
   │   userInfo}        │                   │                 │
   │◀────────────────── │                   │                 │
```

### 权限校验流程

```
┌──────┐      ┌────────┐       ┌──────────┐      ┌───────┐
│Client│      │JWT Filter│      │ Security │      │ Redis │
└──┬───┘      └────┬───┘       └────┬─────┘      └───┬───┘
   │  GET /api/agents   │              │                │
   │  + Bearer token    │              │                │
   │──────────────────▶ │              │                │
   │                    │              │                │
   │                    ├─ 解析Token   │                │
   │                    │              │                │
   │                    ├─ 检查黑名单  │                │
   │                    │──────────────────────────────▶│
   │                    │◀──────────────────────────────│
   │                    │              │                │
   │                    ├─ 检查权限    │                │
   │                    │  agent:read  │                │
   │                    │─────────────▶│                │
   │                    │              ├─ 查Redis缓存   │
   │                    │              │───────────────▶│
   │                    │              │◀───────────────│
   │                    │◀─────────────│                │
   │                    │              │                │
   │  200 OK + data     │              │                │
   │◀────────────────── │              │                │
```

---

## 数据库表设计

```sql
-- 用户表
CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,  -- BCrypt加密
    email VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 角色表
CREATE TABLE roles (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    code VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(200),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 权限表
CREATE TABLE permissions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(100) NOT NULL UNIQUE,
    resource VARCHAR(50) NOT NULL,
    action VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 用户-角色关联表
CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id),
    role_id BIGINT NOT NULL REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);

-- 角色-权限关联表
CREATE TABLE role_permissions (
    role_id BIGINT NOT NULL REFERENCES roles(id),
    permission_id BIGINT NOT NULL REFERENCES permissions(id),
    PRIMARY KEY (role_id, permission_id)
);
```

---

## Redis 缓存策略

| 缓存键 | 数据 | TTL | 更新策略 |
|--------|------|-----|---------|
| `user:permissions:{userId}` | 用户权限列表 | 30min | 权限变更时删除 |
| `token:blacklist:{jti}` | "1" | Token剩余有效期 | 自动过期 |
| `user:info:{userId}` | 用户基本信息 | 30min | 信息变更时删除 |
| `rate:limit:{userId}:{endpoint}` | 请求计数 | 1min / 滑动窗口 | 自动过期 |

---

## 安全措施清单

| 安全措施 | 实现方式 | 防御目标 |
|---------|---------|---------|
| Token 签名 | HMAC-SHA256 | 防篡改 |
| Access Token 短有效期 | 30 分钟 | 缩短 Token 被盗用的窗口期 |
| Refresh Token 长有效期 | 7 天 | 平衡安全性和用户体验 |
| Token 黑名单 | Redis | 支持即时失效 |
| 密码加密 | BCrypt (cost=10) | 防止密码泄露 |
| 限流 | Redis 令牌桶 | 防暴力破解 |
| CORS 白名单 | Spring CORS 配置 | 防跨域攻击 |
| XSS 防护 | CSP Header + 输入过滤 | 防脚本注入 |
| SQL 注入防护 | MyBatis-Plus 参数化 | 防SQL注入 |
| HTTPS | Nginx SSL | 传输层加密 |
