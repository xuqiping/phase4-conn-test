-- ============================================================
-- V149：帮助文章按权限码过滤可见性（说明台·帮助中心权限门控）
--
-- 背景：帮助中心上线全模块使用说明后，用户应只能看到自己拥有权限模块的文章
--   （如无 media:gen 的用户看不到「视频生成」说明），避免暴露未授权模块的存在与用法。
--
-- 变更：help_articles 增加 required_permission 列：
--   - NULL  = 所有登录用户可见（chat/knowledge/wallet/feedback 等无权限码模块）；
--   - 否则  = 用户须持有该权限码（或 ROLE_admin）才在目录/直链可见；
--             特殊值 'ROLE_admin' = 仅系统管理员（与 JWT authorities 同源字符串）。
--
-- 过滤在服务层做（文章表小，目录全量内存过滤），用户侧目录与 slug 直链同闸（直链 404 不泄露存在性）。
--
-- 回滚（rollback）：
--   ALTER TABLE help_articles DROP COLUMN IF EXISTS required_permission;
-- ============================================================

ALTER TABLE help_articles ADD COLUMN required_permission VARCHAR(64);

COMMENT ON COLUMN help_articles.required_permission IS
    '可见性权限码：NULL=全员；否则须持有该码或 ROLE_admin（V149）';
