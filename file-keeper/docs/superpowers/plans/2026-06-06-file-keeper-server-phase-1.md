# File Keeper Server Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first File Keeper commercial authorization server slice under `file-keeper/server/` with a runnable Spring Boot project, H2-backed migration tests, core auth tables, base entities, and super admin bootstrap.

**Architecture:** `file-keeper/server/` is an independent Spring Boot 3.2.5 Maven project using package `com.superprogrammer`. Development and production configuration targets PostgreSQL, while automated tests use H2 in PostgreSQL compatibility mode. Phase 1 only builds the data/model/bootstrap foundation; user registration, JWT login, entitlement APIs, Redis token revocation, admin web, and desktop integration remain out of scope.

**Tech Stack:** Java 17, Spring Boot 3.2.5, Maven, MyBatis-Plus 3.5.5, Flyway, PostgreSQL, H2 for tests, Spring Security BCrypt.

---

## File Structure

Create these files:

- `file-keeper/server/pom.xml` — Maven project definition and dependencies.
- `file-keeper/server/src/main/java/com/superprogrammer/FileKeeperServerApplication.java` — Spring Boot entrypoint.
- `file-keeper/server/src/main/java/com/superprogrammer/common/R.java` — unified API response wrapper.
- `file-keeper/server/src/main/java/com/superprogrammer/common/PageResult.java` — pagination response wrapper.
- `file-keeper/server/src/main/java/com/superprogrammer/common/ErrorCode.java` — shared error codes.
- `file-keeper/server/src/main/java/com/superprogrammer/common/BusinessException.java` — shared business exception.
- `file-keeper/server/src/main/java/com/superprogrammer/common/BaseEntity.java` — MyBatis-Plus base entity.
- `file-keeper/server/src/main/java/com/superprogrammer/config/MyBatisPlusConfig.java` — MyBatis-Plus pagination config.
- `file-keeper/server/src/main/java/com/superprogrammer/config/MetaObjectHandlerConfig.java` — audit timestamp/default fill config.
- `file-keeper/server/src/main/java/com/superprogrammer/config/PasswordEncoderConfig.java` — BCrypt password encoder bean.
- `file-keeper/server/src/main/java/com/superprogrammer/user/entity/User.java` — account entity.
- `file-keeper/server/src/main/java/com/superprogrammer/user/entity/UserModuleEntitlement.java` — module authorization entity.
- `file-keeper/server/src/main/java/com/superprogrammer/device/entity/UserDevice.java` — account-bound device entity.
- `file-keeper/server/src/main/java/com/superprogrammer/device/entity/AnonymousDeviceTrial.java` — anonymous device trial entity.
- `file-keeper/server/src/main/java/com/superprogrammer/settings/entity/SystemSetting.java` — system setting entity.
- `file-keeper/server/src/main/java/com/superprogrammer/audit/entity/AdminAuditLog.java` — admin audit entity.
- `file-keeper/server/src/main/java/com/superprogrammer/bootstrap/SuperAdminInitializer.java` — startup bootstrap for the first super admin.
- `file-keeper/server/src/main/resources/application.yml` — default app configuration.
- `file-keeper/server/src/main/resources/application-dev.yml` — local PostgreSQL development configuration.
- `file-keeper/server/src/main/resources/db/migration/V1__create_auth_schema.sql` — Flyway schema migration.
- `file-keeper/server/src/test/resources/application-test.yml` — H2 test profile.
- `file-keeper/server/src/test/java/com/superprogrammer/db/FlywayMigrationTest.java` — schema migration test.
- `file-keeper/server/src/test/java/com/superprogrammer/bootstrap/SuperAdminInitializerTest.java` — super admin bootstrap test.

No existing source files are modified in this phase. The previously approved design spec remains at `docs/superpowers/specs/2026-06-06-file-keeper-server-phase-1-design.md`.

---

### Task 1: Create Spring Boot server skeleton

