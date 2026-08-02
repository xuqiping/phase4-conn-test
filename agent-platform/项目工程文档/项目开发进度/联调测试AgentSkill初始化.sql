-- ============================================================
-- 联调测试 Agent / Skill 初始化脚本
-- 适用场景：计划8 Agent / Skill 真实执行联调
-- 执行方式：连接 PostgreSQL agent_platform 数据库后手动执行本脚本
-- 幂等性：可重复执行；会复用同名 Agent / Skill，只补齐配置和步骤
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
        RAISE EXCEPTION 'admin user not found. Please create admin user before running this script.';
    END IF;

    SELECT id INTO v_group_id
    FROM agent_groups
    WHERE name IN ('通用助手', '内容创作')
      AND deleted = 0
    ORDER BY CASE name WHEN '通用助手' THEN 1 ELSE 2 END
    LIMIT 1;

    IF v_group_id IS NULL THEN
        INSERT INTO agent_groups (
            name,
            icon,
            description,
            sort_order,
            created_by,
            updated_by
        ) VALUES (
            '联调测试',
            'robot',
            '计划8联调测试分组',
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
            created_by,
            updated_by
        ) VALUES (
            '计划8联调测试Agent',
            '用于验证 LangGraph sidecar 回调 Java AgentRouter 与 SkillExecutor 的联调 Agent',
            'robot',
            v_group_id,
            'PUBLISHED',
            '{}'::jsonb,
            v_admin_id,
            v_admin_id
        )
        RETURNING id INTO v_agent_id;
    ELSE
        UPDATE agents
        SET status = 'PUBLISHED',
            group_id = v_group_id,
            description = '用于验证 LangGraph sidecar 回调 Java AgentRouter 与 SkillExecutor 的联调 Agent',
            updated_by = v_admin_id,
            updated_at = NOW()
        WHERE id = v_agent_id;
    END IF;

    SELECT id INTO v_skill_id
    FROM skills
    WHERE agent_id = v_agent_id
      AND name = '联调摘要生成'
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
        ) VALUES (
            v_agent_id,
            '联调摘要生成',
            '用于验证 SKILL 节点真实执行和 AGENT_REF 路由执行的 LLM_CALL 能力',
            'SEQUENCE',
            '{}'::jsonb,
            1,
            v_admin_id,
            v_admin_id
        )
        RETURNING id INTO v_skill_id;
    ELSE
        UPDATE skills
        SET description = '用于验证 SKILL 节点真实执行和 AGENT_REF 路由执行的 LLM_CALL 能力',
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
                    jsonb_build_array('联调', '测试', '摘要', '计划8'),
                    'skillIds',
                    jsonb_build_array(v_skill_id)
                )
            )
        ),
        updated_by = v_admin_id,
        updated_at = NOW()
    WHERE id = v_agent_id;

    DELETE FROM skill_steps
    WHERE skill_id = v_skill_id;

    INSERT INTO skill_steps (
        skill_id,
        step_order,
        name,
        action,
        config,
        created_by,
        updated_by
    ) VALUES (
        v_skill_id,
        1,
        '生成联调摘要',
        'LLM_CALL',
        jsonb_build_object(
            'promptTemplate',
            '请用不超过80个中文字总结以下联调输入，并明确返回“联调通过”或“需要检查”：{{input}}',
            'model',
            'doubao-seed-2.0-code',
            'outputKey',
            'summary',
            'temperature',
            0.2
        ),
        v_admin_id,
        v_admin_id
    );

    RAISE NOTICE 'Plan8 test Agent initialized. agentId=%, skillId=%', v_agent_id, v_skill_id;
END $$;

-- 可执行以下查询确认初始化结果：
-- SELECT id, name, status, config FROM agents WHERE name = '计划8联调测试Agent' AND deleted = 0;
-- SELECT id, agent_id, name, type FROM skills WHERE name = '联调摘要生成' AND deleted = 0;
-- SELECT id, skill_id, step_order, name, action, config FROM skill_steps WHERE skill_id IN (
--     SELECT id FROM skills WHERE name = '联调摘要生成' AND deleted = 0
-- );
