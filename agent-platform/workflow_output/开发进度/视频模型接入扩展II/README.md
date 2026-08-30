# 开发进度 · 视频模型接入扩展II（MiniMax H3 全家 + HappyHorse 1.1 三形态，ai.ctaigw.cn 中转）

> 2026-08-30。规格 `docs/specs/视频模型接入扩展II-H3与HappyHorse中转接入.md`；计划 `docs/plans/视频模型接入扩展II-H3与HappyHorse中转接入.plan.md`（Chunk A-H）。
> 测试方案：`docs/测试方案/视频模型接入扩展II测试方案.md`（M1-M6 联动自动化已实现 + N1-N8 真中转人工验收待用户填 key）。
> 进度明细：`开发进度1.md`（A-D）/ `开发进度2.md`（E-H）/ `开发进度3.md`（追加：全局默认视频模型）。

## 模型清单（6 个）

| 模型 id | 形态 | 输入 | 计价（中转价=官网非折扣价） |
|---|---|---|---|
| minimax-h3 | 生成 | 提示词+9图/3视频/3音频 | 768p ¥0.5/s，2K ¥0.8/s |
| minimax-h3-context-ir | 提示词增强（输出文本） | 提示词+参考素材 | CHAT：入 ¥5.8/M + 出 ¥23/M token |
| minimax-h3-regeneration | 2K 再生成 | 仅源任务（7 天内成功的 H3 生成） | ¥0.3/s × 源时长，锁 2K |
| happyhorse-1.1-t2v | 文生 | 仅提示词 | 720p ¥0.9/s，1080p ¥1.2/s |
| happyhorse-1.1-i2v | 图生 | 提示词+恰 1 首帧图 | 480p ¥0.45 / 720p ¥0.9 / 1080p ¥1.2 每秒 |
| happyhorse-1.1-r2v | 多图参考 | 提示词+1-9 图 | 720p ¥0.9/s，1080p ¥1.2/s |

## Chunk 总览（8 chunk 全部完成）

- **A** V166 种子（`d1140c30`）：2 provider 行（**key 留空**待管理员填）+ 6 价表行；happyhorse 行显式 `queryEndpoint`（中转查询 `/v1/tasks`，默认推导是错的）。
- **B** DTO+能力层（`9ae3894`）：MediaSubmitRequest +sourceTaskId/prompt 可选；能力分档 HH 三形态 + minimax 再生成档（全零输入锁 2k、i2v ratios 空列表=前端藏比例）。
- **C** Dashscope 三形态（`fd4ef49`）：t2v 纯文本 body / r2v 多图 reference_image / i2v first_frame 恰 1 张。
- **D** Minimax 附属端点（`96e31438`）：端点推导路由（`/video_generation` 尾换 `/h3_context_ir`、`/video_regeneration`，config 可覆盖）；context_ir 结果探测（task_type 或「有 prompt 无 url」启发式）。
- **E** 提交分流+估价（`51a314e8`）：doSubmit 分流（再生成 requireRegenerationSource 七连校验 / context-ir 走 CHAT 估算公式 ceil(字×0.75)+图×1500 入、4000 出）；estimate 端点 +promptChars。
- **F** 结算分流+resultText（`6e95040c`）：worker CONTEXT_IR 成功 → storeText 落 .md + settleMediaSuccess 14 参（CHAT 双腿实 token 多退少补）+ fail-closed 退款；文本内容不进审计（只记长度）。
- **G** 前端（`c52d486e`）：再生成源任务选择器模式 / context-ir 精简表单+文本结果展示下载 / 能力驱动显隐修复（越界回退=能力首项，修掉 720p 脏值坑）/ 画布过滤附属档 + i2v 隐藏比例；测试 M1-M5。
- **H** 全量验证+文档（本提交）：后端 mvn test **2683/2683 绿**；前端 npm test **962/962 绿** + vue-tsc/build 过；本地起后端 V166 落库实测（模型目录/估价公式/再生成校验，见开发进度2）；feature-map/user-ops/3x 问题单回填。

## 关键设计决策

1. **模型 id 后缀路由**：平台模型 `minimax-h3-context-ir`/`-regeneration` 决定能力/计费/端点；出站 body.model 恒为基座名 `minimax-h3`（官方三接口同 model 名）。
2. **context-ir 用 CHAT 价表行**：输入/输出分价 5.8/23 每百万，VIDEO 的 TOKEN 单价模式表达不了 → 该模型出现在聊天价表页（模型名自解释）。
3. **再生成源 id 双形态**：config 存平台 id（留痕）+ sourceArkTaskId（上游 id 字符串，出站 source_task_id 用）。
4. **task_type 零迁移**：media_gen_tasks.task_type 是 VARCHAR(16) 无 CHECK，CONTEXT_IR/REGENERATION 直写。
5. **输入侧费用不建模**（规格 §8）：参考素材不计费，平台按输出秒数/token 收积分；中转账单输入侧自行核对。

## 规格偏离（已回填）

- context-ir 结果识别：plan 原定 queryTask 接口重载 → 实际 parseQueryResult 内 `task_type` 字段+启发式双保险探测，零接口改动（开发进度1 记录）。

## 人工验收（P4，需用户真实 key）

1. 设置→全局模型供应商：两行填中转 key →「测试」按钮过
2. `docs/测试方案/视频模型接入扩展II测试方案.md` N1-N8：H3 生成 768p/2K、Context-IR 增强（结果=文本+下载 .md+token 对账）、再生成（选源任务→2K 成片）、HH 三形态各一条、画布选 HH 模型、流水金额与价表对账

## 回滚

- V166 自带回滚 SQL（注释内，删 6 价表行+2 provider 行）；代码逐 commit revert。
