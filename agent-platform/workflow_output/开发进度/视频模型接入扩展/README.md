# 开发进度 · 视频模型接入扩展（五模型接入：Seedance fast/mini/2.5 + MiniMax H3 + 百炼 HappyHorse）

> 2026-08-27~28。规格 `docs/specs/视频模型接入扩展.md`；计划 `docs/plans/视频模型接入扩展.plan.md`（RA~RH）。
> 测试方案：`docs/测试方案/视频模型接入扩展测试方案.md`（H1-H11/L1-L6，随 VD(修复VI) commit 落盘）。

## RA · worker 按 provider 行 protocol 路由适配器（commit `0d610bf6`，MVR-1）

- `llm_providers.protocol` → MediaGenProvider bean（getId()）路由；blank 回落 ark；未注册 → FAILED「视频协议 X 未注册适配器」。
- V163 迁移：存量 VIDEO 行空 protocol 回填 ark。
- 测试：路由单测（Ark 既有行/minimax/dashscope/未注册/blank 回落）。

## RB · 分辨率字典扩 6 档（commit `3075f680`，MVR-2）

- 计价/预估白名单 4 档（480p/720p/1080p/4k）→ 6 档（+768p/2k，MiniMax 系）；存储/结算双侧同 `normalizeResolution`（大写归一小写）。

## RC · SECOND 秒价分档 V164（commit `032ab851`，MVR-3）

- `pricing_rule.price_per_second_per_resolution` JSONB 行内槽（回落通用秒价语义，零数据迁移）；FieldStrategy.ALWAYS 钉住「导入 {} 清空」；est/token/second 三槽互不串用例。

## RD · Seedance 能力默认修正（commit `89b36a7f`，MVR-4）

- capability 分支序：minimax/hailuo → happyhorse/dashscope → seedance-2-5（含 -2.5 写法）→ seedance-2（fast/mini 收 480p/720p）→ seedance-1 → 保守兜底。

## RE · MinimaxVideoProvider（commit `5e01a9e3`，MVR-5）

- Hailuo v2 适配器：建任务/查态/失败话术脱敏截断/密钥只进头；testConnection 走零成本探测；usageTokens=计费秒数。
- Provider 范式：WebClient 指纹缓存（providerId|endpoint|密钥）、CONNECT 10s/RESPONSE 30s、WCE 不链 cause。

## RF · DashscopeVideoProvider（commit `c945063b`，MVR-6）

- HappyHorse 1.1 图生视频适配器：官方 input/parameters 包裹（media first_frame 恰 1 张必选）、X-DashScope-Async 头必带、resolution 出参大写映射、duration [3,15] 夹取、无 ratio；查态 base 推导（config queryEndpoint > 截 /api/v1/ 拼 tasks > scheme://host）；UNKNOWN=过期终态失败（24h 可查）。
- **规格偏离**：plan 原写 supportsGenerateAudio=true（照抄 RE 模板）；P2 核官方文档无音频参数、输出 MP4 无音轨 → 定 **false**（代码注释+能力测试+本记录三处落字）。
- capability：maxImages 1/maxVideos 0/maxAudios 0、480p/720p/1080p（1080P 官方已核实支持，平台默认回落 720P 对齐价表字典）。
- 测试：18 用例（MockWebServer）+ capability 2 用例。

## RG · 模型管理 VIDEO 行 + V165 + 配价口径（commit `1f6db3c3`，MVR-7/8）

- 前端 ProviderManageTab：VIDEO 行协议下拉可见（ark/minimax/dashscope 三选，类别切 VIDEO 自动落 ark），端点占位随协议切换；llm.ts ProviderProtocol 扩宽（DB 实存 'ark'，原双值联合与现状不符）。vue-tsc 0。
- V165：①存量 VIDEO 行 protocol 归一——V163 只回填 NULL/''，历史行落库 chat 默认 OPENAI_COMPATIBLE → 路由必失败，非白名单一律归 'ark'（本地库实测命中 id=6 行）；②ark 行 models 追加官方列表（2026-05.29 版）确认的两 ID（doubao-seedance-2-0-260128/fast-260128），幂等 jsonb 追加，双跑零漂移。mini/2.5 官方未列 ID **不猜入库**，手册走控制台复制手工加。
- user-ops：积分计费手册 2026-08-28 增补（新供应商行步骤/ark 补 ID/配价行清单——Seedance 系 TOKEN×2 行、H3 SECOND 768p/2k、HappyHorse SECOND 480p/720p）；SeedDance 手册变更记录（两族能力差异、fast 档上限）。

## RH · 文档收尾（本提交）

- feature-map 增补（积分计费：路由+SECOND 槽+6 档字典；设置模块：VIDEO 协议下拉；画布：VE 能力联动）；7_积分系统挂「待人工验证（视频模型扩展）」；变更记录补行；本 README。

## 人工测试标记（真 key 真渠道，P4）

五模型各真跑一条（fast/mini 720p、2.5 4K 30s、H3 768p+2k、HH 480P/720P）→ 流水金额与价表口径对账；H3 参考视频、HH 多图参考链；旧价表 JSON 导入不清 SECOND 槽；Ark 存量任务查态回归（protocol=ark 路由命中）。

## 验证与回滚

- 后端 mvn test 各 chunk 全绿（RA 路由/RB 字典/RC 计价/RD-RE-RF provider+capability）；前端 vue-tsc 0。
- 回滚：V163/V164/V165 均带注释回滚 SQL（手册记档）；代码逐 commit revert。V165 本地库已手工预应用（幂等，重启后 Flyway 正式入册）。
