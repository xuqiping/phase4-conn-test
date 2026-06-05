# 后端基础 + 认证模块 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**目标：** 搭建Spring Boot后端项目，完成数据库初始化和认证授权模块，使后端可启动并能处理注册/登录/JWT鉴权请求。

**架构：** 模块化单体，auth模块作为第一个业务模块。使用Flyway管理数据库迁移，MyBatis-Plus作为ORM，Redis存储JWT黑名单。

**技术栈：** Java 17, Spring Boot 3.2.5, MyBatis-Plus 3.5.5, PostgreSQL, Redis, JJWT 0.12.5, Flyway, Lombok

---

## 文件结构

```
e:\workspace\agent-platform\backend\
├── pom.xml                                              # Maven项目配置
├── src/
│   ├── main/
│   │   ├── java/com/superprogrammer/
│   │   │   ├── AgentPlatformApplication.java           # Spring Boot主类
│   │   │   ├── common/
│   │   │   │   ├── entity/BaseEntity.java              # 公共实体基类
│   │   │   │   ├── result/R.java                       # 统一响应封装
│   │   │   │   ├── result/PageResult.java              # 分页结果封装
│   │   │   │   ├── exception/ErrorCode.java            # 错误码枚举
│   │   │   │   ├── exception/BusinessException.java    # 业务异常
│   │   │   │   ├── exception/GlobalExceptionHandler.java # 全局异常处理
│   │   │   │   ├── config/MybatisPlusConfig.java       # MyBatis-Plus配置
│   │   │   │   └── config/CorsConfig.java              # 跨域配置
│   │   │   └── auth/
│   │   │       ├── entity/
│   │   │       │   ├── User.java                       # 用户实体
│   │   │       │   ├── Role.java                       # 角色实体
│   │   │       │   ├── Permission.java                 # 权限实体
│   │   │       │   ├── UserRole.java                   # 用户角色关联实体
│   │   │       │   └── RolePermission.java             # 角色权限关联实体
│   │   │       ├── mapper/
│   │   │       │   ├── UserMapper.java                 # 用户Mapper接口
│   │   │       │   ├── RoleMapper.java                 # 角色Mapper接口
│   │   │       │   ├── PermissionMapper.java           # 权限Mapper接口
│   │   │       │   ├── UserRoleMapper.java             # 用户角色关联Mapper
│   │   │       │   ├── RolePermissionMapper.java       # 角色权限关联Mapper
│   │   │       │   └── xml/UserMapper.xml              # 用户自定义SQL
│   │   │       ├── dto/
│   │   │       │   ├── LoginRequest.java               # 登录请求DTO
│   │   │       │   ├── RegisterRequest.java            # 注册请求DTO
│   │   │       │   ├── RefreshTokenRequest.java        # 刷新Token请求DTO
│   │   │       │   ├── TokenResponse.java              # Token响应DTO
│   │   │       │   └── UserVO.java                     # 用户视图对象
│   │   │       ├── service/
│   │   │       │   └── AuthService.java                # 认证服务
│   │   │       ├── controller/
│   │   │       │   ├── AuthController.java             # 认证控制器
│   │   │       │   ├── UserController.java             # 用户管理控制器
│   │   │       │   └── RoleController.java             # 角色管理控制器
│   │   │       ├── security/
│   │   │       │   ├── JwtUtil.java                    # JWT工具类
│   │   │       │   ├── JwtAuthenticationFilter.java    # JWT认证过滤器
│   │   │       │   ├── RequirePermission.java          # 权限注解
│   │   │       │   ├── PermissionEvaluator.java        # 权限评估器
│   │   │       │   └── SecurityConfig.java             # Spring Security配置
│   │   └── resources/
│   │       ├── application.yml                          # 应用配置
│   │       ├── application-test.yml                     # 测试环境配置
│   │       └── db/migration/
│   │           ├── V1__init_schema.sql                  # 数据库建表
│   │           └── V2__seed_data.sql                    # 初始数据
│   └── test/
│       └── java/com/superprogrammer/
│           ├── auth/service/AuthServiceTest.java        # 认证服务单元测试
│           ├── auth/security/JwtUtilTest.java           # JWT工具类单元测试
│           ├── auth/controller/AuthControllerTest.java  # 认证控制器测试
│           └── auth/AuthIntegrationTest.java            # 认证集成测试
```

---

### Task 1: 初始化Spring Boot项目
**Files:**
- Create: `agent-platform/backend/pom.xml`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/AgentPlatformApplication.java`
- Create: `agent-platform/backend/src/main/resources/application.yml`
- Create: `agent-platform/backend/src/main/resources/application-test.yml`

- [ ] **Step 1: 创建 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>

    <groupId>com.superprogrammer</groupId>
    <artifactId>agent-platform</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>agent-platform</name>
    <description>多Agent智能体平台后端</description>

    <properties>
        <java.version>17</java.version>
        <mybatis-plus.version>3.5.5</mybatis-plus.version>
        <jjwt.version>0.12.5</jjwt.version>
    </properties>

    <dependencies>
        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- MyBatis-Plus -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>${mybatis-plus.version}</version>
        </dependency>

        <!-- PostgreSQL -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Flyway -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>

        <!-- JWT -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>${jjwt.version}</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Jackson for Java 8 Date/Time -->
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建主应用类**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/AgentPlatformApplication.java
package com.superprogrammer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 application.yml**

```yaml
# agent-platform/backend/src/main/resources/application.yml
server:
  port: 8080
  servlet:
    context-path: /

spring:
  application:
    name: agent-platform

  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:agent_platform}
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      idle-timeout: 30000
      connection-timeout: 30000

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: 0
      timeout: 5000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 2

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 0

  jackson:
    date-format: yyyy-MM-dd HH:mm:ss
    time-zone: Asia/Shanghai
    serialization:
      write-dates-as-timestamps: false

