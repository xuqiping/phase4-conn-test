# 规格 · 视频 TOKEN 计价按分辨率分档（seedance2.0 每百万价分档）

> SDD 特性级规格（Phase 1 产出）。实现须与本文件对齐；冲突时改实现或改本文档（注明原因）。
> 来源：`workflow_output/人工测试问题/7_积分系统.md` §未解决（2026-08-27）+ 用户决策 3 问（见 §2）。
> 上游规格：[积分计费系统.md](积分计费系统.md)（B4 价表）——本文件是其增量，落地后回写主规格变更记录。

## 1. 背景与问题

**用户原话**：价格配置里，视频生成模型，不同的分辨率对应每百万的价格不同，seedance2.0 模型（Cdance2.0）需要给我像预估一样的，分 480p/720p/1080p/4k（有无视频输入同理）。

**现状事实**（2026-08-27 探查）：
- 视频 TOKEN 模式真实扣费 = `usage.total_tokens ÷ 1M × price_input_per_million`（[PricingService.videoCost:384-388](../../../backend/src/main/java/com/superprogrammer/billing/service/PricingService.java#L384-L388)）——**单值每百万价，不看分辨率**。
- 分辨率目前只影响两处，均非真实扣费：① 预估 `est_per_resolution` JSONB 5 槽（¥/秒，[V153](../../../backend/src/main/resources/db/migration/V153__pricing_est_per_resolution.sql)）；② usage 缺失时伪 token（视频像素×帧×时长折算的计数，非文字分词）费率表（`MediaGenTaskWorker.estimateRatePerSec`）。
- 价表行身份 = `(providerId, model, kind=VIDEO, has_reference)`（V95 起有/无参考两行；D6/V160 后判重与候选均无分辨率维度）。
- seedance2.0（Ark，前端模型列表 `seedance-2` 前缀，支 480p~4K）Ark 回传仅 token 总量，无输入/输出拆分。

**与 D6（去分辨率档）的关系**：D6 删的是 **SECOND 秒价多行分辨率档**（V160 合并存量行、表单去下拉）；本规格给 **TOKEN 每百万价**加「一行内多槽」分档——**不恢复多行、不启用 `resolution` 列、候选展开仍参考面×2**。两决策不冲突。

**入口漏斗实证**（2026-08-27 全量探查）：seedance 视频生成全平台**单提交口**——前端仅两入口（生成页 [VideoGenView.vue:967](../../../frontend/src/views/VideoGenView.vue#L967)、无限画布 [CanvasView.vue:1314](../../../frontend/src/views/CanvasView.vue#L1314) `submitVideoOnly`，PropertyPanel 经 CanvasView 同路），同一 `MediaSubmitRequest` DTO → `POST /api/media/video` → worker → `settleMediaSuccess`（已传 `hasReference`+`resolution`，[MediaGenTaskWorker:256-259](../../../backend/src/main/java/com/superprogrammer/media/service/MediaGenTaskWorker.java#L256-L259)）。provider 直调仅 worker 一处；LlmGateway 不注册 VIDEO 类（[LlmConfig.java:43-48](../../../backend/src/main/java/com/superprogrammer/llm/config/LlmConfig.java#L43-L48)）；导演台/工作流/agent 工具无视频生成调用；视频反转走 LLM chat 计费、视频剪辑为本地 ffmpeg，均不调 seedance。**结论：只改 videoCost 一点，所有入口的 Cdance2.0 任务自动按档计费，入口零改动。**

## 2. 用户决策（2026-08-27 拍板）

| # | 问题 | 决策 |
|---|---|---|
| Q1 | 每档配几个每百万价？（Ark 仅回 token 总量） | **每档单一价**（不配输入/输出双价） |
| Q2 | 任务用了未配档位（只配 480p/720p，用户跑 4K） | **回落通用价**（=现有 `price_input_per_million`，老配置零迁移） |
| Q3 | 预估秒价 est_per_resolution 是否改为按每百万价派生 | **维持独立配置**（供应商无关；D9 偏差监控照常） |

## 3. 功能需求

| 编号 | 需求 | 说明 | 优先级 |
|---|---|---|---|
| VTR-1 | 价表 TOKEN 行新增分辨率价槽 | `pricing_rule` 新增 `token_price_per_resolution JSONB`（**JSONB**——PostgreSQL 里能按键存取的 JSON 列，一行内放多个档位的价），仅 VIDEO+TOKEN 行有意义：`{"480p":6.5,"720p":12.3,"1080p":27.8,"4k":111.2}`，值=¥/百万 token。键 ⊆ `{480p,720p,1080p,4k}`，**无 general 键**——通用价沿用现有 `price_input_per_million` 列（复用，语义升级为「通用/兜底每百万价」，不改名不加列） | P0 |
| VTR-2 | 真实扣费按档取价 | `videoCost` TOKEN 分支：`价 = token_price_per_resolution[normalizeResolution(res)] ?? price_input_per_million`；`cost = total_tokens ÷ 1M × 价`（6 位小数 HALF_UP 不变）。normalize 复用 `PricingService.normalizeResolution`（trim+小写，4K→4k），配置侧与结算侧同函数防失配。**缺价双口径维持现状**：有价表行但通用价/槽位全空 → 按 0 元结算交付（[videoCost:385-387](../../../backend/src/main/java/com/superprogrammer/billing/service/PricingService.java#L385-L387) 返 `BigDecimal.ZERO`）；整行无价表/计费失败 → `chargedPoints=null` 退预扣转 FAILED（[MediaGenTaskWorker:262-267](../../../backend/src/main/java/com/superprogrammer/media/service/MediaGenTaskWorker.java#L262-L267) fail-closed）。均不新加拦截 | P0 |
| VTR-3 | 命中链不变 | 先按 `resolveRule` 现有链命中行（精确参考面→无参考兜底），**再在该行内**取档位价；fallback 命中无参考行时槽位也取该行的（不跨行拼价） | P0 |
| VTR-4 | 配置校验 | 仅 VIDEO+TOKEN 行可配槽位（SECOND/CHAT/EMBED/RERANK/IMAGE 传入非空 → 400）；键白名单 ⊆ 4 档；值 >0；`resolution` 列仍恒 null（D6 口径，校验不变）。槽位**非身份字段**：编辑可改，判重键、身份锁定、候选展开均不动 | P0 |
| VTR-5 | 价表页表单 | TOKEN 分支：现有「输入每百万 token 价」标签改为「通用每百万价（¥/百万，未单列档位按此计）」+ 新增 4 档选填输入（480p/720p/1080p/4K，留空=按通用，占位文案同 est 槽位风格）。现状通用价**非必填**（validatePricingRule 仅 nonNegative，[PricingConfigService.java:447](../../../backend/src/main/java/com/superprogrammer/billing/service/PricingConfigService.java#L447)）——保持可空（空=按 0 元口径），表单加软提示「未配价=按 0 计费」非硬校验。列表新增「每百万价（通用→各档）」展示列（无槽位行只显通用价）。有参考/无参考两行各自配各自的槽 | P0 |
| VTR-6 | 导出/导入/模板 | `PricingRuleExportItem` + `tokenPricePerResolution` 字段；导出带值；模板 VIDEO TOKEN 预填空槽；导入 upsert 键不变 `(providerId,model,kind,hasReference)`，**null=不动库中现有槽位（防旧文件误清），非 null=整体覆盖（空对象 `{}`=清空）**；200 行上限与逐行容错不变 | P0 |
| VTR-7 | 预估与预扣不动 | `est_per_resolution`（¥/秒）独立配置口径不变；HOLD 预扣=预估×比例链不变；D9 est-deviation 监控不动。文档/UI 提示：预估秒价与每百万价是**两套独立配置**，建议管理员同改保持一致 | P0 |

## 4. 非功能需求

- **性能**：取档价=同一行内 JSONB 取键，**零新增 SQL**；结算链（行锁/幂等键/流水腿）零改动，回归目标=无。价表 CRUD 校验 O(槽位数) 常数。
- **安全**：沿用 `pricing:manage` 权限与 create/update `@AuditLog`；槽位值服务层白名单+正数校验（防注入/脏价），与 `est_per_resolution` 同范式（V153 先例，不加 DB CHECK）；不涉及 PII。
- **可回滚**：迁移仅 ADD COLUMN（nullable），回滚=drop column；存量行为在管理员填槽前**逐字节不变**（Q2 兜底保证）。

## 5. 数据模型（迁移 V162——V161 号位已被 `V161__group_member_self_and_debt.sql` 占用）

```sql
-- V162__pricing_token_price_per_resolution.sql
ALTER TABLE pricing_rule
  ADD COLUMN token_price_per_resolution JSONB;  -- 仅 VIDEO+TOKEN 行；键⊆{480p,720p,1080p,4k}，值 ¥/百万token
-- 无数据迁移（存量行槽位空=全按通用价，行为不变）；无新索引（行命中链不变）；服务层校验同 est 先例
```

回滚：`ALTER TABLE pricing_rule DROP COLUMN token_price_per_resolution;`

## 6. 改动面（file structure 增量）

| 层 | 文件 | 改动 |
|---|---|---|
| 后端实体 | `billing/entity/PricingRuleEntity.java` | +`tokenPricePerResolution` JSONB 字段 |
| 后端计价 | `billing/service/PricingService.java` | `videoCost` TOKEN 分支按档取价（normalize 后取键，?? 通用价） |
| 后端配置 | `billing/service/PricingConfigService.java` | `validatePricingRule` 槽位校验；`applyRequest` 透传；`toExportItem`/`toItemRequest`/`generateTemplate` 带槽位（导入 null=不动语义） |
| 后端 DTO | `billing/dto/PricingRuleExportItem.java` + PricingRuleRequest/VO | +字段 |
| 前端 | `views/admin/PricingConfigView.vue` | TOKEN 分支表单 4 槽 + 列表列 + sanitize 透传 |
| 前端 | `api/billing.ts` | 类型 +`tokenPricePerResolution` |
| 测试 | `billing/**PricingServiceTest` / `PricingConfigServiceTest` | 见 §7 |

不动的关键面：`resolveRule` 命中链、`MediaGenTaskWorker` 结算入参（resolution 已透传）、`MediaBillingService`（chargeMedia/settle 链）、`estimateVideoYuan`、候选展开、`estimateRatePerSec`、`llm_usage_logs`。

## 7. 测试策略

**单元测试**：
1. `videoCost` TOKEN：档位命中（4K→4k 归一化取值）/ 未配档回落通用价 / 槽+通用全无 → 0 元（现状口径钉死）；`tokens÷1M×价` 精度（6 位 HALF_UP）。
2. 命中链×槽位：有参考行配 4k 档、任务带参考 4K → 用有参考 4k 价；只配无参考行时任务带参考 → fallback 行上取通用价（不跨行）。
3. `validatePricingRule`：合法 4 键通过；非法键/0/负数/SECOND 行带槽/CHAT 行带槽 → 400；`resolution` 非空仍拒（D6 回归钉死）。
4. 导出/导入往返：带槽导出→导入覆盖；导入项槽位 null→库中槽保留；`{}`→清空；旧格式文件（无字段）导入成功且不动槽。
5. 回归：SECOND 行为、est 估价、IMAGE、CHAT 闲时/缓存——现有单测全绿不动。

**集成**（真 PG）：Cdance2.0 两面各配 4 档 → 同价表下提交 4K 任务 mock usage→实耗=4k 档价×token÷1M；HOLD 多退少补腿金额随档位变。

**人工测试标记**（需真人+真渠道，自动化覆盖不了）：
- admin 在价表页给 seedance2.0 配「有参考/无参考 × 通用+4 档」全套 → 表单/列表显示正确；
- 真跑 seedance2.0 一条 4K 任务 → Ark 回传 token、扣费单价=4k 档、流水/账单金额对得上；改跑 720p（未配档）→ 按通用价扣；
- 导出 JSON 人工核对槽位字段；旧导出文件导入不动已配槽位。

## 8. 边界与不做

- **不**给 SECOND 加回分辨率多行/下拉（D6 口径不变，`resolution` 列恒 null）。
- **不**配输入/输出双价（Q1；Ark 仅回总量。将来 Ark 拆分回传再扩）。
- **不**由每百万价派生预估（Q3；费率表 seedance 专属，耦合供应商不做）。
- **不**动图片计价、候选展开（仍参考面×2）、`estimateRatePerSec` 费率表、D9 偏差监控。
- **不**改「缺价=0 元」现状口径（VTR-2；与预估「估价 0 不拦」一致——收紧为 fail-closed 属另一议题，不在本期）。
- **不**加「档位配齐」强制校验（Q2 回落语义即兜底；配齐提示走 UI 软提示可选，非本期必须）。
- legacy `refFileId` 通道（[MediaGenTaskService.java:212-216](../../../backend/src/main/java/com/superprogrammer/media/service/MediaGenTaskService.java#L212-L216)）前端已无生产者（死代码）；**若将来复活，必须保证其转成 `kind=="video"` 附件**，否则 hasReference 误判落到无参考价行。
- 画布空参兜底 `resolution||'720p'`：估价与提交成对同默认无漂移（已实证），不另改。

## 9. 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-08-27 | 建立规格（VTR-1~7，Q1~3 拍板） | 7_积分系统.md 未解决：seedance2.0 每百万价随分辨率不同，价表/扣费表达不了 |
| 2026-08-27 | **实现完成**（plan C1~C5）：V162 迁移+实体 / videoCost 按档取价（脏 JSON WARN 回落）/ 配置侧校验+导入三态+模板 / 前端表单 4 槽+列表列。单测 36/36+53/53、vue-tsc 0、build 过。人工测试 4 项待 P4（见[测试方案](../测试方案/视频TOKEN分辨率分档计价测试方案.md)）——过审后回写主规格变更记录并勾销 7_积分系统 未解决项 | 用户批准计划进 Phase 3（「批，进phase3吧」） |

## 10. 术语表

| 术语 | 大白话 | 案例 |
|---|---|---|
| 每百万价 | 模型每 100 万 token 收多少 ¥ | 4K 档 ¥111/百万 × 用了 55 万 token ≈ ¥61 |
| 伪 token | 视频模型把像素×帧×时长折算成的计数单位，非文字分词 | Ark 回 usage.total_tokens，与文本 token 不可加总 |
| 档位槽 | 一行价表里按分辨率各存一个价的小格子 | `{"4k":111.2}`=这行 4K 单独一个价 |
| 通用价 | 没单列的分辨率都按它算的兜底价 | 只配通用 ¥12，480p~4K 全按 ¥12/百万 |
| 回落 | 要用的档没配，退回用兜底价 | 4K 没配 → 按通用价扣 |
| HOLD 预扣 | 提交任务先按预估扣一笔，完工多退少补 | 预扣 50 积分，实耗 61 → 补扣 11 |
