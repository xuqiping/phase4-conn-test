# 反馈中心 README（19x）

## 用户地图（谁用 / 场景 / 效益）

| 角色 | 场景 | 效益 |
|---|---|---|
| 普通用户 | 提需求（带截图）、问操作问题、查 FAQ、看使用说明 | 有门可进：建议有人审、提问有人答、答案可自助检索；进度靠铃铛通知，不用反复问 |
| admin（feedback:manage） | 「反馈处理」页审建议、答提问、挑好问答公开成 FAQ | 一处收口全部反馈；抢态防多人重复处理；FAQ 滚雪球减少重复答疑 |
| admin（help:manage） | 「帮助文章」页维护 markdown 说明文 | 操作文档随版本更新，发布/下架即时生效 |

效益一句话：**用户反馈与自助帮助三合一**——建议台收需求、提问台答疑惑、说明台放文档；公开问答沉淀为 FAQ，长期降低答疑人力。

## 技术说明

- **形态**：用户侧单页三 tab（`/feedback`）+ 顶栏通知铃铛；admin 两页（`/admin/feedback`、`/admin/help-articles`），权限码 `feedback:manage`/`help:manage` 双码分离。
- **数据**：四表（V141）——建议（username 快照+JSONB 附件）/提问（is_public 进 FAQ）/文章（slug 唯一，硬删释放）/通知（未读部分索引）。
- **并发**：状态流转全部条件 UPDATE 抢态（0 行=409）；改判（ADOPTED↔REJECTED）重发通知，CLOSED 终态。
- **隐私**：FAQ 脱敏在「字段不存在」层（SQL 不 SELECT、VO 无字段）；附件逐 fileId 校验属主。
- **安全**：提交限流 5/分/用户（建议提问分开计）；markdown html:false 防 XSS；admin 动作全 @AuditLog。
- **详查**：`docs/feature-map/反馈中心.feature-map.md`（调用链+表注解）、`docs/user-ops/反馈中心用户操作手册.md`（逐步操作）。