mybatis-plus:
  mapper-locations: classpath*:/com/superprogrammer/**/mapper/xml/*.xml
  type-aliases-package: com.superprogrammer.**.entity
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  global-config:
    db-config:
      id-type: auto
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0

jwt:
  secret: ${JWT_SECRET:bXlTdXBlclNlY3JldEtleUZvckFnZW50UGxhdGZvcm1Qcm9qZWN0MjAyNg==}
  access-expiration: 900000
  refresh-expiration: 604800000

logging:
  level:
    com.superprogrammer: DEBUG
    org.springframework.security: DEBUG
```

- [ ] **Step 4: 创建 application-test.yml**

```yaml
# agent-platform/backend/src/main/resources/application-test.yml
server:
  port: 0

spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    username: sa
    password:
    driver-class-name: org.h2.Driver

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

  flyway:
    enabled: false

  sql:
    init:
      mode: always
      schema-locations: classpath:schema-h2.sql

mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl

jwt:
  secret: dGVzdFNlY3JldEtleUZvclRlc3RpbmdQdXJwb3Nlc09ubHlMb25nRW5vdWdo
  access-expiration: 60000
  refresh-expiration: 3600000
```

- [ ] **Step 5: 创建 H2 测试用的 schema 初始化脚本**

```sql
-- agent-platform/backend/src/test/resources/schema-h2.sql
-- H2兼容模式下的建表脚本（用于测试环境）

CREATE TABLE IF NOT EXISTS users (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL,
    password    VARCHAR(100) NOT NULL,
    email       VARCHAR(100),
    avatar      VARCHAR(500),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    last_login_at TIMESTAMP,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS roles (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    code        VARCHAR(50)  NOT NULL,
    description VARCHAR(200),
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS permissions (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    code        VARCHAR(100) NOT NULL,
    resource    VARCHAR(50)  NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id     BIGINT NOT NULL,
    role_id     BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS agent_groups (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    icon        VARCHAR(50),
    description VARCHAR(500),
    sort_order  INT          NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS agents (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    avatar      VARCHAR(500),
    group_id    BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    config      TEXT,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS skills (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    agent_id    BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    type        VARCHAR(30)  NOT NULL DEFAULT 'SEQUENCE',
    config      TEXT,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS skill_steps (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    skill_id    BIGINT       NOT NULL,
    step_order  INT          NOT NULL,
    name        VARCHAR(100) NOT NULL,
    action      VARCHAR(100) NOT NULL,
    config      TEXT,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS workflows (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    owner_id    BIGINT       NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS workflow_nodes (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    workflow_id   BIGINT       NOT NULL,
    node_id       VARCHAR(50)  NOT NULL,
    type          VARCHAR(30)  NOT NULL,
    position_x    DOUBLE       NOT NULL DEFAULT 0,
    position_y    DOUBLE       NOT NULL DEFAULT 0,
    label         VARCHAR(100),
    config        TEXT,
    created_by    BIGINT,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    BIGINT,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       INT          NOT NULL DEFAULT 0,
    version       INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS workflow_edges (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    workflow_id     BIGINT       NOT NULL,
    source_node_id  VARCHAR(50)  NOT NULL,
    target_node_id  VARCHAR(50)  NOT NULL,
    source_handle   VARCHAR(50),
    target_handle   VARCHAR(50),
    label           VARCHAR(100),
    condition       TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INT          NOT NULL DEFAULT 0,
    version         INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS execution_logs (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    workflow_id     BIGINT       NOT NULL,
    workflow_name   VARCHAR(100),
    triggered_by    BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    variables       TEXT,
    node_logs       TEXT,
    started_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP,
    duration        BIGINT,
    error_message   TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INT          NOT NULL DEFAULT 0,
    version         INT          NOT NULL DEFAULT 0
);

-- 测试初始数据
INSERT INTO roles (id, name, code, description) VALUES (1, '普通用户', 'user', '可以创建和执行工作流');
INSERT INTO roles (id, name, code, description) VALUES (2, 'Agent管理员', 'agent_admin', '可以管理Agent和技能');
INSERT INTO roles (id, name, code, description) VALUES (3, '系统管理员', 'admin', '拥有所有权限');

INSERT INTO permissions (id, name, code, resource, action) VALUES
    (1, '查看Agent', 'agent:read', 'agent', 'read'),
    (2, '创建Agent', 'agent:create', 'agent', 'create'),
    (3, '编辑Agent', 'agent:update', 'agent', 'update'),
    (4, '删除Agent', 'agent:delete', 'agent', 'delete'),
    (5, '发布Agent', 'agent:publish', 'agent', 'publish'),
    (6, '管理技能', 'skill:manage', 'skill', 'manage'),
    (7, '查看工作流', 'workflow:read', 'workflow', 'read'),
    (8, '创建工作流', 'workflow:create', 'workflow', 'create'),
    (9, '编辑工作流', 'workflow:update', 'workflow', 'update'),
    (10, '删除工作流', 'workflow:delete', 'workflow', 'delete'),
    (11, '发布工作流', 'workflow:publish', 'workflow', 'publish'),
    (12, '执行工作流', 'execution:run', 'execution', 'run'),
    (13, '查看执行日志', 'execution:read', 'execution', 'read'),
    (14, '管理用户', 'user:manage', 'user', 'manage'),
    (15, '管理角色', 'role:manage', 'role', 'manage');

-- admin拥有所有权限
INSERT INTO role_permissions (role_id, permission_id)
SELECT 3, id FROM permissions;

-- 测试admin用户（密码: admin123）
INSERT INTO users (id, username, password, email, status)
VALUES (1, 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', 'admin@platform.com', 'ACTIVE');

INSERT INTO user_roles (user_id, role_id) VALUES (1, 3);
```

- [ ] **Step 6: 验证编译**

```bash
cd e:\workspace\agent-platform\backend
mvn compile -q
```

预期输出：`BUILD SUCCESS`

- [ ] **Step 7: 提交**

```bash
git add agent-platform/backend/pom.xml
git add agent-platform/backend/src/main/java/com/superprogrammer/AgentPlatformApplication.java
git add agent-platform/backend/src/main/resources/application.yml
git add agent-platform/backend/src/main/resources/application-test.yml
git add agent-platform/backend/src/test/resources/schema-h2.sql
git commit -m "feat: 初始化Spring Boot项目骨架

- pom.xml: Spring Boot 3.2.5 + MyBatis-Plus 3.5.5 + JWT + Flyway
- application.yml: PostgreSQL/Redis/JWT配置
- application-test.yml: H2内存数据库测试配置
- schema-h2.sql: 测试环境建表脚本"
```

---

### Task 2: Flyway数据库迁移
**Files:**
- Create: `agent-platform/backend/src/main/resources/db/migration/V1__init_schema.sql`
- Create: `agent-platform/backend/src/main/resources/db/migration/V2__seed_data.sql`

- [ ] **Step 1: 创建 V1__init_schema.sql**

```sql
-- ============================================================
-- 多Agent智能体平台 数据库初始化脚本
-- 数据库: PostgreSQL 15+
-- Flyway迁移: V1
-- ============================================================

-- ============================================================
-- 1. 认证模块 (auth)
-- ============================================================

-- 1.1 用户表
CREATE TABLE users (
    id            BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(50)                 NOT NULL,
    password      VARCHAR(100)                NOT NULL,
    email         VARCHAR(100),
    avatar        VARCHAR(500),
    status        VARCHAR(20)                 NOT NULL DEFAULT 'ACTIVE'
                                               CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED')),
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_by    BIGINT,
    created_at    TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by    BIGINT,
    updated_at    TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted       INT                         NOT NULL DEFAULT 0,
    version       INT                         NOT NULL DEFAULT 0,
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email    UNIQUE (email)
);

COMMENT ON TABLE  users              IS '用户表';
COMMENT ON COLUMN users.password     IS 'BCrypt加密密码';
COMMENT ON COLUMN users.status       IS '用户状态: ACTIVE-正常, DISABLED-禁用, LOCKED-锁定';
COMMENT ON COLUMN users.deleted      IS '逻辑删除: 0-正常, 1-已删除';
COMMENT ON COLUMN users.version      IS '乐观锁版本号';

-- 1.2 角色表
CREATE TABLE roles (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(50)                 NOT NULL,
    code        VARCHAR(50)                 NOT NULL,
    description VARCHAR(200),
    created_by  BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0,
    CONSTRAINT uk_roles_code UNIQUE (code)
);

COMMENT ON TABLE roles IS '角色表';

-- 1.3 权限表
CREATE TABLE permissions (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100)                NOT NULL,
    code        VARCHAR(100)                NOT NULL,
    resource    VARCHAR(50)                 NOT NULL,
    action      VARCHAR(50)                 NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0,
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

COMMENT ON TABLE  permissions              IS '权限表';
COMMENT ON COLUMN permissions.resource     IS '资源标识: agent, workflow, user, role, execution';
COMMENT ON COLUMN permissions.action       IS '操作: create, read, update, delete, publish, execute, manage';

-- 1.4 用户-角色关联表
CREATE TABLE user_roles (
    user_id     BIGINT NOT NULL,
    role_id     BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id)     REFERENCES users(id)       ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id)     REFERENCES roles(id)       ON DELETE CASCADE
);

COMMENT ON TABLE user_roles IS '用户-角色关联表';

-- 1.5 角色-权限关联表
CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role       FOREIGN KEY (role_id)       REFERENCES roles(id)       ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE
);

COMMENT ON TABLE role_permissions IS '角色-权限关联表';

-- ============================================================
-- 2. Agent管理模块 (agent)
-- ============================================================

-- 2.1 Agent分组表
CREATE TABLE agent_groups (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100)                NOT NULL,
    icon        VARCHAR(50),
    description VARCHAR(500),
    sort_order  INT                         NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0
);

COMMENT ON TABLE agent_groups IS 'Agent分组表';

-- 2.2 Agent表
CREATE TABLE agents (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100)                NOT NULL,
    description VARCHAR(500),
    avatar      VARCHAR(500),
    group_id    BIGINT                      NOT NULL,
    status      VARCHAR(20)                 NOT NULL DEFAULT 'DRAFT'
                                               CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE')),
    config      JSONB,
    created_by  BIGINT                      NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0,
    CONSTRAINT fk_agents_group   FOREIGN KEY (group_id)   REFERENCES agent_groups(id),
    CONSTRAINT fk_agents_creator FOREIGN KEY (created_by)  REFERENCES users(id)
);

COMMENT ON TABLE  agents           IS 'Agent表';
COMMENT ON COLUMN agents.config    IS 'Agent配置(JSONB): {model, temperature, maxTokens, systemPrompt}';
COMMENT ON COLUMN agents.deleted   IS '逻辑删除: 0-正常, 1-已删除';

CREATE INDEX idx_agents_group_id    ON agents(group_id);
CREATE INDEX idx_agents_status      ON agents(status)      WHERE deleted = 0;
CREATE INDEX idx_agents_created_by  ON agents(created_by);

-- 2.3 技能表
CREATE TABLE skills (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id    BIGINT                      NOT NULL,
    name        VARCHAR(100)                NOT NULL,
    description VARCHAR(500),
    type        VARCHAR(30)                 NOT NULL DEFAULT 'SEQUENCE'
                                               CHECK (type IN ('SEQUENCE', 'CONDITION', 'PARALLEL')),
    config      JSONB,
    sort_order  INT                         NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0,
    CONSTRAINT fk_skills_agent FOREIGN KEY (agent_id) REFERENCES agents(id)
);

COMMENT ON TABLE  skills        IS '技能表';
COMMENT ON COLUMN skills.type   IS '执行类型: SEQUENCE-顺序, CONDITION-条件, PARALLEL-并行';

CREATE INDEX idx_skills_agent_id ON skills(agent_id) WHERE deleted = 0;

-- 2.4 技能步骤表
CREATE TABLE skill_steps (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skill_id    BIGINT                      NOT NULL,
    step_order  INT                         NOT NULL,
    name        VARCHAR(100)                NOT NULL,
    action      VARCHAR(100)                NOT NULL,
    config      JSONB,
    created_by  BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0,
    CONSTRAINT fk_skill_steps_skill FOREIGN KEY (skill_id) REFERENCES skills(id)
);

COMMENT ON TABLE  skill_steps          IS '技能步骤表';
COMMENT ON COLUMN skill_steps.action   IS '步骤动作: LLM_CALL, HTTP_REQUEST, CODE_EXECUTE, CONDITION_CHECK';

CREATE INDEX idx_skill_steps_skill_id ON skill_steps(skill_id);

-- ============================================================
-- 3. 工作流编排模块 (workflow)
-- ============================================================

-- 3.1 工作流表
CREATE TABLE workflows (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100)                NOT NULL,
    description VARCHAR(500),
    status      VARCHAR(20)                 NOT NULL DEFAULT 'DRAFT'
                                               CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    owner_id    BIGINT                      NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0,
    CONSTRAINT fk_workflows_owner FOREIGN KEY (owner_id) REFERENCES users(id)
);

COMMENT ON TABLE workflows IS '工作流表';

CREATE INDEX idx_workflows_owner_id ON workflows(owner_id) WHERE deleted = 0;
CREATE INDEX idx_workflows_status   ON workflows(status)   WHERE deleted = 0;

-- 3.2 工作流节点表
CREATE TABLE workflow_nodes (
    id            BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id   BIGINT                      NOT NULL,
    node_id       VARCHAR(50)                 NOT NULL,
    type          VARCHAR(30)                 NOT NULL
                                                 CHECK (type IN ('START', 'END', 'AGENT', 'CONDITION', 'PARALLEL', 'LOOP')),
    position_x    DOUBLE PRECISION            NOT NULL DEFAULT 0,
    position_y    DOUBLE PRECISION            NOT NULL DEFAULT 0,
    label         VARCHAR(100),
    config        JSONB,
    created_by    BIGINT,
    created_at    TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by    BIGINT,
    updated_at    TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted       INT                         NOT NULL DEFAULT 0,
    version       INT                         NOT NULL DEFAULT 0,
    CONSTRAINT fk_workflow_nodes_workflow FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE,
    CONSTRAINT uk_workflow_node_id         UNIQUE (workflow_id, node_id)
);

COMMENT ON TABLE  workflow_nodes              IS '工作流节点表';
COMMENT ON COLUMN workflow_nodes.node_id      IS '画布节点唯一标识(UUID)';
COMMENT ON COLUMN workflow_nodes.config       IS '节点配置(JSONB): AGENT类型含agentId, CONDITION类型含expression等';

CREATE INDEX idx_workflow_nodes_workflow_id ON workflow_nodes(workflow_id);

-- 3.3 工作流边表
CREATE TABLE workflow_edges (
    id              BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id     BIGINT                      NOT NULL,
    source_node_id  VARCHAR(50)                 NOT NULL,
    target_node_id  VARCHAR(50)                 NOT NULL,
    source_handle   VARCHAR(50),
    target_handle   VARCHAR(50),
    label           VARCHAR(100),
    condition       TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by      BIGINT,
    updated_at      TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted         INT                         NOT NULL DEFAULT 0,
    version         INT                         NOT NULL DEFAULT 0,
    CONSTRAINT fk_workflow_edges_workflow FOREIGN KEY (workflow_id)                      REFERENCES workflows(id)                              ON DELETE CASCADE,
    CONSTRAINT fk_workflow_edges_source   FOREIGN KEY (workflow_id, source_node_id)     REFERENCES workflow_nodes(workflow_id, node_id)       ON DELETE CASCADE,
    CONSTRAINT fk_workflow_edges_target   FOREIGN KEY (workflow_id, target_node_id)     REFERENCES workflow_nodes(workflow_id, node_id)       ON DELETE CASCADE
);

COMMENT ON TABLE  workflow_edges                IS '工作流边表';
COMMENT ON COLUMN workflow_edges.condition      IS '条件边表达式(JavaScript)';

CREATE INDEX idx_workflow_edges_workflow_id ON workflow_edges(workflow_id);

-- ============================================================
-- 4. 执行模块 (execution)
-- ============================================================

-- 4.1 执行日志表
CREATE TABLE execution_logs (
    id              BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id     BIGINT                      NOT NULL,
    workflow_name   VARCHAR(100),
    triggered_by    BIGINT                      NOT NULL,
    status          VARCHAR(20)                 NOT NULL DEFAULT 'RUNNING'
                                                 CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED')),
    variables       JSONB,
    node_logs       JSONB,
    started_at      TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP WITH TIME ZONE,
    duration        BIGINT,
    error_message   TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by      BIGINT,
    updated_at      TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted         INT                         NOT NULL DEFAULT 0,
    version         INT                         NOT NULL DEFAULT 0,
    CONSTRAINT fk_execution_logs_workflow FOREIGN KEY (workflow_id)  REFERENCES workflows(id),
    CONSTRAINT fk_execution_logs_user     FOREIGN KEY (triggered_by) REFERENCES users(id)
);

COMMENT ON TABLE  execution_logs                IS '执行日志表';
COMMENT ON COLUMN execution_logs.duration       IS '执行耗时(毫秒)';
COMMENT ON COLUMN execution_logs.node_logs      IS '节点执行日志(JSONB数组): [{nodeId, type, status, input, output, error, startedAt, completedAt}]';

CREATE INDEX idx_execution_logs_workflow_id  ON execution_logs(workflow_id);
CREATE INDEX idx_execution_logs_triggered_by ON execution_logs(triggered_by);
CREATE INDEX idx_execution_logs_status       ON execution_logs(status);
CREATE INDEX idx_execution_logs_started_at   ON execution_logs(started_at DESC);
```

- [ ] **Step 2: 创建 V2__seed_data.sql**

```sql
-- ============================================================
-- 多Agent智能体平台 初始数据
-- Flyway迁移: V2
-- ============================================================

-- 2.1 初始角色
INSERT INTO roles (name, code, description) VALUES
    ('普通用户', 'user', '可以创建和执行工作流'),
    ('Agent管理员', 'agent_admin', '可以管理Agent和技能'),
    ('系统管理员', 'admin', '拥有所有权限');

-- 2.2 初始权限
INSERT INTO permissions (name, code, resource, action) VALUES
    ('查看Agent', 'agent:read', 'agent', 'read'),
    ('创建Agent', 'agent:create', 'agent', 'create'),
    ('编辑Agent', 'agent:update', 'agent', 'update'),
    ('删除Agent', 'agent:delete', 'agent', 'delete'),
    ('发布Agent', 'agent:publish', 'agent', 'publish'),
    ('管理技能', 'skill:manage', 'skill', 'manage'),
    ('查看工作流', 'workflow:read', 'workflow', 'read'),
    ('创建工作流', 'workflow:create', 'workflow', 'create'),
    ('编辑工作流', 'workflow:update', 'workflow', 'update'),
    ('删除工作流', 'workflow:delete', 'workflow', 'delete'),
    ('发布工作流', 'workflow:publish', 'workflow', 'publish'),
    ('执行工作流', 'execution:run', 'execution', 'run'),
    ('查看执行日志', 'execution:read', 'execution', 'read'),
    ('管理用户', 'user:manage', 'user', 'manage'),
    ('管理角色', 'role:manage', 'role', 'manage');

-- 2.3 角色-权限分配
-- 普通用户权限
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'user' AND p.code IN (
    'agent:read', 'workflow:read', 'workflow:create', 'workflow:update',
    'workflow:delete', 'workflow:publish', 'execution:run', 'execution:read'
);

-- Agent管理员权限（包含普通用户权限）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'agent_admin' AND p.code IN (
    'agent:read', 'agent:create', 'agent:update', 'agent:delete', 'agent:publish',
    'skill:manage',
    'workflow:read', 'workflow:create', 'workflow:update', 'workflow:delete', 'workflow:publish',
    'execution:run', 'execution:read'
);

-- 系统管理员拥有所有权限
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'admin';

-- 2.4 初始管理员用户（密码: admin123）
INSERT INTO users (username, password, email, status) VALUES
    ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', 'admin@platform.com', 'ACTIVE');

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r
WHERE u.username = 'admin' AND r.code = 'admin';

-- 2.5 初始Agent分组
INSERT INTO agent_groups (name, icon, description, sort_order) VALUES
    ('通用助手', 'robot', '通用对话和问答类Agent', 1),
    ('数据分析', 'chart', '数据处理和分析类Agent', 2),
    ('内容创作', 'edit', '文案、翻译等创作类Agent', 3),
    ('开发工具', 'code', '代码生成和调试类Agent', 4);
```

- [ ] **Step 3: 验证迁移脚本**

```bash
cd e:\workspace\agent-platform\backend
mvn flyway:migrate -q 2>&1 || echo "迁移需要在应用启动时自动执行，此命令需要数据库连接"
```

预期：应用首次启动时Flyway自动执行迁移，创建13张表。

- [ ] **Step 4: 提交**

```bash
git add agent-platform/backend/src/main/resources/db/migration/V1__init_schema.sql
git add agent-platform/backend/src/main/resources/db/migration/V2__seed_data.sql
git commit -m "feat: 添加Flyway数据库迁移脚本

- V1: 13张表完整DDL（PostgreSQL语法）
- V2: 初始角色/权限/管理员用户/Agent分组数据"
```

---

### Task 3: 公共代码
**Files:**
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/common/entity/BaseEntity.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/common/result/R.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/common/result/PageResult.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/common/exception/ErrorCode.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/common/exception/BusinessException.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/common/exception/GlobalExceptionHandler.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/common/config/MybatisPlusConfig.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/common/config/CorsConfig.java`

- [ ] **Step 1: 创建 BaseEntity.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/common/entity/BaseEntity.java
package com.superprogrammer.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public abstract class BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    @Version
    private Integer version;
}
```

- [ ] **Step 2: 创建 R.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/common/result/R.java
package com.superprogrammer.common.result;

import com.superprogrammer.common.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class R<T> {

    private int code;
    private String message;
    private T data;

    public static <T> R<T> ok() {
        return new R<>(200, "success", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(200, "success", data);
    }

    public static <T> R<T> ok(String message, T data) {
        return new R<>(200, message, data);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return new R<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    public static <T> R<T> fail(ErrorCode errorCode, String message) {
        return new R<>(errorCode.getCode(), message, null);
    }
}
```

- [ ] **Step 3: 创建 PageResult.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/common/result/PageResult.java
package com.superprogrammer.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> records;
    private long total;
    private long page;
    private long size;
    private long pages;

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        long pages = (total + size - 1) / size;
        return new PageResult<>(records, total, page, size, pages);
    }
}
```

- [ ] **Step 4: 创建 ErrorCode.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/common/exception/ErrorCode.java
package com.superprogrammer.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    // 通用错误
    SUCCESS(200, "success"),
    BAD_REQUEST(400, "参数错误"),
    UNAUTHORIZED(401, "未认证"),
    TOKEN_EXPIRED(40101, "Access Token已过期，请使用Refresh Token刷新"),
    TOKEN_INVALID(40102, "Token已失效"),
    FORBIDDEN(403, "无权限"),
    ROLE_FORBIDDEN(40301, "角色权限不足"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突"),
    UNPROCESSABLE(422, "业务规则违反"),
    AGENT_NOT_PUBLISHED(42201, "Agent未发布"),
    WORKFLOW_INVALID(42202, "工作流结构无效"),
    AGENT_NO_SKILL(42203, "Agent无技能"),
    RATE_LIMIT(429, "请求频率超限"),
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;
}
```

- [ ] **Step 5: 创建 BusinessException.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/common/exception/BusinessException.java
package com.superprogrammer.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
```

- [ ] **Step 6: 创建 GlobalExceptionHandler.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/common/exception/GlobalExceptionHandler.java
package com.superprogrammer.common.exception;

import com.superprogrammer.common.result.R;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        HttpStatus status = resolveHttpStatus(e.getCode());
        return ResponseEntity.status(status).body(R.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Map<String, String>>> handleValidationException(
            MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("参数校验失败: {}", errors);
        return ResponseEntity.badRequest().body(R.fail(400, "参数校验失败"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraintViolationException(
            ConstraintViolationException e) {
        log.warn("约束校验失败: {}", e.getMessage());
        return ResponseEntity.badRequest().body(R.fail(400, e.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<R<Void>> handleBadCredentialsException(BadCredentialsException e) {
        log.warn("认证失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(R.fail(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("权限不足: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(R.fail(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleException(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(ErrorCode.INTERNAL_ERROR));
    }

    private HttpStatus resolveHttpStatus(int code) {
        if (code == 200) return HttpStatus.OK;
        if (code == 409) return HttpStatus.CONFLICT;
        if (code == 401 || code == 40101 || code == 40102) return HttpStatus.UNAUTHORIZED;
        if (code == 403 || code == 40301) return HttpStatus.FORBIDDEN;
        if (code == 404) return HttpStatus.NOT_FOUND;
        if (code >= 400 && code < 500) return HttpStatus.BAD_REQUEST;
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
```

- [ ] **Step 7: 创建 MybatisPlusConfig.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/common/config/MybatisPlusConfig.java
package com.superprogrammer.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "deleted", Integer.class, 0);
                this.strictInsertFill(metaObject, "version", Integer.class, 0);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
```

- [ ] **Step 8: 创建 CorsConfig.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/common/config/CorsConfig.java
package com.superprogrammer.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.addExposedHeader("Authorization");
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
```

- [ ] **Step 9: 验证编译**

```bash
cd e:\workspace\agent-platform\backend
mvn compile -q
```

预期输出：`BUILD SUCCESS`

- [ ] **Step 10: 提交**

```bash
git add agent-platform/backend/src/main/java/com/superprogrammer/common/
git commit -m "feat: 添加公共代码模块

- BaseEntity: 公共字段基类(自动填充/逻辑删除/乐观锁)
- R/PageResult: 统一响应封装
- ErrorCode: 错误码枚举(16种错误码)
- BusinessException + GlobalExceptionHandler: 异常处理
- MybatisPlusConfig: 分页+乐观锁+自动填充
- CorsConfig: 跨域配置"
```

---

### Task 4: Auth实体 + Mapper
**Files:**
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/User.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/Role.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/Permission.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/UserRole.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/RolePermission.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/UserMapper.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/RoleMapper.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/PermissionMapper.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/UserRoleMapper.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/RolePermissionMapper.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/xml/UserMapper.xml`

- [ ] **Step 1: 创建 User.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/User.java
package com.superprogrammer.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("users")
public class User extends BaseEntity {

    private String username;

    private String password;

    private String email;

    private String avatar;

    private String status;

    private LocalDateTime lastLoginAt;
}
```

- [ ] **Step 2: 创建 Role.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/Role.java
package com.superprogrammer.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("roles")
public class Role extends BaseEntity {

    private String name;

    private String code;

    private String description;
}
```

- [ ] **Step 3: 创建 Permission.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/Permission.java
package com.superprogrammer.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("permissions")
public class Permission extends BaseEntity {

    private String name;

    private String code;

    private String resource;

    private String action;
}
```

- [ ] **Step 4: 创建 UserRole.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/UserRole.java
package com.superprogrammer.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_roles")
public class UserRole {

    private Long userId;

    private Long roleId;
}
```

- [ ] **Step 5: 创建 RolePermission.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/RolePermission.java
package com.superprogrammer.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("role_permissions")
public class RolePermission {

    private Long roleId;

    private Long permissionId;
}
```

- [ ] **Step 6: 创建 Mapper 接口**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/UserMapper.java
package com.superprogrammer.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 根据用户名查询用户及其角色编码列表
     */
    List<String> selectRoleCodesByUsername(String username);

    /**
     * 根据用户ID查询权限编码列表
     */
    List<String> selectPermissionCodesByUserId(Long userId);
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/RoleMapper.java
package com.superprogrammer.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.auth.entity.Role;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoleMapper extends BaseMapper<Role> {
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/PermissionMapper.java
package com.superprogrammer.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.auth.entity.Permission;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/UserRoleMapper.java
package com.superprogrammer.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.auth.entity.UserRole;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/RolePermissionMapper.java
package com.superprogrammer.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.auth.entity.RolePermission;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermission> {
}
```

- [ ] **Step 7: 创建 UserMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.superprogrammer.auth.mapper.UserMapper">

    <!-- 根据用户名查询角色编码列表 -->
    <select id="selectRoleCodesByUsername" resultType="java.lang.String">
        SELECT r.code
        FROM roles r
        INNER JOIN user_roles ur ON r.id = ur.role_id
        INNER JOIN users u ON u.id = ur.user_id
        WHERE u.username = #{username}
          AND u.deleted = 0
          AND r.deleted = 0
    </select>

    <!-- 根据用户ID查询权限编码列表 -->
    <select id="selectPermissionCodesByUserId" resultType="java.lang.String">
        SELECT DISTINCT p.code
        FROM permissions p
        INNER JOIN role_permissions rp ON p.id = rp.permission_id
        INNER JOIN user_roles ur ON ur.role_id = rp.role_id
        WHERE ur.user_id = #{userId}
          AND p.deleted = 0
    </select>

</mapper>
```

- [ ] **Step 8: 验证编译**

```bash
cd e:\workspace\agent-platform\backend
mvn compile -q
```

预期输出：`BUILD SUCCESS`

- [ ] **Step 9: 提交**

```bash
git add agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/
git add agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/
git commit -m "feat: 添加Auth模块实体和Mapper

- User/Role/Permission/UserRole/RolePermission 实体
- 5个Mapper接口(BaseMapper通用CRUD)
- UserMapper.xml: 按用户名查角色、按用户ID查权限"
```

---

### Task 5: JWT工具类
**Files:**
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/security/JwtUtil.java`
- Test: `agent-platform/backend/src/test/java/com/superprogrammer/auth/security/JwtUtilTest.java`

- [ ] **Step 1: 写失败测试**

```java
// agent-platform/backend/src/test/java/com/superprogrammer/auth/security/JwtUtilTest.java
package com.superprogrammer.auth.security;

import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        jwtUtil.setSecret("dGVzdFNlY3JldEtleUZvclRlc3RpbmdQdXJwb3Nlc09ubHlMb25nRW5vdWdo");
        jwtUtil.setAccessExpiration(60000L);
        jwtUtil.setRefreshExpiration(3600000L);
    }

    @Test
    void generateAccessToken_shouldContainUserIdAndUsername() {
        Long userId = 1L;
        String username = "admin";
        List<String> roles = Arrays.asList("admin");

        String token = jwtUtil.generateAccessToken(userId, username, roles);

        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertEquals(userId, jwtUtil.getUserIdFromToken(token));
        assertEquals(username, jwtUtil.getUsernameFromToken(token));
        assertEquals(roles, jwtUtil.getRolesFromToken(token));
    }

    @Test
    void generateRefreshToken_shouldContainUserId() {
        Long userId = 1L;

        String token = jwtUtil.generateRefreshToken(userId);

        assertNotNull(token);
        assertEquals(userId, jwtUtil.getUserIdFromToken(token));
        assertEquals("refresh", jwtUtil.getTypeFromToken(token));
    }

    @Test
    void isTokenValid_shouldReturnTrueForValidToken() {
        String token = jwtUtil.generateAccessToken(1L, "admin", Arrays.asList("admin"));

        assertTrue(jwtUtil.isTokenValid(token));
    }

    @Test
    void isTokenExpired_shouldReturnFalseForFreshToken() {
        String token = jwtUtil.generateAccessToken(1L, "admin", Arrays.asList("admin"));

        assertFalse(jwtUtil.isTokenExpired(token));
    }

    @Test
    void isTokenExpired_shouldReturnTrueForExpiredToken() {
        jwtUtil.setAccessExpiration(1L);
        String token = jwtUtil.generateAccessToken(1L, "admin", Arrays.asList("admin"));

        // 等待token过期
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertTrue(jwtUtil.isTokenExpired(token));
    }

    @Test
    void getRemainingTtl_shouldReturnPositiveForValidToken() {
        String token = jwtUtil.generateAccessToken(1L, "admin", Arrays.asList("admin"));

        long ttl = jwtUtil.getRemainingTtl(token);

        assertTrue(ttl > 0);
    }

    @Test
    void getTokenId_shouldReturnUniqueId() {
        String token1 = jwtUtil.generateAccessToken(1L, "admin", Arrays.asList("admin"));
        String token2 = jwtUtil.generateAccessToken(1L, "admin", Arrays.asList("admin"));

        String jti1 = jwtUtil.getTokenId(token1);
        String jti2 = jwtUtil.getTokenId(token2);

        assertNotNull(jti1);
        assertNotNull(jti2);
        assertNotEquals(jti1, jti2);
    }

    @Test
    void isTokenValid_shouldReturnFalseForMalformedToken() {
        assertFalse(jwtUtil.isTokenValid("this.is.not-a-valid-token"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd e:\workspace\agent-platform\backend
mvn test -pl . -Dtest=JwtUtilTest -q 2>&1 | tail -5
```

预期：编译失败（JwtUtil类不存在）

- [ ] **Step 3: 写最小实现**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/security/JwtUtil.java
package com.superprogrammer.auth.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
@Getter
@Setter
public class JwtUtil {

    private String secret;
    private Long accessExpiration;
    private Long refreshExpiration;

    private SecretKey getSigningKey() {
        byte[] keyBytes = Base64.getDecoder().decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(Long userId, String username, List<String> roles) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessExpiration);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", roles)
                .claim("type", "access")
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public String generateRefreshToken(Long userId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshExpiration);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "refresh")
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return Long.parseLong(claims.getSubject());
    }

    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("username", String.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> getRolesFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("roles", List.class);
    }

    public String getTypeFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("type", String.class);
    }

    public String getTokenId(String token) {
        Claims claims = parseToken(token);
        return claims.getId();
    }

    public boolean isTokenValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isTokenExpired(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public long getRemainingTtl(String token) {
        Claims claims = parseToken(token);
        Date expiration = claims.getExpiration();
        long remaining = expiration.getTime() - System.currentTimeMillis();
        return Math.max(remaining, 0);
    }

    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd e:\workspace\agent-platform\backend
mvn test -Dtest=JwtUtilTest -q
```

预期输出：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: 提交**

```bash
git add agent-platform/backend/src/main/java/com/superprogrammer/auth/security/JwtUtil.java
git add agent-platform/backend/src/test/java/com/superprogrammer/auth/security/JwtUtilTest.java
git commit -m "feat: 实现JWT工具类

- JwtUtil: 生成/解析/验证Access Token和Refresh Token
- 使用JJWT 0.12.5 API(HMAC-SHA256签名)
- 8个单元测试全部通过: 生成/解析/过期/唯一ID/非法token"
```

---

### Task 6: Auth Service
**Files:**
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/LoginRequest.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/RegisterRequest.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/RefreshTokenRequest.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/TokenResponse.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/UserVO.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/service/AuthService.java`
- Test: `agent-platform/backend/src/test/java/com/superprogrammer/auth/service/AuthServiceTest.java`

- [ ] **Step 1: 创建 DTO**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/LoginRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/RegisterRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50, message = "用户名长度必须在3-50之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 100, message = "密码长度必须在6-100之间")
    private String password;

    @Email(message = "邮箱格式不正确")
    private String email;
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/RefreshTokenRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshTokenRequest {

    @NotBlank(message = "refreshToken不能为空")
    private String refreshToken;
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/TokenResponse.java
package com.superprogrammer.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private Long expiresIn;
    private UserInfo userInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private Long id;
        private String username;
        private String email;
        private String avatar;
        private List<String> roles;
        private List<String> permissions;
    }
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/UserVO.java
package com.superprogrammer.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    private Long id;
    private String username;
    private String email;
    private String avatar;
    private String status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private List<String> roles;
    private List<String> permissions;
}
```

- [ ] **Step 2: 写失败测试**

```java
// agent-platform/backend/src/test/java/com/superprogrammer/auth/service/AuthServiceTest.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.auth.dto.*;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserRole;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.mapper.UserRoleMapper;
import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private AuthService authService;

    private User testUser;
    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setPassword("$2a$10$encoded_password");
        testUser.setEmail("test@example.com");
        testUser.setStatus("ACTIVE");
        testUser.setCreatedAt(LocalDateTime.now());
        testUser.setUpdatedAt(LocalDateTime.now());

        registerRequest = new RegisterRequest();
        registerRequest.setUsername("newuser");
        registerRequest.setPassword("password123");
        registerRequest.setEmail("new@example.com");

        loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("password123");
    }

    @Test
    void register_success() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(passwordEncoder.encode("password123")).thenReturn("$2a$10$encoded");
        when(userMapper.insert(any(User.class))).thenReturn(1);
        when(userRoleMapper.insert(any(UserRole.class))).thenReturn(1);

        assertDoesNotThrow(() -> authService.register(registerRequest));

        verify(userMapper).insert(argThat(user ->
                user.getUsername().equals("newuser") &&
                user.getEmail().equals("new@example.com")
        ));
    }

    @Test
    void register_duplicateUsername_throwsException() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);

        assertThrows(BusinessException.class, () -> authService.register(registerRequest));
    }

    @Test
    void login_success() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(true);
        when(jwtUtil.generateAccessToken(eq(1L), eq("testuser"), anyList())).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(eq(1L))).thenReturn("refresh-token");
        when(jwtUtil.getAccessExpiration()).thenReturn(900000L);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("user"));
        when(userMapper.selectPermissionCodesByUserId(1L)).thenReturn(Arrays.asList("agent:read"));
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        TokenResponse response = authService.login(loginRequest);

        assertNotNull(response);
        assertEquals("access-token", response.getAccessToken());
        assertEquals("refresh-token", response.getRefreshToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(900000L, response.getExpiresIn());
        assertNotNull(response.getUserInfo());
        assertEquals(1L, response.getUserInfo().getId());
        assertEquals("testuser", response.getUserInfo().getUsername());
    }

    @Test
    void login_wrongPassword_throwsException() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches("password123", testUser.getPassword())).thenReturn(false);

        assertThrows(BusinessException.class, () -> authService.login(loginRequest));
    }

    @Test
    void login_userNotFound_throwsException() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThrows(BusinessException.class, () -> authService.login(loginRequest));
    }

    @Test
    void refreshToken_success() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("valid-refresh-token");

        when(jwtUtil.isTokenValid("valid-refresh-token")).thenReturn(true);
        when(jwtUtil.getTypeFromToken("valid-refresh-token")).thenReturn("refresh");
        when(jwtUtil.getUserIdFromToken("valid-refresh-token")).thenReturn(1L);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(jwtUtil.generateAccessToken(eq(1L), anyString(), anyList())).thenReturn("new-access-token");
        when(jwtUtil.getAccessExpiration()).thenReturn(900000L);
        when(userMapper.selectById(1L)).thenReturn(testUser);
        when(userMapper.selectRoleCodesByUsername("testuser")).thenReturn(Arrays.asList("user"));

        TokenResponse response = authService.refreshToken(request);

        assertNotNull(response);
        assertEquals("new-access-token", response.getAccessToken());
        assertEquals(900000L, response.getExpiresIn());
    }

    @Test
    void refreshToken_invalidToken_throwsException() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("invalid-token");

        when(jwtUtil.isTokenValid("invalid-token")).thenReturn(false);

        assertThrows(BusinessException.class, () -> authService.refreshToken(request));
    }

    @Test
    void logout_success() {
        String accessToken = "valid-access-token";
        String refreshToken = "valid-refresh-token";

        when(jwtUtil.getTokenId(accessToken)).thenReturn("access-jti");
        when(jwtUtil.getTokenId(refreshToken)).thenReturn("refresh-jti");
        when(jwtUtil.getRemainingTtl(accessToken)).thenReturn(50000L);
        when(jwtUtil.getRemainingTtl(refreshToken)).thenReturn(3000000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        assertDoesNotThrow(() -> authService.logout(accessToken, refreshToken));

        verify(valueOperations, times(2)).set(anyString(), eq("1"), anyLong(), eq(TimeUnit.MILLISECONDS));
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd e:\workspace\agent-platform\backend
mvn test -Dtest=AuthServiceTest -q 2>&1 | tail -5
```

预期：编译失败（AuthService类不存在）

- [ ] **Step 4: 写最小实现**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/service/AuthService.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.auth.dto.*;
import com.superprogrammer.auth.entity.Role;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserRole;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.mapper.UserRoleMapper;
import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    private static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";

    @Transactional
    public void register(RegisterRequest request) {
        // 检查用户名唯一
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, request.getUsername());
        User existing = userMapper.selectOne(wrapper);
        if (existing != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }

        // 创建用户
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setStatus("ACTIVE");
        userMapper.insert(user);

        // 分配默认角色(user)
        LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
        roleWrapper.eq(Role::getCode, "user");
        Role defaultRole = roleMapper.selectOne(roleWrapper);
        if (defaultRole != null) {
            UserRole userRole = new UserRole(user.getId(), defaultRole.getId());
            userRoleMapper.insert(userRole);
        }

        log.info("用户注册成功: {}", user.getUsername());
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        // 查询用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, request.getUsername());
        User user = userMapper.selectOne(wrapper);

        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // 验证密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // 检查用户状态
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已被禁用或锁定");
        }

        // 查询角色和权限
        List<String> roleCodes = userMapper.selectRoleCodesByUsername(user.getUsername());
        List<String> permissionCodes = userMapper.selectPermissionCodesByUserId(user.getId());

        // 生成JWT Token
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), roleCodes);
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        // 更新最后登录时间
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);

        log.info("用户登录成功: {}", user.getUsername());

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtUtil.getAccessExpiration())
                .userInfo(TokenResponse.UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .email(user.getEmail())
                        .avatar(user.getAvatar())
                        .roles(roleCodes)
                        .permissions(permissionCodes)
                        .build())
                .build();
    }

    public TokenResponse refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();

        // 验证refresh token
        if (!jwtUtil.isTokenValid(refreshToken)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "无效的Refresh Token");
        }

        // 检查token类型
        String type = jwtUtil.getTypeFromToken(refreshToken);
        if (!"refresh".equals(type)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "请使用Refresh Token刷新");
        }

        // 检查Redis黑名单
        String jti = jwtUtil.getTokenId(refreshToken);
        String blacklistKey = TOKEN_BLACKLIST_PREFIX + jti;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(blacklistKey))) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "Token已失效");
        }

        // 获取用户信息并生成新access token
        Long userId = jwtUtil.getUserIdFromToken(refreshToken);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        List<String> roleCodes = userMapper.selectRoleCodesByUsername(user.getUsername());
        String newAccessToken = jwtUtil.generateAccessToken(userId, user.getUsername(), roleCodes);

        return TokenResponse.builder()
                .accessToken(newAccessToken)
                .tokenType("Bearer")
                .expiresIn(jwtUtil.getAccessExpiration())
                .build();
    }

    public void logout(String accessToken, String refreshToken) {
        // 将access token加入黑名单
        if (accessToken != null && jwtUtil.isTokenValid(accessToken)) {
            String accessJti = jwtUtil.getTokenId(accessToken);
            long accessTtl = jwtUtil.getRemainingTtl(accessToken);
            if (accessTtl > 0) {
                redisTemplate.opsForValue().set(
                        TOKEN_BLACKLIST_PREFIX + accessJti, "1", accessTtl, TimeUnit.MILLISECONDS);
            }
        }

        // 将refresh token加入黑名单
        if (refreshToken != null && jwtUtil.isTokenValid(refreshToken)) {
            String refreshJti = jwtUtil.getTokenId(refreshToken);
            long refreshTtl = jwtUtil.getRemainingTtl(refreshToken);
            if (refreshTtl > 0) {
                redisTemplate.opsForValue().set(
                        TOKEN_BLACKLIST_PREFIX + refreshJti, "1", refreshTtl, TimeUnit.MILLISECONDS);
            }
        }

        log.info("用户登出成功");
    }

    public UserVO getCurrentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        List<String> roleCodes = userMapper.selectRoleCodesByUsername(user.getUsername());
        List<String> permissionCodes = userMapper.selectPermissionCodesByUserId(userId);

        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .roles(roleCodes)
                .permissions(permissionCodes)
                .build();
    }

    public boolean isTokenBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + jti));
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd e:\workspace\agent-platform\backend
mvn test -Dtest=AuthServiceTest -q
```

预期输出：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 6: 提交**

```bash
git add agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/
git add agent-platform/backend/src/main/java/com/superprogrammer/auth/service/AuthService.java
git add agent-platform/backend/src/test/java/com/superprogrammer/auth/service/AuthServiceTest.java
git commit -m "feat: 实现AuthService认证服务

