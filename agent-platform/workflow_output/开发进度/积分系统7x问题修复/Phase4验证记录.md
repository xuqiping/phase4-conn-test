# 积分系统 7x 问题修复 · Phase 4 验证记录

> 验证方式：Playwright IAB 浏览器自动化（UI 渲染验证）+ curl API 验证（数据/计费路径）+ DB 直查（迁移落盘）。
> 验证时间：2026-08-11。admin 账号登录。

## 环境确认
- 后端 8080 health 200，前端 5173 200，均已加载新代码（export endpoint 返 200，hasReference 字段存在）
- V95 迁移已应用：`pricing_rule.has_reference boolean NOT NULL DEFAULT false`；`idx_pricing_lookup` 含 has_reference 列
- 既有 VIDEO 全局价 Cdance2.0 行 has_reference=f（旧行兼容）

## 测试结果

### TC-1 · admin 登录 + 价表配置页 ✅
- 登录成功（dom_cua.click submit node_id）
- 价表配置页渲染：**导出 / 下载模板 / 导入 / 新增价表** 四按钮齐全
- 价表列表含「**参考视频**」列；VIDEO 行显「无参考」，非 VIDEO 行显「—」

### TC-1b · 创建 IMAGE 价表不再 500 ✅
- `POST /api/billing/pricing` kind=IMAGE providerId=7 model=Doubao-Seedream-5.0-lite pricePerImage=0.1
- 返 **200**（不再 500）；落库 `videoBillingMode=null`（不再泄漏 'TOKEN'）、`priceInputPerMillion=null`（sanitize 生效）
- 根因确认：LlmProviderMapper `deleted=0` 修复生效

### TC-2 · 导出价表 ✅
- `GET /pricing/export` 返 200，`Content-Disposition: attachment; filename="pricing-rules-2026-08-11.json"`，`X-Content-Type-Options: nosniff`
- JSON 含 hasReference 字段，明文价格无密钥

### TC-3 · 下载模板 ✅
- `GET /pricing/template` 返 200，文件名 `pricing-template-2026-08-11.json`
- 2 行（CHAT kimi/k3 + IMAGE Doubao-Seedream-5.0-lite），联动供应商预填 kind/providerId/providerName/model
- 所有价格字段 null（fill-in 模板）；已配置的模型不在模板里（availablePricingModels 过滤生效）

### TC-4 · 导入新建 ✅
- `POST /pricing/import` 1 行 CHAT k3（未配置）→ `{created:1, updated:0, failed:0}`

### TC-5 · 导入覆盖（upsert）✅
- 同 k3 改价再导入 → `{created:0, updated:1, failed:0}`（价格被覆盖，effective_from 刷新）

### TC-6 · 导入含非法行不中断 ✅
- 1 行 providerId=99999（不存在）+ 1 行合法 IMAGE → `{created:1, updated:0, failed:1, errors:["providerId=99999 不存在或未启用"]}`
- 合法行落库，非法行进 errors

### TC-7 · 导入超限拒绝 ✅
- 201 行 → HTTP 400 `单次导入上限 200 条，当前 201 条`

### TC-8 · VIDEO has_reference 定价维度 ✅
- V95 列存在：`has_reference boolean NOT NULL DEFAULT false`
- 索引重建：`idx_pricing_lookup (kind, model, has_reference, effective_from DESC)`
- 既有 VIDEO 行 has_reference=f（旧行兼容）
- 查询语义验证：`has_reference=true` 精确查返 NULL（无 true 行），`has_reference=false` 查返行 id=7
- fallback 逻辑（PricingService 层编排）由单测覆盖：true 查不到 → fallback 到 false 行

### TC-11 · VideoGenView 参考视频标志 ✅
- 历史列表含「**参考视频**」列
- 任务 45（Cdance2.0，带参考视频附件）列显「**有**」
- 任务详情头部标签：`当前任务 已完成 用量估算 有参考视频`（hasReference 标志正确）
- 「请求参数」按钮存在 → 点开 modal 含「平台收到的提交参数」+「实际发给模型（已脱敏）」两个 tab
- submittedRequest JSON 含 attachments `[{kind:image},{kind:video}]`（证实 hasReference=true 计算正确，kind=video 触发）
- **TC-13 脱敏**：providerRequestSnapshot tab 存在（媒体 URL 已脱敏为 sha256/大小，单测覆盖）

### TC-12 · Canvas 审计面板接入（旁证）✅
- Canvas 节点为自定义绘制（vue-flow），DOM snapshot 难以定位节点交互
- 验证依据：
  - 单测 MediaGenQueryServiceTest +3（hasReference 计算：video 附件 true / 首帧图 false / 无附件 false）
  - vue-tsc 类型检查通过（CanvasView pollVideoTask/hydrate 保留审计字段、PropertyPanel 接 MediaTaskRequestDetails、VideoNode 角标）
  - VideoGenView 实测确认 hasReference 标志 + 推送参数面板对带参考视频任务正确工作（Canvas 共用同一组件 + 同一 VO 字段）
- 推送参数已脱敏落库在 request_config.providerRequestSnapshot（无需新 DB 列，复用现有机制）

## 自动化测试（Phase 3 已跑通）
- 后端 126 测试 BUILD SUCCESS（PricingServiceTest/PricingConfigServiceTest/MediaBillingServiceTest/MediaGenQueryServiceTest/MediaGenTaskWorkerTest/MediaGenTaskServiceTest/PricingConfigControllerTest/BillingDtoValidationTest）
- 前端 vue-tsc 通过 + VideoGenView.test.ts 2 用例通过

## 结论
- 4 个问题（7x-1/2/3/4）全部通过验证，无阻断缺陷
- 已知非阻断：Canvas 节点级交互因自定义绘制未做 GUI 点按验证，由单测+类型检查+VideoGenView 同源旁证覆盖；Phase 5 发布后可人工点一遍画布视频节点确认
- 安全检查：导出/导入/模板三 endpoint 均挂 @RequirePermission+@AuditLog；价表无加密无需二次确认；推送参数已脱敏

**可放行 Phase 5 发布迭代。**
