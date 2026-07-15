# Phase 4 进度 — 体验优化

**状态：** ⚪ 未开始  
**预计周期：** 1 周  
**详细计划：** [plan.md](plan.md)

## 每日灵感回顾

- [ ] 创建 `InspirationReviewService`
- [ ] 复用调度器定时推送
- [ ] 更新被推送灵感的 `reviewed_at`
- [ ] 迁移 `V15__add_inspiration_review_config.sql`

## 快捷指令菜单

- [ ] `NlpIntentService` 识别 `/help` 与帮助类消息
- [ ] `InboundMessageService` 处理 `help` 意图
- [ ] 机器人回复菜单文本

## 移动端轻量化

- [ ] 高频操作在 IM 中闭环完成
- [ ] 降低必须打开桌面端确认的场景
- [ ] 优化 auto-confirm 阈值与匹配逻辑

## 企业微信适配（可选）

- [ ] `WeComWebhookAdapter`
- [ ] `WeComWebhookController`
- [ ] 文档说明普通群机器人与自建应用差异

## 里程碑

- [ ] 每天定时收到历史灵感回顾推送
- [ ] 在 IM 中输入 `/help` 能收到指令菜单
- [ ] 90% 以上高频操作无需打开桌面端