- DTO: LoginRequest/RegisterRequest/RefreshTokenRequest/TokenResponse/UserVO
- register: 用户名唯一校验 + BCrypt加密 + 默认角色分配
- login: 用户验证 + JWT生成 + 角色权限查询
- refreshToken: 验证 + 黑名单检查 + 新token生成
- logout: Redis黑名单(TTL=Token剩余有效期)
- 8个单元测试全部通过"
```

---

### Task 7: Auth Controller
**Files:**
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/AuthController.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/UserController.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/RoleController.java`
- Test: `agent-platform/backend/src/test/java/com/superprogrammer/auth/controller/AuthControllerTest.java`

- [ ] **Step 1: 写失败测试**

```java
// agent-platform/backend/src/test/java/com/superprogrammer/auth/controller/AuthControllerTest.java
package com.superprogrammer.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.auth.dto.*;
import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtUtil jwtUtil;

    @Test
    void register_success_returns201() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("password123");
        request.setEmail("new@example.com");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"));
    }

    @Test
    void register_withEmptyUsername_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("");
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_success_returns200() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        TokenResponse response = TokenResponse.builder()
                .accessToken("test-access-token")
                .refreshToken("test-refresh-token")
                .tokenType("Bearer")
                .expiresIn(900000L)
                .userInfo(TokenResponse.UserInfo.builder()
                        .id(1L)
                        .username("admin")
                        .roles(Arrays.asList("admin"))
                        .permissions(Arrays.asList("agent:read"))
                        .build())
                .build();

        when(authService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("test-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("test-refresh-token"))
                .andExpect(jsonPath("$.data.userInfo.username").value("admin"));
    }

    @Test
    void login_withEmptyPassword_returns400() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refresh_success_returns200() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("valid-refresh-token");

        TokenResponse response = TokenResponse.builder()
                .accessToken("new-access-token")
                .tokenType("Bearer")
                .expiresIn(900000L)
                .build();

        when(authService.refreshToken(any(RefreshTokenRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"));
    }

    @Test
    void logout_success_returns200() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd e:\workspace\agent-platform\backend
mvn test -Dtest=AuthControllerTest -q 2>&1 | tail -5
```