**Files:**
- Create: `file-keeper/server/pom.xml`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/FileKeeperServerApplication.java`
- Create: `file-keeper/server/src/main/resources/application.yml`
- Create: `file-keeper/server/src/main/resources/application-dev.yml`
- Create: `file-keeper/server/src/test/resources/application-test.yml`

- [ ] **Step 1: Create Maven project file**

Create `file-keeper/server/pom.xml`:

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
    <artifactId>file-keeper-server</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>file-keeper-server</name>
    <description>File Keeper commercial authorization server</description>

    <properties>
        <java.version>17</java.version>
        <mybatis-plus.version>3.5.5</mybatis-plus.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>${mybatis-plus.version}</version>
        </dependency>

        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

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

- [ ] **Step 2: Create Spring Boot entrypoint**

Create `file-keeper/server/src/main/java/com/superprogrammer/FileKeeperServerApplication.java`:

```java
package com.superprogrammer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FileKeeperServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileKeeperServerApplication.class, args);
    }
}
```

- [ ] **Step 3: Create default application configuration**

Create `file-keeper/server/src/main/resources/application.yml`:

```yaml
server:
  port: 8088

spring:
  application:
    name: file-keeper-server
  profiles:
    active: dev
  datasource:
    driver-class-name: org.postgresql.Driver
    url: ${FILE_KEEPER_DB_URL:jdbc:postgresql://localhost:5432/file_keeper}
    username: ${FILE_KEEPER_DB_USERNAME:postgres}
    password: ${FILE_KEEPER_DB_PASSWORD:postgres}
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    redis:
      host: ${FILE_KEEPER_REDIS_HOST:localhost}
      port: ${FILE_KEEPER_REDIS_PORT:6379}
      password: ${FILE_KEEPER_REDIS_PASSWORD:}

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0

file-keeper:
  bootstrap:
    super-admin:
      email: ${FILE_KEEPER_SUPER_ADMIN_EMAIL:}
      phone: ${FILE_KEEPER_SUPER_ADMIN_PHONE:}
      password: ${FILE_KEEPER_SUPER_ADMIN_PASSWORD:}
```

- [ ] **Step 4: Create development profile configuration**

Create `file-keeper/server/src/main/resources/application-dev.yml`:

```yaml
spring:
  datasource:
    driver-class-name: org.postgresql.Driver
    url: ${FILE_KEEPER_DB_URL:jdbc:postgresql://localhost:5432/file_keeper}
    username: ${FILE_KEEPER_DB_USERNAME:postgres}
    password: ${FILE_KEEPER_DB_PASSWORD:postgres}
```

- [ ] **Step 5: Create H2 test profile configuration**

Create `file-keeper/server/src/test/resources/application-test.yml`:

```yaml
spring:
  datasource:
    driver-class-name: org.h2.Driver
    url: jdbc:h2:mem:file_keeper_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1
    username: sa
    password:
  flyway:
    enabled: true
    locations: classpath:db/migration
  main:
    banner-mode: off

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0

file-keeper:
  bootstrap:
    super-admin:
      email:
      phone:
      password:
```

- [ ] **Step 6: Run Maven test to verify skeleton compiles**

Run:

```bash
mvn -f "file-keeper/server/pom.xml" test
```

Expected: build succeeds with no tests or with `Tests run: 0` because test classes have not been added yet.

- [ ] **Step 7: Checkpoint**

Run:

```bash
git status --short
```

Expected: new files under `file-keeper/server/` are visible. Do not commit unless the user explicitly asks for a commit.

---

### Task 2: Add Flyway migration test and schema

**Files:**
- Create: `file-keeper/server/src/test/java/com/superprogrammer/db/FlywayMigrationTest.java`
- Create: `file-keeper/server/src/main/resources/db/migration/V1__create_auth_schema.sql`

- [ ] **Step 1: Write failing Flyway migration test**

Create `file-keeper/server/src/test/java/com/superprogrammer/db/FlywayMigrationTest.java`:

```java
package com.superprogrammer.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsCoreAuthorizationTables() {
        assertTableExists("users");
        assertTableExists("user_module_entitlements");
        assertTableExists("user_devices");
        assertTableExists("anonymous_device_trials");
        assertTableExists("system_settings");
        assertTableExists("admin_audit_logs");
    }

    private void assertTableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where lower(table_schema) = 'public' and lower(table_name) = ?",
                Integer.class,
                tableName
        );
        assertEquals(1, count, tableName + " table should exist");
    }
}
```

- [ ] **Step 2: Run the failing migration test**

Run:

```bash
mvn -f "file-keeper/server/pom.xml" test -Dtest=FlywayMigrationTest
```

Expected: test fails because `users` and the other core tables do not exist yet. The failure should include an assertion like `users table should exist ==> expected: <1> but was: <0>`.

- [ ] **Step 3: Create Flyway migration**

Create `file-keeper/server/src/main/resources/db/migration/V1__create_auth_schema.sql`:

```sql
CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email VARCHAR(120),
    phone VARCHAR(32),
    password_hash VARCHAR(120) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'user',
    status VARCHAR(32) NOT NULL DEFAULT 'pending_verification',
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    device_limit INT NOT NULL DEFAULT 1,
    offline_cache_minutes INT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_phone UNIQUE (phone),
    CONSTRAINT ck_users_contact CHECK (email IS NOT NULL OR phone IS NOT NULL),
    CONSTRAINT ck_users_role CHECK (role IN ('super_admin', 'user')),
    CONSTRAINT ck_users_status CHECK (status IN ('pending_verification', 'pending_review', 'active', 'disabled'))
);

