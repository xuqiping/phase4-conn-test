-- C6 连接器（WP6 Step3）：连续错误计数列。
-- 生活比喻：连接器像送奶工，连续 3 天没送到奶（同步失败 3 轮）就亮红灯停送（status=ERROR），
-- 等主人检查修好（管理端重新启用）再恢复送奶；天数记在本子上（sync_error_streak）。
ALTER TABLE knowledge_connectors
    ADD COLUMN IF NOT EXISTS sync_error_streak INT NOT NULL DEFAULT 0;