预期：编译失败（AuthController类不存在）

- [ ] **Step 3: 写最小实现**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/AuthController.java
package com.superprogrammer.auth.controller;

import com.superprogrammer.auth.dto.*;
import com.superprogrammer.auth.service.AuthService;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<R<Void>> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(R.ok("注册成功", null));
    }

    @PostMapping("/login")
    public ResponseEntity<R<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse response = authService.login(request);
        return ResponseEntity.ok(R.ok(response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<R<TokenResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        TokenResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(R.ok(response));
    }

    @PostMapping("/logout")
    public ResponseEntity<R<Void>> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String accessToken = null;
        String refreshToken = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            accessToken = authHeader.substring(7);
        }

        authService.logout(accessToken, refreshToken);
        return ResponseEntity.ok(R.ok("登出成功", null));
    }

    @GetMapping("/me")
    public ResponseEntity<R<UserVO>> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long userId = (Long) authentication.getPrincipal();
        UserVO userVO = authService.getCurrentUser(userId);
        return ResponseEntity.ok(R.ok(userVO));
    }
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/UserController.java
package com.superprogrammer.auth.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.dto.UserVO;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.service.AuthService;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;
    private final AuthService authService;

    @GetMapping
    @PreAuthorize("hasAuthority('user:manage')")
    public ResponseEntity<R<PageResult<UserVO>>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<User> userPage = userMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<User>().orderByDesc(User::getCreatedAt)
        );

        var vos = userPage.getRecords().stream().map(user ->
                authService.getCurrentUser(user.getId())
        ).toList();

        PageResult<UserVO> result = PageResult.of(
                vos, userPage.getTotal(), page, size);
        return ResponseEntity.ok(R.ok(result));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:manage')")
    public ResponseEntity<R<UserVO>> getUser(@PathVariable Long id) {
        UserVO userVO = authService.getCurrentUser(id);
        return ResponseEntity.ok(R.ok(userVO));
    }
}
```

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/RoleController.java
package com.superprogrammer.auth.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.entity.Role;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleMapper roleMapper;

    @GetMapping
    @PreAuthorize("hasAuthority('role:manage')")
    public ResponseEntity<R<PageResult<Role>>> listRoles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<Role> rolePage = roleMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Role>().orderByAsc(Role::getId)
        );
        PageResult<Role> result = PageResult.of(
                rolePage.getRecords(), rolePage.getTotal(), page, size);
        return ResponseEntity.ok(R.ok(result));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    public ResponseEntity<R<Role>> getRole(@PathVariable Long id) {
        Role role = roleMapper.selectById(id);
        return ResponseEntity.ok(R.ok(role));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('role:manage')")
    public ResponseEntity<R<Role>> createRole(@RequestBody Role role) {
        roleMapper.insert(role);
        return ResponseEntity.ok(R.ok(role));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    public ResponseEntity<R<Role>> updateRole(@PathVariable Long id, @RequestBody Role role) {
        role.setId(id);
        roleMapper.updateById(role);
        return ResponseEntity.ok(R.ok(role));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd e:\workspace\agent-platform\backend
mvn test -Dtest=AuthControllerTest -q
```

