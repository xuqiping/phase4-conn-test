# Phase 1 — 用户表加钉钉绑定字段

> 总路由：[README.md](README.md) · 上一：— · 下一：[Phase 2](phase-02-dingtalk-properties.md)

**Goal：** `users` 表加钉钉绑定字段，实体对齐。

**Files:**
- Create: `backend/src/main/resources/db/migration/V41__add_dingtalk_user_binding.sql`
- Modify: `backend/src/main/java/com/superprogrammer/auth/entity/User.java`

**Interfaces:**
- Consumes: 现有 `users` 表 + `BaseEntity` 字段。
- Produces: `User.bindType / dingtalkUnionId / dingtalkOpenId`；DB 列 + unionId 唯一部分索引。

---

- [x] **Step 1: 写 V41 迁移文件**

```sql
-- 钉钉 H5 微应用免登：用户绑定字段
-- bind_type 区分账密用户(password)与钉钉免登用户(dingtalk)
ALTER TABLE users ADD COLUMN IF NOT EXISTS bind_type        VARCHAR(20)  NOT NULL DEFAULT 'password';
ALTER TABLE users ADD COLUMN IF NOT EXISTS dingtalk_union_id VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS dingtalk_open_id  VARCHAR(64);

-- unionId 唯一，但允许多个 NULL（账密用户未绑定时为 NULL）。用部分索引。
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_dingtalk_union_id
    ON users (dingtalk_union_id)
    WHERE dingtalk_union_id IS NOT NULL;
```

- [x] **Step 2: 改 `User.java`，加三个字段**

在 `lastLoginAt` 下方追加（保持现有字段不动）：

```java
    /** 登录方式：password=账密，dingtalk=钉钉免登 */
    private String bindType;

    /** 钉钉 unionId（跨应用稳定标识，账密用户为 null） */
    private String dingtalkUnionId;

    /** 钉钉 openId（应用内标识） */
    private String dingtalkOpenId;
```

- [x] **Step 3: 校验编译**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS。

- [x] **Step 4: 提交**

```bash
git add backend/src/main/resources/db/migration/V41__add_dingtalk_user_binding.sql backend/src/main/java/com/superprogrammer/auth/entity/User.java
git commit -m "feat(auth): V41 users 表加钉钉绑定字段(bind_type/union_id/open_id)"
```

- [ ] **完成后：** 回 [README](README.md) 勾掉 Phase 1，开 Phase 2。
