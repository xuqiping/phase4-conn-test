# 知识库RAG检索能力增强 · 功能 README

> 来源：[14x_知识库.md](../../人工测试问题/14x_知识库.md) 两项未解决 + 对标顶级知识库检索 7 项差距（C1-C7）。
> 实施记录：[开发进度1-9](开发进度1.md)｜规格：[specs](../../docs/specs)｜计划：[主 plan](../../docs/plans/知识库RAG检索能力增强.plan.md) + WP1-6 子 plan。
> 状态：六 WP 全部落（后端 2915/2915 + 前端 1103/1103 + vue-tsc 0）；**WP3 Step4 影子对比挂起**（等用户给选库+黄金集）；人工测试 Phase4（[测试方案 S1-S17+L1-L8](../../docs/测试方案/知识库RAG检索能力增强测试方案.md)）。

## 用户地图（谁用 · 场景 · 效益）

| 能力 | 谁用 | 场景 | 效益 |
|---|---|---|---|
| **C1 文档关联** | 库管理员（canManage/owner） | 文档互引（「详见」「依赖」）建关系边；系统还自动建议关系，一键采纳 | 问答时答案自动带出关联文档内容——不用自己想起「还有一篇相关的」 |
| **C2 附件召回** | 所有提问者 | PPT 附 PDF 附件、说明文字入库 | 附件按描述参与召回，命中后原文按需注入——附件不再是检索盲区 |
| **C3 多轮检索** | 所有提问者 | 复杂问题一次检索覆盖不全 | 系统自动判断覆盖缺口、追加改写检索（有界循环），覆盖不足的召回率实测 Recall 0.75→1.00 |
| **C4 上下文嵌入** | 库管理员 | 切片入库时逐块带 LLM 生成的前文定位语 | 「它/该公司」这类指代切片也能被独立召回；存量库可手动触发重建（带成本预估） |
| **C5 多模态** | 所有提问者 | 图表/截图/扫描件入库 | 图片转视觉向量参与检索，截图问题能搜到图（ColPali 通道预留，KB 级开关） |
| **C6 连接器** | 库 owner/admin | 团队 Wiki/静态站点/S3 网盘/WebDAV 定时同步 | 源端更新自动建新版本重索引、删除自动隔离（可选同步删）——知识库不再手动搬文件 |
| **C7 全局问答** | 所有提问者 | 问题跨多个库（「我们全部资料里……」） | 各库分别检索+汇总（map-reduce），跨库答案带引用与溯源，不再「只能选一个库问」 |

## 简要技术说明

- **检索主链**：`RagRetrievalService` → `LlmQueryPlanner`（查询规划）→ `RetrievalRouter`（向量/关键词/图片通道）→ `IterativeRetrievalOrchestrator`（C3 有界循环，`CoverageVerifier` 判缺口）→ `RelationGraphPostProcessor`（C1 关系扩展，≤150ms 批量）→ `RrfFusion` 融合 → 证据预算裁剪。
- **C2**：`AttachmentContentInjector` 召回后注入（文本直拼/图片走 VLM 描述，`AttachmentVisionCache` 缓存）；计费归文档属主。
- **C4**：`LlmContextualizer` 与 L1 摘要同批生成定位语随切片入库；`ContextualRebuildService` 存量重建（job 限速+确认框成本预估）。
- **C5**：IMAGE 表（V173）与文本向量分表，`RrfFusion` 第三列表；`ColpaliGateway` 三重闸（总开关/KB 开关/探活）。
- **C6**：`connector` 包十类——SPI 抽象 + URL 站点（BFS 同域 ≤2 深）/S3（AWS SDK，url-connection 传输）/WebDAV（PROPFIND）；凭证 AES-GCM 密文落库只写不读；SSRF 三重防线（SsrfGuard+手动重定向逐跳复检+每跳 URL 白名单）；`ConnectorSyncWorker` 乐观认领（FOR UPDATE SKIP LOCKED）+etag 差分四态（新增走 upload 全管线/变更走版本链/复活/重试）+单轮 50 动作预算+1 req/s 限速+连续 3 轮错误停调度。
- **C7**：`GlobalAnswerStrategy` 跨库 map-reduce，`KbSummaryWorker` 维护库级摘要，`CitationChecker` 引用核验。
- **缓存一致性**：`AnswerCacheService` evidence hash 扩边（含关系边+附件注入内容）——建边/换附件后旧缓存自动失效。
- **数据模型**：V170 关系边 / V171 节点上下文列 / V172 库摘要 / V173 图片向量表 / V174 ColPali 开关 / V175-176 连接器与账本（详见 [feature-map](../../docs/feature-map/知识库RAG检索能力增强.feature-map.md) 表注解）。

## 验证口径

- 单测：后端 2735→**2915**（+180）、前端 1093→**1103**（+10）全绿；vue-tsc 0 错。
- 合成黄金集：多轮 maxRounds 1→2 Recall 0.750→1.000、MRR 0.750→1.000（真实库跑批 Phase4）。
- 人工测试：测试方案 S1-S17（各 WP 手测剧本）+ L1-L8（联动含反向/半选/批量），Phase4 执行。