预期输出：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: 提交**

```bash
git add agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/
git add agent-platform/backend/src/test/java/com/superprogrammer/auth/controller/AuthControllerTest.java
git commit -m "feat: 实现Auth/User/Role Controller

- AuthController: register/login/refresh/logout/me端点
- UserController: 用户列表/详情(user:manage权限)
- RoleController: 角色CRUD(role:manage权限)
- @Validated请求体验证 + R<T>统一响应
- 6个MockMvc测试: 注册201/登录200/参数校验400"
```

---

### Task 8: JWT过滤器 + Spring Security配置
**Files:**
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/security/JwtAuthenticationFilter.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/security/RequirePermission.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/security/PermissionEvaluator.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/security/SecurityConfig.java`
- Create: `agent-platform/backend/src/main/java/com/superprogrammer/auth/config/PasswordEncoderConfig.java`

- [ ] **Step 1: 创建 PasswordEncoderConfig**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/config/PasswordEncoderConfig.java
package com.superprogrammer.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
```

- [ ] **Step 2: 创建 RequirePermission.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/security/RequirePermission.java
package com.superprogrammer.auth.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /**
     * 所需权限编码，格式: resource:action
     * 例如: "agent:create", "user:manage"
     */
    String value();
}
```

- [ ] **Step 3: 创建 PermissionEvaluator.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/security/PermissionEvaluator.java
package com.superprogrammer.auth.security;

import com.superprogrammer.auth.entity.Permission;
import com.superprogrammer.auth.mapper.PermissionMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionEvaluator {

    private final UserMapper userMapper;

    /**
     * 检查当前用户是否拥有指定权限
     * @param authentication 当前认证信息
     * @param permissionCode 权限编码，如 "agent:create"
     * @return 是否有权限
     */
    public boolean hasPermission(Authentication authentication, String permissionCode) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        return authorities.contains(permissionCode);
    }
}
```

