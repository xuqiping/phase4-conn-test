-- ============================================================
-- V62: 项目资产库 · 受控词汇追加「分镜」媒体类型（S17）
-- 背景：S17 新增分镜媒体类型（MEDIA_STORYBOARD="分镜"，category=TEXT）。
--      新建项目由 AssetProjectService.DEFAULT_MEDIA_TYPES 自动含分镜；
--      存量项目须补，否则一键分镜时 resolveCategory「分镜」不在 vocab → 400。
-- 范围：仅给 asset_projects.media_types JSONB 数组追加 {key:"分镜",category:"TEXT"}。
--      幂等：已含「分镜」的项目不动（WHERE NOT EXISTS 守卫），可重复执行。
-- 不动：assets.media_type（分镜资产由一键分镜新建，无存量）、media_category（系统枚举）。
-- 回滚：从 media_types 删 key='分镜' 项（jsonb 遍历重建）；Flyway 不自动 down。
-- ============================================================

UPDATE asset_projects p
SET media_types = p.media_types || jsonb_build_array(jsonb_build_object('key', '分镜', 'category', 'TEXT'))
WHERE jsonb_typeof(p.media_types) = 'array'
  AND NOT EXISTS (
      SELECT 1 FROM jsonb_array_elements(p.media_types) AS e WHERE e->>'key' = '分镜'
  );