CREATE TABLE user_module_entitlements (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    module_code VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_user_module_entitlements_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_user_module_entitlements_user_module UNIQUE (user_id, module_code),
    CONSTRAINT ck_user_module_entitlements_module CHECK (module_code IN ('files', 'processes', 'clipboard'))
);

CREATE INDEX idx_user_module_entitlements_user_id ON user_module_entitlements(user_id);

CREATE TABLE user_devices (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_id VARCHAR(80) NOT NULL,
    fingerprint_hash VARCHAR(128) NOT NULL,
    device_name VARCHAR(120),
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    last_seen_at TIMESTAMP WITH TIME ZONE,
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_user_devices_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_user_devices_user_device UNIQUE (user_id, device_id),
    CONSTRAINT ck_user_devices_status CHECK (status IN ('active', 'disabled'))
);

CREATE INDEX idx_user_devices_user_id ON user_devices(user_id);
CREATE INDEX idx_user_devices_device_id ON user_devices(device_id);

CREATE TABLE anonymous_device_trials (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id VARCHAR(80) NOT NULL,
    fingerprint_hash VARCHAR(128) NOT NULL,
    device_name VARCHAR(120),
    trial_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    trial_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    free_module_code VARCHAR(32),
    free_module_selected_at TIMESTAMP WITH TIME ZONE,
    last_free_module_changed_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_anonymous_device_trials_device UNIQUE (device_id),
    CONSTRAINT ck_anonymous_device_trials_module CHECK (free_module_code IS NULL OR free_module_code IN ('files', 'processes', 'clipboard')),
    CONSTRAINT ck_anonymous_device_trials_status CHECK (status IN ('active', 'disabled'))
);

CREATE INDEX idx_anonymous_device_trials_device_id ON anonymous_device_trials(device_id);

CREATE TABLE system_settings (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    setting_key VARCHAR(120) NOT NULL,
    setting_value VARCHAR(500),
    description VARCHAR(500),
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_system_settings_key UNIQUE (setting_key)
);

CREATE TABLE admin_audit_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admin_user_id BIGINT,
    action VARCHAR(120) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id VARCHAR(120),
    detail TEXT,
    ip_address VARCHAR(64),
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_admin_audit_logs_admin_user FOREIGN KEY (admin_user_id) REFERENCES users(id)
);

