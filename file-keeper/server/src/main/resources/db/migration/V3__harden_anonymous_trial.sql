-- =============================================================================
-- V3__harden_anonymous_trial.sql
-- 用途：增强匿名设备试用的反作弊能力
-- 说明：增加 IP、UA 指纹、试用重置次数等字段，用于识别刷试用行为
-- =============================================================================

-- 首次发现该匿名设备时的来源 IP（支持 IPv6，故 45 字符）
ALTER TABLE anonymous_device_trials ADD COLUMN first_seen_ip VARCHAR(45);

-- 用户代理（User-Agent）哈希，用于识别同一浏览器/客户端多次注册
ALTER TABLE anonymous_device_trials ADD COLUMN user_agent_hash VARCHAR(64);

-- 试用重置次数统计，超过阈值可触发风控拦截
ALTER TABLE anonymous_device_trials ADD COLUMN trial_reset_count INT NOT NULL DEFAULT 0;

-- 加速按 IP 反查设备和按重置次数筛查异常设备
CREATE INDEX idx_anonymous_device_trials_first_seen_ip ON anonymous_device_trials(first_seen_ip);
CREATE INDEX idx_anonymous_device_trials_reset_count ON anonymous_device_trials(trial_reset_count);