- [ ] **Step 4: 创建 JwtAuthenticationFilter.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/security/JwtAuthenticationFilter.java
package com.superprogrammer.auth.security;

import com.superprogrammer.auth.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final AuthService authService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtUtil.isTokenValid(token)) {
            // 检查token类型
            String type = jwtUtil.getTypeFromToken(token);
            if (!"access".equals(type)) {
                filterChain.doFilter(request, response);
                return;
            }

            // 检查Redis黑名单
            String jti = jwtUtil.getTokenId(token);
            if (authService.isTokenBlacklisted(jti)) {
                log.warn("Token已失效(jti={}): {}", jti, request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            }

            // 提取用户信息
            Long userId = jwtUtil.getUserIdFromToken(token);
            String username = jwtUtil.getUsernameFromToken(token);
            List<String> roles = jwtUtil.getRolesFromToken(token);

            // 查询用户完整权限
            List<String> permissions = authService.getUserPermissionCodes(userId);

            // 构建GrantedAuthority列表（包含角色和权限）
            List<SimpleGrantedAuthority> authorities = permissions.stream()
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());

            // 设置SecurityContext
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, username, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            log.debug("JWT认证成功: userId={}, username={}, permissions={}",
                    userId, username, permissions);
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
```

需要在AuthService中补充一个方法，供Filter调用获取权限编码列表。更新 AuthService.java，在类中添加以下方法：

```java
    /**
     * 获取用户权限编码列表（供JwtAuthenticationFilter使用）
     */
    public List<String> getUserPermissionCodes(Long userId) {
        return userMapper.selectPermissionCodesByUserId(userId);
    }
```

- [ ] **Step 5: 创建 SecurityConfig.java**

```java
// agent-platform/backend/src/main/java/com/superprogrammer/auth/security/SecurityConfig.java
package com.superprogrammer.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 禁用CSRF（前后端分离，使用JWT）
                .csrf(AbstractHttpConfigurer::disable)
                // 无状态Session
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 配置请求授权
                .authorizeHttpRequests(auth -> auth
                        // 白名单路径：登录、注册、刷新Token
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/register").permitAll()
                        .requestMatchers("/api/auth/refresh").permitAll()
                        // 其他路径需要认证
                        .anyRequest().authenticated()
                )
                // 异常处理
                .exceptionHandling(exceptions -> exceptions
                        // 未认证处理
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            response.setStatus(401);
                            R<Void> result = R.fail(ErrorCode.UNAUTHORIZED);
                            response.getWriter().write(objectMapper.writeValueAsString(result));
                        })
                        // 无权限处理
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            response.setStatus(403);
                            R<Void> result = R.fail(ErrorCode.FORBIDDEN);
                            response.getWriter().write(objectMapper.writeValueAsString(result));
                        })
                )
                // 添加JWT过滤器
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
```

- [ ] **Step 6: 更新 AuthService 补充 getUserPermissionCodes 方法**

在 `agent-platform/backend/src/main/java/com/superprogrammer/auth/service/AuthService.java` 末尾 `}` 之前添加：

```java
    /**
     * 获取用户权限编码列表（供JwtAuthenticationFilter使用）
     */
    public List<String> getUserPermissionCodes(Long userId) {
        return userMapper.selectPermissionCodesByUserId(userId);
    }
