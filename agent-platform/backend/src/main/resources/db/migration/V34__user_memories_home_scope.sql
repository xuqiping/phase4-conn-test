-- V34: user_memories 加 home_project_id（写归属/唯一性槽），换唯一索引为 home-aware。
-- 背景：V33 纯可见性标签模型下，唯一索引 V29 仍 (user_id, memory_key) scope-orthogonal，
-- 而 dedup 查询 scope-filtered（只看写目标 scope）→ 写目标看不到的老行把 INSERT 顶爆
-- （global 已有 child_name，切项目 1 再加女儿名 → 撞 uk_user_memories_user_key_clean 报错）。
-- V34 改混合模型：home 管唯一性/写归属（分区语义），is_global+user_memory_projects 管读可见性（共享语义）。
--   - 每项目独立 key：global/P1/P2 各一条 child_name（不同 home）共存。
--   - 跨项目共享：一条记忆可经 user_memory_projects/is_global 被多 scope 读取。
-- 读路径不动（SCOPE_FILTER 仍按 mem_include_global + mem_read_project_ids 过滤，同 key 多条全注入）。

-- 1. 加 home 列（NULL = global home）
ALTER TABLE user_memories ADD COLUMN home_project_id BIGINT;
COMMENT ON COLUMN user_memories.home_project_id IS '写归属/唯一性槽：NULL=global home，否则=该 project home。与可见性(is_global+user_memory_projects)正交：home 管"能不能再插同 key"，可见性管"读时哪些 scope 拉它"。';

-- 2. 回填：is_global=false 行从 user_memory_projects 取首个 project 作 home（多挂取最小 id，保唯一约束不撞）
--    is_global=true 行 home_project_id 留 NULL（global home，默认即）
--    注：user_memories 表无 deleted 列（不走 BaseEntity 软删），不在此过滤。
UPDATE user_memories m
SET home_project_id = (
    SELECT MIN(p.project_id)
    FROM user_memory_projects p
    WHERE p.memory_id = m.id
)
WHERE m.is_global = false
  AND m.home_project_id IS NULL;

-- 3. 删旧 scope-orthogonal 唯一索引
DROP INDEX IF EXISTS uk_user_memories_user_key_clean;

-- 4. 新 home-aware 唯一索引
--    COALESCE(home_project_id, -1) 解 PG NULL distinct 问题（PG 默认 NULL 互不相等，不写 COALESCE 则多条 global 同 key 可共存）
--    -1 哨兵：project id 从 GENERATED ALWAYS AS IDENTITY 正向递增，不会取到 -1，无冲突。
CREATE UNIQUE INDEX uk_user_memories_user_key_home
    ON user_memories (user_id, memory_key, COALESCE(home_project_id, -1))
    WHERE conflict_id IS NULL;
