-- ============================================================
-- 计划8联调测试 Agent / Skill 初始化脚本
-- 适用数据库：PostgreSQL
-- 用途：
--   1. 创建一个已发布 Agent，用于 AGENT_REF 节点真实执行联调
--   2. 创建一个 Skill，用于 SKILL 节点真实执行联调
--   3. 创建一个 LLM_CALL 步骤，验证 SkillExecutor -> LlmGateway 链路
--
-- 执行前提：
--   1. V1/V2 基础迁移已完成
--   2. admin 用户存在
--   3. agent_groups 中存在“开发工具”或至少有一个分组
--   4. 已配置可用 LLM Provider，默认模型为 doubao-seed-2.0-code
--
-- 执行示例：
--   psql -h localhost -p 5432 -U postgres -d agent_platform -f "项目工程文档/项目功能介绍/项目相关配置说明/联调测试AgentSkill初始化.sql"
-- ============================================================

DO $$
DECLARE
    v_admin_id BIGINT;
    v_group_id BIGINT;
    v_agent_id BIGINT;
    v_skill_id BIGINT;
BEGIN
    SELECT id INTO v_admin_id
    FROM users
    WHERE username = 'admin'
      AND deleted = 0
    LIMIT 1;

    IF v_admin_id IS NULL THEN
        RAISE EXCEPTION 'admin user not found. Please run base seed data first.';
    END IF;

    SELECT id INTO v_group_id
    FROM agent_groups
    WHERE name = '开发工具'
      AND deleted = 0
    LIMIT 1;

    IF v_group_id IS NULL THEN
        SELECT id INTO v_group_id
        FROM agent_groups
        WHERE deleted = 0
        ORDER BY sort_order ASC, id ASC
        LIMIT 1;
    END IF;

    IF v_group_id IS NULL THEN
        INSERT INTO agent_groups (
            name,
            icon,
            description,
            sort_order,
            created_by,
            updated_by
        )
        VALUES (
            '联调测试',
            'tool',
            '计划8联调测试数据分组',
            99,
            v_admin_id,
            v_admin_id
        )
        RETURNING id INTO v_group_id;
    END IF;

    SELECT id INTO v_agent_id
    FROM agents
    WHERE name = '计划8联调测试Agent'
      AND deleted = 0
    LIMIT 1;

    IF v_agent_id IS NULL THEN
        INSERT INTO agents (
            name,
            description,
            avatar,
            group_id,
            status,
            config,
            parent_id,
            created_by,
            updated_by
        )
        VALUES (
            '计划8联调测试Agent',
            '用于验证 LangGraph sidecar 回调 Java AgentRouter 与 SkillExecutor 的联调测试Agent。',
            'robot',
            v_group_id,
            'PUBLISHED',
            '{}'::jsonb,
            NULL,
            v_admin_id,
            v_admin_id
        )
        RETURNING id INTO v_agent_id;
    ELSE
        UPDATE agents
        SET status = 'PUBLISHED',
            group_id = v_group_id,
            updated_by = v_admin_id,
            updated_at = NOW()
        WHERE id = v_agent_id;
    END IF;

    SELECT id INTO v_skill_id
    FROM skills
    WHERE agent_id = v_agent_id
      AND name = '计划8联调问答能力'
      AND deleted = 0
    LIMIT 1;

    IF v_skill_id IS NULL THEN
        INSERT INTO skills (
            agent_id,
            name,
            description,
            type,
            config,
            sort_order,
            created_by,
            updated_by
        )
        VALUES (
            v_agent_id,
            '计划8联调问答能力',
            '用于验证 SKILL 节点真实执行、LLM_CALL 步骤和执行监控输出展示。',
            'SEQUENCE',
            '{}'::jsonb,
            1,
            v_admin_id,
            v_admin_id
        )
        RETURNING id INTO v_skill_id;
    ELSE
        UPDATE skills
        SET description = '用于验证 SKILL 节点真实执行、LLM_CALL 步骤和执行监控输出展示。',
            type = 'SEQUENCE',
            sort_order = 1,
            updated_by = v_admin_id,
            updated_at = NOW()
        WHERE id = v_skill_id;
    END IF;

    UPDATE agents
    SET config = jsonb_build_object(
            'routingRules',
            jsonb_build_array(
                jsonb_build_object(
                    'keywords',
                    jsonb_build_array('联调', '测试', '计划8', 'sidecar', 'Agent', 'Skill'),
                    'skillIds',
                    jsonb_build_array(v_skill_id)
                )
            ),
            'model',
            'doubao-seed-2.0-code',
            'temperature',
            0.2
        ),
        updated_by = v_admin_id,
        updated_at = NOW()
    WHERE id = v_agent_id;

    IF NOT EXISTS (
        SELECT 1
        FROM skill_steps
        WHERE skill_id = v_skill_id
          AND step_order = 1
          AND deleted = 0
    ) THEN
        INSERT INTO skill_steps (
            skill_id,
            step_order,
            name,
            action,
            config,
            created_by,
            updated_by
        )
        VALUES (
            v_skill_id,
            1,
            '生成联调回复',
            'LLM_CALL',
            jsonb_build_object(
                'promptTemplate',
                '你是计划8联调测试Agent。请用不超过120字回答用户输入，并在回答末尾附上“[plan8-runtime-ok]”。用户输入：{{input}}',
                'model',
                'doubao-seed-2.0-code',
                'outputKey',
                'finalAnswer',
                'temperature',
                0.2
            ),
            v_admin_id,
            v_admin_id
        );
    ELSE
        UPDATE skill_steps
        SET name = '生成联调回复',
            action = 'LLM_CALL',
            config = jsonb_build_object(
                'promptTemplate',
                '你是计划8联调测试Agent。请用不超过120字回答用户输入，并在回答末尾附上“[plan8-runtime-ok]”。用户输入：{{input}}',
                'model',
                'doubao-seed-2.0-code',
                'outputKey',
                'finalAnswer',
                'temperature',
                0.2
            ),
            updated_by = v_admin_id,
            updated_at = NOW()
        WHERE skill_id = v_skill_id
          AND step_order = 1
          AND deleted = 0;
    END IF;

    RAISE NOTICE 'Plan8 test Agent initialized. agent_id=%, skill_id=%', v_agent_id, v_skill_id;
END $$;

SELECT
    a.id AS agent_id,
    a.name AS agent_name,
    a.status AS agent_status,
    s.id AS skill_id,
    s.name AS skill_name,
    ss.id AS step_id,
    ss.action AS step_action
FROM agents a
JOIN skills s ON s.agent_id = a.id AND s.deleted = 0
JOIN skill_steps ss ON ss.skill_id = s.id AND ss.deleted = 0
WHERE a.name = '计划8联调测试Agent'
  AND a.deleted = 0
ORDER BY s.sort_order, ss.step_order;