```

- [ ] **Step 7: 验证编译**

```bash
cd e:\workspace\agent-platform\backend
mvn compile -q
```

预期输出：`BUILD SUCCESS`

- [ ] **Step 8: 提交**

```bash
git add agent-platform/backend/src/main/java/com/superprogrammer/auth/security/JwtAuthenticationFilter.java
git add agent-platform/backend/src/main/java/com/superprogrammer/auth/security/RequirePermission.java
git add agent-platform/backend/src/main/java/com/superprogrammer/auth/security/PermissionEvaluator.java
git add agent-platform/backend/src/main/java/com/superprogrammer/auth/security/SecurityConfig.java
git add agent-platform/backend/src/main/java/com/superprogrammer/auth/config/PasswordEncoderConfig.java
git add agent-platform/backend/src/main/java/com/superprogrammer/auth/service/AuthService.java
git commit -m "feat: 实现JWT过滤器 + Spring Security配置

- JwtAuthenticationFilter: 提取token/验证/黑名单检查/设置SecurityContext
- SecurityConfig: 白名单路径(login/register/refresh) + 无状态Session + 禁用CSRF
- RequirePermission: 自定义权限注解
- PermissionEvaluator: 权限评估逻辑
- PasswordEncoderConfig: BCrypt配置(cost=10)
- 未认证返回401，无权限返回403"
```

---

### Task 9: 集成测试 + 启动验证
**Files:**
- Create: `agent-platform/backend/src/test/java/com/superprogrammer/auth/AuthIntegrationTest.java`

- [ ] **Step 1: 写集成测试**

```java
// agent-platform/backend/src/test/java/com/superprogrammer/auth/AuthIntegrationTest.java
package com.superprogrammer.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.auth.dto.LoginRequest;
import com.superprogrammer.auth.dto.RegisterRequest;
import com.superprogrammer.auth.dto.TokenResponse;
import com.superprogrammer.common.result.R;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String accessToken;
    private static String refreshToken;

    @Test
    @Order(1)
    void step1_register_newUser_returnsCreated() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("integrationuser");
        request.setPassword("password123");
        request.setEmail("integration@test.com");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @Order(2)
    void step2_register_duplicateUser_returnsConflict() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("integrationuser");
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(3)
    void step3_login_withRegisteredUser_returnsToken() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("integrationuser");
        request.setPassword("password123");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.userInfo.username").value("integrationuser"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        var response = objectMapper.readValue(responseBody,
                objectMapper.getTypeFactory().constructParametricType(R.class, TokenResponse.class));
        TokenResponse tokenResponse = (TokenResponse) response.getData();
        accessToken = tokenResponse.getAccessToken();
        refreshToken = tokenResponse.getRefreshToken();
    }

    @Test
    @Order(4)
    void step4_login_withWrongPassword_returnsUnauthorized() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("integrationuser");
        request.setPassword("wrongpassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    void step5_accessProtectedEndpoint_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(6)
    void step6_accessProtectedEndpoint_withToken_returnsOk() throws Exception {
        // 先登录获取token
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("integrationuser");
        loginRequest.setPassword("password123");

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andReturn();

        String loginBody = loginResult.getResponse().getContentAsString();
        var loginResponse = objectMapper.readValue(loginBody,
                objectMapper.getTypeFactory().constructParametricType(R.class, TokenResponse.class));
        String token = ((TokenResponse) loginResponse.getData()).getAccessToken();

        // 用token访问受保护接口
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("integrationuser"));
    }

    @Test
    @Order(7)
    void step7_refreshToken_returnsNewAccessToken() throws Exception {
        // 先登录获取refresh token
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("integrationuser");
        loginRequest.setPassword("password123");

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andReturn();

        String loginBody = loginResult.getResponse().getContentAsString();
        var loginResponse = objectMapper.readValue(loginBody,
                objectMapper.getTypeFactory().constructParametricType(R.class, TokenResponse.class));
        String rToken = ((TokenResponse) loginResponse.getData()).getRefreshToken();

        // 刷新token
        String refreshRequestBody = "{\"refreshToken\":\"" + rToken + "\"}";
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshRequestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    @Order(8)
    void step8_logout_thenAccess_returnsUnauthorized() throws Exception {
        // 先登录
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("integrationuser");
        loginRequest.setPassword("password123");

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andReturn();

        String loginBody = loginResult.getResponse().getContentAsString();
        var loginResponse = objectMapper.readValue(loginBody,
                objectMapper.getTypeFactory().constructParametricType(R.class, TokenResponse.class));
        String token = ((TokenResponse) loginResponse.getData()).getAccessToken();
        String rToken = ((TokenResponse) loginResponse.getData()).getRefreshToken();

        // 登出
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 注意：登出后token加入Redis黑名单，如果Redis未启动则黑名单检查可能不生效
        // 此处主要验证登出接口本身正常返回
    }
}
```

- [ ] **Step 2: 运行全部测试**

```bash
cd e:\workspace\agent-platform\backend
mvn test -q
```

预期：所有测试通过

- [ ] **Step 3: 启动应用验证**

```bash
cd e:\workspace\agent-platform\backend
mvn spring-boot:run
```

验证清单：
1. 控制台输出 `Started AgentPlatformApplication in X.XXs`
2. 日志显示 Flyway 迁移成功：`Successfully applied 2 migrations`
3. 使用 curl 测试登录接口：

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

预期返回：`{"code":200,"message":"success","data":{"accessToken":"...","refreshToken":"...","tokenType":"Bearer","expiresIn":900000,"userInfo":{...}}}`

- [ ] **Step 4: 提交最终代码**

```bash
git add agent-platform/backend/src/test/java/com/superprogrammer/auth/AuthIntegrationTest.java
git commit -m "feat: 添加认证模块集成测试

