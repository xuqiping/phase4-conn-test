-- V159：存量 BACKSTOP 差额回填进成员 used_points（7x-2 口径汇合，计划A A3）
-- =====================================================================
-- 背景：BACKSTOP（组池不足·组长个人兜底差额）此前不计 member.used，导致
--       「成员已使用」≠「账单实耗」。A1 起服务层已同事务计入；本迁移把
--       历史存量行一次性回填，使新旧口径一致。
-- 版本号说明：计划原文写 V158，实现时 V158 已被 group_ledger_member_types
--       占用（多工作流并行取号先查 flyway_schema_history）→ 改号 V159。
--
-- 消费成员(consumer)映射链（BACKSTOP 行 actor=组长，非消费者，须反查）：
--   ① 媒体路径：ref_id=media_gen_tasks.id（数字才尝试）→ task.user_id（且 task 属本组）
--   ② 聊天/嵌入路径：同组同 ref_id 的 CONSUME 行（HOLD 预扣腿，actor=消费成员）
--      —— 全量扣路径 chargeGroup 失败时无 CONSUME 行，聊天走 ② 命中 HOLD 腿；
--         媒体全量扣路径走 ① 命中任务行
--   ③ 都映射不到 → 跳过+NOTICE 清单（宁缺勿错：错记比漏记危害大）
--
-- 幂等：remark 追加 ' | used-synced(V159)' 标记，重跑零变更；
--       A1 之后的新 BACKSTOP 行 remark 含「计入成员已用」，被 remark 过滤排除，
--       不会双加。
-- 回滚：本迁移只加不减（used 只增），出错时按 NOTICE 清单人工反向修正——
--       flyway_schema_history 可 repair 回退版本号，数据回退需按清单手算。
DO $$
DECLARE
    r RECORD;
    v_consumer BIGINT;
    v_amount NUMERIC;
    v_done INT := 0;
    v_skipped INT := 0;
    v_total NUMERIC := 0;
BEGIN
    FOR r IN SELECT * FROM project_group_ledger
             WHERE type = 'BACKSTOP'
               AND (remark IS NULL OR remark = '组池不足·组长兜底')
               AND remark NOT LIKE '%used-synced%'
             ORDER BY id
    LOOP
        v_consumer := NULL;

        -- ① 媒体路径：ref_id 数字 → 任务行
        IF r.ref_id ~ '^[0-9]+$' THEN
            SELECT t.user_id INTO v_consumer
            FROM media_gen_tasks t
            WHERE t.id = r.ref_id::bigint
              AND t.project_group_id = r.group_id;
        END IF;

        -- ② 聊天/嵌入路径：同组同 ref 的 CONSUME（HOLD 腿）行 actor
        IF v_consumer IS NULL THEN
            SELECT l.actor_user_id INTO v_consumer
            FROM project_group_ledger l
            WHERE l.group_id = r.group_id
              AND l.ref_id = r.ref_id
              AND l.type = 'CONSUME'
              AND l.id <> r.id
            ORDER BY l.id DESC
            LIMIT 1;
        END IF;

        IF v_consumer IS NULL THEN
            RAISE NOTICE 'V159 skip(unmapped): ledger=% group=% ref=%:%',
                    r.id, r.group_id, r.ref_type, r.ref_id;
            v_skipped := v_skipped + 1;
            CONTINUE;
        END IF;

        v_amount := ABS(r.delta_points);
        UPDATE project_group_members
        SET used_points = used_points + v_amount,
            updated_at = NOW(),
            version = version + 1
        WHERE group_id = r.group_id
          AND user_id = v_consumer
          AND deleted = 0;

        IF NOT FOUND THEN
            RAISE NOTICE 'V159 skip(member-gone): ledger=% group=% consumer=% ref=%:%',
                    r.id, r.group_id, v_consumer, r.ref_type, r.ref_id;
            v_skipped := v_skipped + 1;
            CONTINUE;
        END IF;

        UPDATE project_group_ledger
        SET remark = COALESCE(remark, '') || ' | used-synced(V159)'
        WHERE id = r.id;

        v_done := v_done + 1;
        v_total := v_total + v_amount;
    END LOOP;

    RAISE NOTICE 'V159 backfill done: rows=% skipped=% total_points_added=%', v_done, v_skipped, v_total;
END $$;
