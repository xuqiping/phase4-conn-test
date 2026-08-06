-- ============================================================
-- V60: 项目资产库 · 媒体类型两层设计（media_category + media_types 受控词汇）
-- 功能：plan §C1b / D1 两层架构——拆「处理类别」与「媒体类型标签」两维度
--   1. assets.media_category（系统固定 4 类 TEXT/IMAGE/VIDEO/AUDIO）= 决定处理逻辑
--      （编辑器形态/预览/mime 校验/gen_meta 提取/画布节点映射）；旧 media_type 仍作受控词汇标签用。
--   2. asset_projects.media_types JSONB = 项目级受控词汇 [{key,category}]，默认 5 项，
--      owner/editor 维护（防标签腐烂，同 narrative_roles 范式）。
-- 设计要点（plan 技术坑点）：
--   - 自定义 media_type 击穿分类链 → category 决定处理、type 仅分类标签。
--   - 删旧 chk_asset_media（media_type 放开任意字符串，走应用层受控校验）。
--   - backfill：按 media_type 现值 CASE 回填 category（全 5 类 + DEFAULT 'TEXT' 兜底）。
--   - media_type 列放宽 VARCHAR(16)→VARCHAR(32)，防长 key 撞 DB 500（与 role_key VARCHAR(32) 对齐）。
-- 回滚：ALTER TABLE assets DROP CONSTRAINT chk_asset_media_category, DROP COLUMN media_category;
--       ALTER TABLE asset_projects DROP COLUMN media_types;
--       （旧 chk_asset_media 不重建——应用层校验已接管）
-- ============================================================

-- 1. assets 加处理类别列（先加默认 TEXT，再按现值 backfill，最后加 CHECK）
ALTER TABLE assets ADD COLUMN media_category VARCHAR(20) NOT NULL DEFAULT 'TEXT';

UPDATE assets SET media_category = CASE media_type
    WHEN 'PROMPT' THEN 'TEXT'
    WHEN 'SCRIPT' THEN 'TEXT'
    WHEN 'IMAGE'  THEN 'IMAGE'
    WHEN 'VIDEO'  THEN 'VIDEO'
    WHEN 'AUDIO'  THEN 'AUDIO'
    ELSE 'TEXT'
END;

-- 删旧 media_type CHECK（type 改任意字符串，受控词汇走应用层 AssetService.validateMediaType）
ALTER TABLE assets DROP CONSTRAINT chk_asset_media;
-- 加新 category CHECK（系统固定 4 类）
ALTER TABLE assets ADD CONSTRAINT chk_asset_media_category
    CHECK (media_category IN ('TEXT','IMAGE','VIDEO','AUDIO'));
-- media_type 放宽长度（受控词汇 key 可能长于 16，与 role_key 对齐到 32）
ALTER TABLE assets ALTER COLUMN media_type TYPE VARCHAR(32);

COMMENT ON COLUMN assets.media_type     IS '轴A 媒体类型标签（项目受控词汇 key，如 PROMPT/SCRIPT/IMAGE/VIDEO/AUDIO 或自定义「地图」）；仅分类，处理行为由 media_category 决定';
COMMENT ON COLUMN assets.media_category IS '处理类别（系统固定 4 类 TEXT/IMAGE/VIDEO/AUDIO）：决定编辑器形态/预览/mime 校验/gen_meta 提取/画布节点映射';

-- 2. asset_projects 加媒体类型受控词汇 JSONB（默认 5 项 {key,category}）
ALTER TABLE asset_projects ADD COLUMN media_types JSONB NOT NULL DEFAULT
    '[{"key":"PROMPT","category":"TEXT"},{"key":"SCRIPT","category":"TEXT"},
      {"key":"IMAGE","category":"IMAGE"},{"key":"VIDEO","category":"VIDEO"},{"key":"AUDIO","category":"AUDIO"}]'::jsonb;

COMMENT ON COLUMN asset_projects.media_types IS '媒体类型受控词汇桶 JSON 数组 [{key,category}]，默认 5 项；owner/editor 维护（防标签腐烂，同 narrative_roles 范式）。key=类型标签，category=TEXT/IMAGE/VIDEO/AUDIO 决定处理';