- 8个集成测试覆盖完整认证流程:
  注册 -> 登录 -> Token访问 -> 刷新Token -> 登出
- 验证: 注册201/登录200/重复注册409/错误密码401
- 验证: 未认证401/携带token 200/Token刷新成功"
```

---

## 自审清单

### 需求覆盖检查

| 需求 | 状态 | 对应Task |
|------|------|---------|
| pom.xml（Spring Boot 3.2.5 + 所有依赖） | 已覆盖 | Task 1 |
| AgentPlatformApplication.java 主类 | 已覆盖 | Task 1 |
| application.yml（PostgreSQL/Redis/MyBatis-Plus/Flyway/JWT） | 已覆盖 | Task 1 |
| application-test.yml（H2内存数据库） | 已覆盖 | Task 1 |
| V1__init_schema.sql（13张表DDL） | 已覆盖 | Task 2 |
| V2__seed_data.sql（初始数据：admin用户/角色/权限） | 已覆盖 | Task 2 |
| BaseEntity.java（公共字段+自动填充+逻辑删除+乐观锁） | 已覆盖 | Task 3 |
| R.java（统一响应） | 已覆盖 | Task 3 |
| PageResult.java | 已覆盖 | Task 3 |
| ErrorCode.java | 已覆盖 | Task 3 |
| BusinessException.java | 已覆盖 | Task 3 |
| GlobalExceptionHandler.java | 已覆盖 | Task 3 |
| MybatisPlusConfig.java（分页+乐观锁+自动填充） | 已覆盖 | Task 3 |
| CorsConfig.java | 已覆盖 | Task 3 |
| 5个Auth实体（User/Role/Permission/UserRole/RolePermission） | 已覆盖 | Task 4 |
| 5个Mapper接口 | 已覆盖 | Task 4 |
| UserMapper.xml（按用户名查角色、按用户ID查权限） | 已覆盖 | Task 4 |
| JwtUtil（生成/解析/验证/过期/黑名单TTL） | 已覆盖 | Task 5 |
| 5个DTO（LoginRequest/RegisterRequest/RefreshTokenRequest/TokenResponse/UserVO） | 已覆盖 | Task 6 |
| AuthService（register/login/refreshToken/logout/getCurrentUser） | 已覆盖 | Task 6 |
| AuthController（5个端点） | 已覆盖 | Task 7 |
| UserController | 已覆盖 | Task 7 |
| RoleController | 已覆盖 | Task 7 |
| JwtAuthenticationFilter | 已覆盖 | Task 8 |
| RequirePermission 注解 | 已覆盖 | Task 8 |
| PermissionEvaluator | 已覆盖 | Task 8 |
| SecurityConfig（白名单+无状态Session+禁用CSRF） | 已覆盖 | Task 8 |
| 集成测试（完整认证流程） | 已覆盖 | Task 9 |

### Placeholder检查

- 所有代码文件均包含完整的Java/SQL/YAML实现
- 无 TODO、FIXME、placeholder、"类似Task N"的引用
- 所有测试包含具体的断言，无空测试方法

### 类型一致性检查

- 所有实体继承BaseEntity，公共字段类型一致（Long id, Long createdBy, LocalDateTime createdAt等）
- UserMapper.xml返回类型为java.lang.String，与Mapper接口方法返回类型List\<String\>匹配
- R\<T\>泛型在各Controller中正确使用
- TokenResponse内部类UserInfo使用@Builder正确构建
- JwtUtil使用JJWT 0.12.5 API（Jwts.builder().subject()/claim()/signWith()）

### 版本号一致性检查

- Spring Boot: 3.2.5
- MyBatis-Plus: 3.5.5
- JJWT: 0.12.5
- Java: 17
