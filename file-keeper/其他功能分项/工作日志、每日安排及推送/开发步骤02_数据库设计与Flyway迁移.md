# 开发步骤 02：数据库设计与 Flyway 迁移

> 本步骤完成 `work-report` 模块所需的全部业务表设计、Flyway 迁移脚本和后端实体类。

> **状态：✅ 已完成**（2026-06-21）

---

## 1. 目标

- 设计完整的数据库表结构
- 编写兼容 PostgreSQL 和 H2 的 Flyway 迁移脚本
- 创建对应的 MyBatis-Plus 实体类
- 建立基础 Repository/Mapper 层

---

## 2. 前置依赖

- [`开发步骤01_模块注册与授权接入.md`](开发步骤01_模块注册与授权接入.md) 已完成
- moduleCode `work-report` 已注册

---

## 3. 涉及文件

| 文件 | 路径 |
|---|---|
| Flyway 迁移脚本 | `file-keeper/server/src/main/resources/db/migration/V{x}__add_work_report_module.sql` |
| 实体类 | `file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/*.java` |
| Mapper | `file-keeper/server/src/main/java/com/superprogrammer/workreport/mapper/*.java` |
| Repository | `file-keeper/server/src/main/java/com/superprogrammer/workreport/repository/*.java` |

---

## 4. 详细任务

### 4.1 创建 Flyway 迁移脚本

新建 `file-keeper/server/src/main/resources/db/migration/V{x}__add_work_report_module.sql`，内容如下：

```sql
-- ============================================
-- work-report 模块业务表
-- ============================================

-- 1. 工作记录表
CREATE TABLE work_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    log_date DATE NOT NULL,
    content TEXT NOT NULL,
    tags VARCHAR(255),
    source VARCHAR(32) DEFAULT 'MANUAL',
    sort_order INT DEFAULT 0,
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_work_logs_user_date ON work_logs(user_id, log_date);
CREATE INDEX idx_work_logs_user_deleted ON work_logs(user_id, deleted);

-- 2. 每日安排表
CREATE TABLE work_plans (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    plan_date DATE NOT NULL,
    content TEXT NOT NULL,
    priority VARCHAR(16) DEFAULT 'MEDIUM',
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT DEFAULT 0,
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_work_plans_user_date ON work_plans(user_id, plan_date);

-- 3. 报告模板表
CREATE TABLE report_templates (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT REFERENCES users(id),
    name VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_report_templates_type ON report_templates(type, is_default);

-- 4. 报告规则配置表
CREATE TABLE report_configs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    name VARCHAR(64) NOT NULL,
    report_type VARCHAR(16) NOT NULL,
    template_id BIGINT NOT NULL REFERENCES report_templates(id),
    cron_expression VARCHAR(64) NOT NULL,
    timezone VARCHAR(64) DEFAULT 'Asia/Shanghai',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ai_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_report_configs_user ON report_configs(user_id, enabled, deleted);

-- 5. 推送目标配置表
CREATE TABLE report_push_targets (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    config_id BIGINT NOT NULL REFERENCES report_configs(id),
    platform VARCHAR(32) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(255) NOT NULL,
    credential TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_report_push_targets_config ON report_push_targets(config_id, deleted);

-- 6. 已生成报告表
CREATE TABLE work_reports (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    config_id BIGINT NOT NULL REFERENCES report_configs(id),
    report_type VARCHAR(16) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    generated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    status VARCHAR(32) NOT NULL DEFAULT 'GENERATED',
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_work_reports_user ON work_reports(user_id, generated_at DESC);
CREATE INDEX idx_work_reports_status ON work_reports(status);

-- 7. 推送记录表
CREATE TABLE push_deliveries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    report_id BIGINT NOT NULL REFERENCES work_reports(id),
    target_id BIGINT NOT NULL REFERENCES report_push_targets(id),
    status VARCHAR(32) NOT NULL,
    response TEXT,
    tried_count INT DEFAULT 0,
    created_by VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_push_deliveries_report ON push_deliveries(report_id);
```

### 4.2 创建实体类

在 `file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/` 下创建：

- `WorkLog.java`
- `WorkPlan.java`
- `ReportTemplate.java`
- `ReportConfig.java`
- `ReportPushTarget.java`
- `WorkReport.java`
- `PushDelivery.java`

示例 `WorkLog.java`：

```java
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("work_logs")
public class WorkLog extends BaseEntity {

    @TableField("user_id")
    private Long userId;

    @TableField("log_date")
    private LocalDate logDate;

    @TableField("content")
    private String content;

    @TableField("tags")
    private String tags;

    @TableField("source")
    private String source;

    @TableField("sort_order")
    private Integer sortOrder;
}
```

### 4.3 创建 Mapper

在 `file-keeper/server/src/main/java/com/superprogrammer/workreport/mapper/` 下创建对应 Mapper 接口，继承 `BaseMapper<T>`。

### 4.4 创建 Repository（可选）

如果项目使用 Repository 模式，在 `file-keeper/server/src/main/java/com/superprogrammer/workreport/repository/` 下创建对应 Repository。

### 4.5 初始化默认模板数据（可选）

可在 Flyway 脚本末尾插入 3 套默认模板：

```sql
INSERT INTO report_templates (name, type, content, is_default, created_at, updated_at)
VALUES
('技术开发日报', 'DAILY', '## 今日工作
{{logs}}

## 遇到的问题
{{issues}}

## 明日计划
{{plans}}', TRUE, NOW(), NOW()),
('运营日报', 'DAILY', '## 今日运营工作
{{logs}}

## 数据亮点
{{highlights}}

## 明日待办
{{plans}}', FALSE, NOW(), NOW()),
('管理周报', 'WEEKLY', '# 本周总结
{{ai_summary}}

## 关键进展
{{logs}}

## 下周计划
{{plans}}', FALSE, NOW(), NOW());
```

---

## 5. 验收标准

- [x] Flyway 迁移脚本在 PostgreSQL 执行成功
- [x] Flyway 迁移脚本在 H2 测试库执行成功
- [x] 所有实体类继承 `BaseEntity`
- [x] 所有实体类字段与数据库表一致
- [x] Mapper 接口可正常注入使用（项目实际使用 JdbcTemplate Repository）
- [x] 默认模板数据已插入
- [x] `mvn -f file-keeper/server/pom.xml test` 通过

> 验证结果：H2 手动验证 7 表 + 3 模板成功；`mvn test -Dtest=FlywayMigrationTest` 3 个测试通过；全量 `mvn test` 53 个测试通过。

---

## 6. 验证命令

```bash
# 后端编译和测试
mvn -f "file-keeper/server/pom.xml" clean test

# 查看 Flyway 执行状态
mvn -f "file-keeper/server/pom.xml" flyway:info
```

---

## 7. 预计工时

**2 天**

---

## 8. 风险与注意事项

| 风险 | 说明 |
|---|---|
| H2 与 PostgreSQL 语法差异 | `GENERATED ALWAYS AS IDENTITY` 在 H2 中支持，但需测试确认 |
| 字段类型不兼容 | `TEXT`、`TIMESTAMP WITH TIME ZONE` 需确认 H2 兼容模式支持 |
| 外键约束导致测试数据清理困难 | 测试时按正确顺序清理表数据或使用 `@Transactional` |
| 索引过多影响写入 | MVP 阶段索引已按常用查询设计，后续可监控慢查询 |

---

## 9. 下一Step

完成本步骤后，继续执行 [`开发步骤03_后端业务接口开发.md`](开发步骤03_后端业务接口开发.md)。

---

*文档版本：v1.0*  
*编写日期：2026-06-21*