CREATE INDEX idx_admin_audit_logs_admin_user_id ON admin_audit_logs(admin_user_id);
CREATE INDEX idx_admin_audit_logs_target ON admin_audit_logs(target_type, target_id);
```

- [ ] **Step 4: Run migration test to verify green**

Run:

```bash
mvn -f "file-keeper/server/pom.xml" test -Dtest=FlywayMigrationTest
```

Expected: `FlywayMigrationTest` passes.

- [ ] **Step 5: Checkpoint**

Run:

```bash
git status --short
```

Expected: `FlywayMigrationTest.java` and `V1__create_auth_schema.sql` are present as new files. Do not commit unless the user explicitly asks for a commit.

---

### Task 3: Add common infrastructure and entities

**Files:**
- Create: `file-keeper/server/src/main/java/com/superprogrammer/common/R.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/common/PageResult.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/common/ErrorCode.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/common/BusinessException.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/common/BaseEntity.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/config/MyBatisPlusConfig.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/config/MetaObjectHandlerConfig.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/config/PasswordEncoderConfig.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/entity/User.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/user/entity/UserModuleEntitlement.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/device/entity/UserDevice.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/device/entity/AnonymousDeviceTrial.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/settings/entity/SystemSetting.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/audit/entity/AdminAuditLog.java`

- [ ] **Step 1: Add unified response wrapper**

Create `file-keeper/server/src/main/java/com/superprogrammer/common/R.java`:

```java
package com.superprogrammer.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class R<T> {

    private int code;
    private String msg;
    private T data;

    public static <T> R<T> ok() {
        return new R<>(200, "success", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(200, "success", data);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return new R<>(errorCode.getCode(), errorCode.getMsg(), null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }
}
```

- [ ] **Step 2: Add pagination wrapper**

Create `file-keeper/server/src/main/java/com/superprogrammer/common/PageResult.java`:

```java
package com.superprogrammer.common;

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
}
```

- [ ] **Step 3: Add error codes**

Create `file-keeper/server/src/main/java/com/superprogrammer/common/ErrorCode.java`:

```java
package com.superprogrammer.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(200, "success"),
    BAD_REQUEST(400, "参数错误"),
    UNAUTHORIZED(401, "未认证"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突"),
    UNPROCESSABLE(422, "业务规则违反"),
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String msg;
}
```

- [ ] **Step 4: Add business exception**

Create `file-keeper/server/src/main/java/com/superprogrammer/common/BusinessException.java`:

```java
package com.superprogrammer.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String msg) {
        super(msg);
        this.code = errorCode.getCode();
    }

    public BusinessException(int code, String msg) {
        super(msg);
        this.code = code;
    }
}
```

- [ ] **Step 5: Add MyBatis-Plus base entity**

Create `file-keeper/server/src/main/java/com/superprogrammer/common/BaseEntity.java`:

```java
package com.superprogrammer.common;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public abstract class BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
```

- [ ] **Step 6: Add MyBatis-Plus pagination config**

Create `file-keeper/server/src/main/java/com/superprogrammer/config/MyBatisPlusConfig.java`:

```java
package com.superprogrammer.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
```

- [ ] **Step 7: Add MyBatis-Plus meta object handler**

Create `file-keeper/server/src/main/java/com/superprogrammer/config/MetaObjectHandlerConfig.java`:

```java
package com.superprogrammer.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class MetaObjectHandlerConfig implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createdBy", Long.class, 0L);
        this.strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, OffsetDateTime.now());
        this.strictInsertFill(metaObject, "updatedBy", Long.class, 0L);
        this.strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
        this.strictInsertFill(metaObject, "deleted", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedBy", Long.class, 0L);
        this.strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
    }
}
```

- [ ] **Step 8: Add password encoder bean**

Create `file-keeper/server/src/main/java/com/superprogrammer/config/PasswordEncoderConfig.java`:

```java
package com.superprogrammer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

- [ ] **Step 9: Add User entity**

Create `file-keeper/server/src/main/java/com/superprogrammer/user/entity/User.java`:

```java
package com.superprogrammer.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("users")
public class User extends BaseEntity {

    private String email;
    private String phone;
    private String passwordHash;
    private String role;
    private String status;
    private Boolean emailVerified;
    private Boolean phoneVerified;
    private Integer deviceLimit;
    private Integer offlineCacheMinutes;
}
```

- [ ] **Step 10: Add UserModuleEntitlement entity**

Create `file-keeper/server/src/main/java/com/superprogrammer/user/entity/UserModuleEntitlement.java`:

```java
package com.superprogrammer.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_module_entitlements")
public class UserModuleEntitlement extends BaseEntity {

    private Long userId;
    private String moduleCode;
    private Boolean enabled;
    private OffsetDateTime expiresAt;
}
```

