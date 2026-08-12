-- ============================================================
-- V94: 日志审计问题修复（8x）
-- 1) idx_audit_username —— 按账号名筛选的索引（问题 #4 用户账号筛选）
-- 2) media_gen_tasks.client_ip / media_edit_tasks.client_ip —— submit 时盖戳用户 IP，
--    worker 终态审计取用（问题 #6 worker 无 MDC 的 IP 缺口）
-- 回滚：DROP INDEX idx_audit_username; ALTER TABLE ... DROP COLUMN client_ip;
-- 注意：审计表 audit_logs 本身不加列（client_ip V89 已有），本脚本只动媒体任务表 + 加账号索引。
-- ============================================================

-- #4 账号名筛选索引（username LIKE 走前缀可用；admin 低频，双侧模糊可接受）
CREATE INDEX IF NOT EXISTS idx_audit_username ON audit_logs(username, created_at);

-- #6 媒体任务行盖戳 client_ip：submit（web 请求，MDC 有 IP）写入，worker 终态读出落审计
ALTER TABLE media_gen_tasks  ADD COLUMN IF NOT EXISTS client_ip VARCHAR(64);
ALTER TABLE media_edit_tasks ADD COLUMN IF NOT EXISTS client_ip VARCHAR(64);

COMMENT ON COLUMN media_gen_tasks.client_ip  IS '提交者 IP（submit 时从 MDC 盖戳，worker 终态审计取用，问题修复 #6）';
COMMENT ON COLUMN media_edit_tasks.client_ip IS '提交者 IP（同上）';
