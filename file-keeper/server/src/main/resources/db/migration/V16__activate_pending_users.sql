-- 取消人工审核流程：将历史待审核普通用户激活。
-- disabled 等人工封禁状态不在更新条件中，必须保持原状。
UPDATE users
SET status = 'active',
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'pending_review'
  AND deleted = 0;