- [ ] **Step 11: Add UserDevice entity**

Create `file-keeper/server/src/main/java/com/superprogrammer/device/entity/UserDevice.java`:

```java
package com.superprogrammer.device.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_devices")
public class UserDevice extends BaseEntity {

    private Long userId;
    private String deviceId;
    private String fingerprintHash;
    private String deviceName;
    private String status;
    private OffsetDateTime lastSeenAt;
}
```

- [ ] **Step 12: Add AnonymousDeviceTrial entity**

Create `file-keeper/server/src/main/java/com/superprogrammer/device/entity/AnonymousDeviceTrial.java`:

```java
package com.superprogrammer.device.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("anonymous_device_trials")
public class AnonymousDeviceTrial extends BaseEntity {

    private String deviceId;
    private String fingerprintHash;
    private String deviceName;
    private OffsetDateTime trialStartedAt;
    private OffsetDateTime trialExpiresAt;
    private String freeModuleCode;
    private OffsetDateTime freeModuleSelectedAt;
    private OffsetDateTime lastFreeModuleChangedAt;
    private String status;
}
```

- [ ] **Step 13: Add SystemSetting entity**

Create `file-keeper/server/src/main/java/com/superprogrammer/settings/entity/SystemSetting.java`:

```java
package com.superprogrammer.settings.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("system_settings")
public class SystemSetting extends BaseEntity {

    private String settingKey;
    private String settingValue;
    private String description;
}
```

- [ ] **Step 14: Add AdminAuditLog entity**

Create `file-keeper/server/src/main/java/com/superprogrammer/audit/entity/AdminAuditLog.java`:

```java
package com.superprogrammer.audit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("admin_audit_logs")
public class AdminAuditLog extends BaseEntity {

    private Long adminUserId;
    private String action;
    private String targetType;
    private String targetId;
    private String detail;
    private String ipAddress;
}
```

- [ ] **Step 15: Run server tests to verify compile and migration still pass**

Run:

```bash
mvn -f "file-keeper/server/pom.xml" test
```

Expected: `FlywayMigrationTest` passes and the project compiles with the new common classes and entities.

- [ ] **Step 16: Checkpoint**

Run:

```bash
git status --short
```

Expected: common/config/entity files are visible as new files. Do not commit unless the user explicitly asks for a commit.

---

### Task 4: Add super admin bootstrap red-green test

**Files:**
- Create: `file-keeper/server/src/test/java/com/superprogrammer/bootstrap/SuperAdminInitializerTest.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/bootstrap/SuperAdminInitializer.java`

- [ ] **Step 1: Write failing super admin bootstrap test**

Create `file-keeper/server/src/test/java/com/superprogrammer/bootstrap/SuperAdminInitializerTest.java`:

```java
package com.superprogrammer.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:super_admin_initializer;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "file-keeper.bootstrap.super-admin.email=admin@example.com",
        "file-keeper.bootstrap.super-admin.password=AdminPass123!"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SuperAdminInitializerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsSuperAdminWhenBootstrapPropertiesExist() {
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "select email, password_hash, role, status, email_verified from users where role = 'super_admin'"
        );

        assertEquals("admin@example.com", user.get("email"));
        assertEquals("super_admin", user.get("role"));
        assertEquals("active", user.get("status"));
        assertEquals(Boolean.TRUE, user.get("email_verified"));
        assertNotNull(user.get("password_hash"));
        assertNotEquals("AdminPass123!", user.get("password_hash"));
    }
}
```

- [ ] **Step 2: Run failing super admin bootstrap test**

Run:

```bash
mvn -f "file-keeper/server/pom.xml" test -Dtest=SuperAdminInitializerTest
```

Expected: test fails because no `role = 'super_admin'` row is created. The failure should be an empty result from `queryForMap` or an assertion failure caused by the missing row.

- [ ] **Step 3: Implement SuperAdminInitializer**

Create `file-keeper/server/src/main/java/com/superprogrammer/bootstrap/SuperAdminInitializer.java`:

