-- ============================================================
-- V100: 资产公众池「允许公共复制」开关（2x 待决策项落地）
-- 发布时 OWNER/admin 自选：关闭后公共 VIEWER 仅可引用（resolve），
-- 不可把资产复制成自己项目的独立副本（copy 接口 403 ASSET_COPY_FORBIDDEN）。
-- 项目成员/OWNER/admin 的复制不受限（开关只管公共 VIEWER，测试方案 C5）。
--
-- DEFAULT TRUE：存量已发布项目保持现状（公共池现状=允许复制），零行为变化。
-- 回滚：ALTER TABLE asset_projects DROP COLUMN IF EXISTS allow_public_copy;（无数据损失）
-- ============================================================

ALTER TABLE asset_projects
    ADD COLUMN allow_public_copy BOOLEAN NOT NULL DEFAULT TRUE;

-- 发布弹窗回显用：开关跨「移出公众池→再发布」保留（unpublish 不清此列）。
