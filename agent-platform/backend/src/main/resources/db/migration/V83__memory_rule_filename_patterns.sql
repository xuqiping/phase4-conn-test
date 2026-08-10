-- ============================================================
-- V79: memory_project_rules 增文件名硬规则列 filename_patterns
--
-- 背景（用户需求）：语义锚点路由是概率性匹配（向量+BM25+LLM 精判+置信度），
--   无法做到「文件名含 XX 就一定进该项目」。新增确定性短路：上传附件文件名命中
--   任一模式 → 直接落 ACTIVE 条目一定进，跳过粗筛/精判/置信度/脱敏（用户显式
--   「一定进，风险上传者自负」，见 MemoryRoutingService ④.0 分支）。
--
-- 语义（v1）：
--   · 子串包含，大小写不敏感（lower(original_name) LIKE '%pattern%'，应用侧算）
--   · 仅作用于 FILE 路由（对话轮无文件名）
--   · 与语义路由并行：filename 短路先插 ACTIVE；语义分支经 countFileEntry 幂等跳过
--   · 规则仍须 enabled + anchor 就绪（在 findRoutingCandidates 候选集内）——
--     embed 故障致 enabled 强制 false 时整条规则不生效（含文件名短路），v1 取此简化
--
ALTER TABLE memory_project_rules
    ADD COLUMN filename_patterns TEXT[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN memory_project_rules.filename_patterns IS
    '文件名硬规则（v1 子串包含，大小写不敏感）：上传附件文件名命中任一模式 → 确定性直接 ACTIVE 一定进该规则所属项目，跳过语义路由/精判/脱敏，风险上传者自负';