```java
package com.superprogrammer.bootstrap;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SuperAdminInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    @Value("${file-keeper.bootstrap.super-admin.email:}")
    private String email;

    @Value("${file-keeper.bootstrap.super-admin.phone:}")
    private String phone;

    @Value("${file-keeper.bootstrap.super-admin.password:}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        String normalizedEmail = normalize(email);
        String normalizedPhone = normalize(phone);

        if (!StringUtils.hasText(password) || (normalizedEmail == null && normalizedPhone == null)) {
            return;
        }

        if (superAdminExists(normalizedEmail, normalizedPhone)) {
            return;
        }

        jdbcTemplate.update(
                "insert into users (email, phone, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, 'super_admin', 'active', ?, ?, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                normalizedEmail,
                normalizedPhone,
                passwordEncoder.encode(password),
                normalizedEmail != null,
                normalizedPhone != null
        );
    }

    private boolean superAdminExists(String normalizedEmail, String normalizedPhone) {
        StringBuilder sql = new StringBuilder("select count(*) from users where role = 'super_admin' and deleted = 0 and (");
        List<Object> params = new ArrayList<>();

        if (normalizedEmail != null) {
            sql.append("email = ?");
            params.add(normalizedEmail);
        }

        if (normalizedPhone != null) {
            if (!params.isEmpty()) {
                sql.append(" or ");
            }
            sql.append("phone = ?");
            params.add(normalizedPhone);
        }

        sql.append(")");
        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count != null && count > 0;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
```

- [ ] **Step 4: Run super admin bootstrap test to verify green**

Run:

```bash
mvn -f "file-keeper/server/pom.xml" test -Dtest=SuperAdminInitializerTest
```

Expected: `SuperAdminInitializerTest` passes.

- [ ] **Step 5: Run migration test again**

Run:

```bash
mvn -f "file-keeper/server/pom.xml" test -Dtest=FlywayMigrationTest
```

Expected: `FlywayMigrationTest` still passes. `SuperAdminInitializer` should not create a row in this test because bootstrap password is empty in the test profile.

- [ ] **Step 6: Checkpoint**

Run:

```bash
git status --short
```

Expected: `SuperAdminInitializer.java` and `SuperAdminInitializerTest.java` are visible as new files. Do not commit unless the user explicitly asks for a commit.

---

### Task 5: Final phase 1 verification

**Files:**
- Verify all files created in Tasks 1-4.

- [ ] **Step 1: Run the full phase 1 server test suite**

Run:

```bash
mvn -f "file-keeper/server/pom.xml" test
```

Expected: all server tests pass, including `FlywayMigrationTest` and `SuperAdminInitializerTest`.

- [ ] **Step 2: Confirm no unexpected files are staged**

Run:

```bash
git status --short
```

Expected: the working tree shows the new `file-keeper/server/` files, the design spec, and this implementation plan as uncommitted changes. Nothing should be staged unless the user explicitly asked for staging or committing.

- [ ] **Step 3: Summarize verification evidence**

Report the exact Maven command and result. If the build fails, report the failing test or compiler error and do not claim phase 1 is complete.

- [ ] **Step 4: Ask before committing**

If the user wants the phase committed, use a new commit with a terse Chinese Conventional Commit message, for example:

```bash
git add "docs/superpowers/specs/2026-06-06-file-keeper-server-phase-1-design.md" \
        "docs/superpowers/plans/2026-06-06-file-keeper-server-phase-1.md" \
        "file-keeper/server"
git commit -m "$(cat <<'EOF'
feat: 初始化商业授权服务端基础工程
EOF
)"
```

Do not run this commit command unless the user explicitly asks for a commit.

---

## Self-Review

- Spec coverage: this plan covers the server Maven project, Spring Boot entrypoint, PostgreSQL/H2 configuration, Flyway migration, six core tables, common types, base entity, config classes, six data entities, super admin bootstrap, H2 migration test, and super admin bootstrap test.
- Scope control: registration, verification, login, JWT, Redis token revocation, entitlement APIs, admin web, and desktop integration are intentionally excluded because they belong to later phases.
- Placeholder scan: no step relies on unspecified code; each created source file includes concrete content.
- Type consistency: table names, column names, Java field names, property names, package names, and test assertions match across SQL, entities, configuration, and bootstrap code.
