-- =============================================================================
-- V4__device_time_sync_anomaly.sql
-- 用途：在用户设备表增加时间同步异常计数
-- 说明：客户端若通过修改系统时间绕过离线授权校验，服务端可累计异常次数并风控
-- =============================================================================

-- 设备时间同步异常次数，用于检测和限制时间作弊
ALTER TABLE user_devices ADD COLUMN time_sync_anomaly_count INT NOT NULL DEFAULT 0;

-- 加速筛选时间同步异常设备
CREATE INDEX idx_user_devices_anomaly ON user_devices(time_sync_anomaly_count);
