-- =============================================================================
-- V2__widen_device_name.sql
-- 用途：放宽用户设备和匿名设备试用表中设备名称的长度限制
-- 说明：部分 Windows 设备名称较长，120 字符不足以展示完整计算机名，扩至 255
-- =============================================================================

-- 用户设备：device_name 从 VARCHAR(120) 扩至 VARCHAR(255)
ALTER TABLE user_devices ALTER COLUMN device_name SET DATA TYPE VARCHAR(255);

-- 匿名设备试用：device_name 从 VARCHAR(120) 扩至 VARCHAR(255)
ALTER TABLE anonymous_device_trials ALTER COLUMN device_name SET DATA TYPE VARCHAR(255);
