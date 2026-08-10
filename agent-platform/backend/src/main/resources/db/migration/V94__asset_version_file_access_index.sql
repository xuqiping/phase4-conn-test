-- Phase 4 公共资产文件访问修复：FileStorageService 的资产共享 grantor 按 file_id 查引用。
-- 部分索引只覆盖真实文件版本，避免图片/视频卡片预览时扫描 asset_versions 全表。
CREATE INDEX IF NOT EXISTS idx_asset_version_file
    ON asset_versions(file_id)
    WHERE file_id IS NOT NULL;
