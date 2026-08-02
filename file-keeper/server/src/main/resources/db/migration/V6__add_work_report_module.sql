-- =============================================================================
-- V6__add_work_report_module.sql
-- 用途：新增工作汇报模块所需的全部业务表
-- 说明：包含工作记录、每日安排、报告模板、报告配置、推送目标、已生成报告、推送记录
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 工作记录表：用户每天填写的实际工作内容
-- -----------------------------------------------------------------------------
CREATE TABLE work_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),                 -- 所属用户
    log_date DATE NOT NULL,                                       -- 记录日期
    content TEXT NOT NULL,                                        -- 工作内容详情
    tags VARCHAR(255),                                            -- 标签，逗号分隔或 JSON
    source VARCHAR(32) DEFAULT 'MANUAL',                          -- 来源：MANUAL / IMPORT 等
    sort_order INT DEFAULT 0,                                     -- 同日期内排序
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_work_logs_user_date ON work_logs(user_id, log_date);     -- 按用户+日期查询日志
CREATE INDEX idx_work_logs_user_deleted ON work_logs(user_id, deleted);   -- 按用户查询有效日志

-- -----------------------------------------------------------------------------
-- 2. 每日安排表：用户对某一天的计划/待办事项（后续 V7 增强字段）
-- -----------------------------------------------------------------------------
CREATE TABLE work_plans (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),                 -- 所属用户
    plan_date DATE NOT NULL,                                      -- 计划日期
    content TEXT NOT NULL,                                        -- 计划内容
    priority VARCHAR(16) DEFAULT 'MEDIUM',                        -- 优先级：LOW / MEDIUM / HIGH
    completed BOOLEAN NOT NULL DEFAULT FALSE,                     -- 是否已完成
    sort_order INT DEFAULT 0,                                     -- 同日期内排序
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_work_plans_user_date ON work_plans(user_id, plan_date);  -- 按用户+日期查询计划

-- -----------------------------------------------------------------------------
-- 3. 报告模板表：日报/周报等模板，用户可自定义，也可使用系统默认模板
-- -----------------------------------------------------------------------------
CREATE TABLE report_templates (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT REFERENCES users(id),                          -- 所属用户，NULL 表示系统默认模板
    name VARCHAR(64) NOT NULL,                                    -- 模板名称
    type VARCHAR(16) NOT NULL,                                    -- 模板类型：DAILY / WEEKLY 等
    content TEXT NOT NULL,                                        -- 模板内容，含 {{logs}} / {{plans}} 等占位符
    is_default BOOLEAN NOT NULL DEFAULT FALSE,                    -- 是否为系统默认模板
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_report_templates_type ON report_templates(type, is_default);  -- 按类型查询默认模板

-- -----------------------------------------------------------------------------
-- 4. 报告规则配置表：用户配置的报告生成规则（周期、模板、AI、推送等）
-- -----------------------------------------------------------------------------
CREATE TABLE report_configs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),                 -- 所属用户
    name VARCHAR(64) NOT NULL,                                    -- 配置名称
    report_type VARCHAR(16) NOT NULL,                             -- 报告类型：DAILY / WEEKLY
    template_id BIGINT NOT NULL REFERENCES report_templates(id),  -- 使用的模板
    cron_expression VARCHAR(64) NOT NULL,                         -- Cron 表达式，控制生成周期
    timezone VARCHAR(64) DEFAULT 'Asia/Shanghai',                 -- 报告生成时区
    enabled BOOLEAN NOT NULL DEFAULT TRUE,                        -- 是否启用该规则
    ai_enabled BOOLEAN NOT NULL DEFAULT TRUE,                     -- 是否启用 AI 总结
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_report_configs_user ON report_configs(user_id, enabled, deleted);  -- 查询用户启用的配置

-- -----------------------------------------------------------------------------
-- 5. 推送目标配置表：报告生成后推送到哪里（如飞书机器人）
-- -----------------------------------------------------------------------------
CREATE TABLE report_push_targets (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    config_id BIGINT NOT NULL REFERENCES report_configs(id),      -- 关联的报告配置
    platform VARCHAR(32) NOT NULL,                                -- 推送平台：feishu / wecom / dingtalk 等
    target_type VARCHAR(32) NOT NULL,                             -- 目标类型：GROUP / USER 等
    target_id VARCHAR(255) NOT NULL,                              -- 目标 ID（如群机器人 webhook 路径）
    credential TEXT,                                              -- 凭证信息（加密存储，如 webhook key）
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_report_push_targets_config ON report_push_targets(config_id, deleted);  -- 查询某配置下的推送目标

-- -----------------------------------------------------------------------------
-- 6. 已生成报告表：每次根据规则生成的报告快照
-- -----------------------------------------------------------------------------
CREATE TABLE work_reports (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),                 -- 所属用户
    config_id BIGINT NOT NULL REFERENCES report_configs(id),      -- 来源配置
    report_type VARCHAR(16) NOT NULL,                             -- 报告类型
    title VARCHAR(255) NOT NULL,                                  -- 报告标题
    content TEXT NOT NULL,                                        -- 报告正文（Markdown）
    generated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 生成时间
    status VARCHAR(32) NOT NULL DEFAULT 'GENERATED',              -- 状态：GENERATED / PUSHED / FAILED
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_work_reports_user ON work_reports(user_id, generated_at DESC);  -- 查询用户报告历史
CREATE INDEX idx_work_reports_status ON work_reports(status);                     -- 按状态筛查待推送/失败报告

-- -----------------------------------------------------------------------------
-- 7. 推送记录表：每次向某个目标推送报告的执行结果
-- -----------------------------------------------------------------------------
CREATE TABLE push_deliveries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    report_id BIGINT NOT NULL REFERENCES work_reports(id),        -- 关联的报告
    target_id BIGINT NOT NULL REFERENCES report_push_targets(id), -- 关联的推送目标
    status VARCHAR(32) NOT NULL,                                  -- 状态：PENDING / SUCCESS / FAILED
    response TEXT,                                                -- 平台返回结果或错误信息
    tried_count INT DEFAULT 0,                                    -- 已重试次数
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_push_deliveries_report ON push_deliveries(report_id);  -- 查询某报告的全部推送记录

-- -----------------------------------------------------------------------------
-- 8. 初始化默认报告模板：系统预置常用日报/周报模板
-- -----------------------------------------------------------------------------
INSERT INTO report_templates (name, type, content, is_default, created_at, updated_at)
VALUES
('技术开发日报', 'DAILY', '## 今日工作
{{logs}}

## 遇到的问题
{{issues}}

## 明日计划
{{plans}}', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('运营日报', 'DAILY', '## 今日运营工作
{{logs}}

## 数据亮点
{{highlights}}

## 明日待办
{{plans}}', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('管理周报', 'WEEKLY', '# 本周总结
{{ai_summary}}

## 关键进展
{{logs}}

## 下周计划
{{plans}}', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
