---
description: "C3 Agentic 有界循环检索（激活休眠代码）+ LLM QueryPlanner 的实现计划（WP2）"
created-date: 2026-09-03
---

# Implementation Plan for WP2：多轮检索

> 上级索引：[知识库RAG检索能力增强.plan.md](知识库RAG检索能力增强.plan.md)｜规格：[§5 C3](../specs/知识库RAG检索能力增强设计.md)

## 坑点预判（WP2 内）

| 坑 | 规避 | 验证 |
|---|---|---|
| **零回归是本 WP 唯一大坑**：激活循环后覆盖足够的 query 走了多余轮次/输出漂移 | 循环入口条件=CoverageVerifier 判缺口；round0 结束即判，覆盖→直接返回（零开销路径不新增任何对象分配） | 黄金集基线对比：覆盖场景 rounds=0 且证据集与基线完全一致（按 nodeId+score 逐条断言） |
| 休眠代码与主链现实脱节：`RetrievalRouter.supplement()`/`NeighborExpander`/`CoverageVerifier` 写于早期，签名/DTO 可能对不上现状 | Step 1 先做「休眠代码对齐审计」：逐类核对引用的 Mapper/DTO 是否仍存在、语义是否漂移，产出对齐清单再改 | 审计清单入 PR 描述 |
| 补充 query 质量差→多轮白跑还烧钱 | supplement 生成的 query 复用 QueryExpansionService 既有 LLM 通道（释义改写），每轮 ≤3 条；轮间去重（与已用 query 集 hash 比对） | 单测：同 query 不重复检索 |
| 补检索不继承权限/版本范围→越权或跨版本召回 | supplement 显式接收 round0 的 KB/权限/版本过滤参数（规格 §5.1「继承原范围」），不重新解析 | 单测：受限用户补轮结果不含无权/旧版文档 |
| LLM 规划器超时阻塞主链 | 独立 2s 超时（CompletableFuture+orTimeout），失败/超时→规则版 QueryPlan 原样返回；开关默认 false | 单测超时回退；关开关零调用 |
| trace 新增 round 维度破坏旧消费方（前端调试面板/评测读取） | RagTraceContext 加可选字段（rounds 列表），旧读取路径不感知；调试面板增量渲染 | 兼容性单测：无 round 字段时面板正常 |
| 循环与 C1 step6.5 执行顺序混乱（边被多次带出） | 钉死：循环全部结束→step6.5 一次（主计划与规格 §3.2 已钉，本 WP 编排器必须留 step6.5 挂点） | 集成测试：多轮+有边场景带出仅一次 |

## 实现步骤

- [x] **Step 1：休眠代码对齐审计**
  - **目标**：摸清三个休眠类现状差距，出对齐清单
  - **动作**：核对 `RetrievalRouter`（supplement 签名）、`NeighborExpander`（扩展输入/输出）、`CoverageVerifier`（判定依据的 query 子意图来源）与现行 Mapper/DTO/RagRetrievalService 主链的匹配度；列「可直接用/需改造/需重写」三类清单；本步零代码或仅删过时死代码
  - **文件**：`retrieval/RetrievalRouter.java`、`context/NeighborExpander.java`、`context/CoverageVerifier.java`（只读审计）
  - **依赖**：无｜**验证**：清单落本 plan 备注，评审通过再动 Step 2
  - **审计清单**（2026-09-03，逐类核对）：
    - **RetrievalRouter——绕开不激活**。`supplement()` 走 `OpenSearchRetrievers.retrieve`，但现状主链召回=PG（`queryMapper.denseRecallL0`+`fetchL2Children`+`bm25HitsJieba`），OpenSearch 仅 P2 影子双写未生产承载——补轮走它=数据源/语义漂移。Step2 编排器直接复用主链 PG 召回（从 RagRetrievalService 抽 `recallForQuery(query, kb, visible, plan)` 复用单元）；RetrievalRouter 零引用纯死代码，**保留不删**（P2 影子链语义完整，删除收益零）
    - **NeighborExpander——可直接用**。纯函数（selected+neighborsMap+authorized→扩展集）零依赖零漂移；缺数据源：Step3 补 PG sibling 查询（同 parentId 序号相邻），authorized=既有 visible docIds
    - **CoverageVerifier——需改造**。`missing(required,covered)` 集合差可直接用；**required 子意图来源缺失**：现行 QueryPlan 无子意图字段（仅 queryType/answerShape/filters/strategies/…）。规则版退路：仅 EXACT 类（带 filter）required=filter 语义命中（如 version=V2.1 → 证据文本须含 V2.1），其余 queryType required=空→覆盖即足→rounds=0（零回归门）；LLM 版子意图由 Step4 LlmQueryPlanner 供给。`maxRounds(highAccuracy)` 语义漂移→弃用，改 `rag.retrieval.max-rounds` 配置（已有 CoverageVerifierTest 同步改）
    - **RagTraceContext——偏离预警**：该类只存 ID/用途（WP1 Step2 偏离先例），rounds 计量不动它：走 writeTrace tokenBudget JSON 扩 rounds 字段+编排器 MDC info 日志（轮次/缺口/补充 query/新命中），Step2 实现注定稿
    - **step6.5 挂点**：现状已钉「gather 循环后单次执行」（WP1 Step2 落地），补轮并集须在 step6.5 前——编排器插点=per-KB gather 循环内每库召回完成后（同库内并集），或循环后并集再 step6.5，Step2 设计时定（测试锚：多轮+有边带出仅一次）
    - **INSUFFICIENT 移位**：现状判定在 grounded facts 空（RagRetrievalService:364）与 GroundedAskResult——发生在证据装载**之后**；循环只管召回并集，天然在判定前完成 → **零代码移动**（plan ②项「移到循环耗尽后」已被现状满足，仅加轮次耗尽语义）

- [x] **Step 2：IterativeRetrievalOrchestrator 有界循环**
  - **目标**：round0 之外的补充轮可运行、有界、可关
  - **动作**：①新编排类：round0（现有完整管道）→CoverageVerifier 判缺口（依据 QueryPlan 子意图 vs 已命中证据覆盖）→缺口则 supplement 产 query（≤3，去重，继承范围）→重跑 step3-6（召回+融合+rerank）→候选并集（by nodeId 去重，score 取最高）→再判→MAX_ROUND（rag.retrieval.max-rounds 默认 2）或预算守卫（证据 token 累计超预算）跳出；②拒答 INSUFFICIENT 判定移到循环耗尽后；③RagTraceContext 加 rounds 计量；④配置开关 max-rounds=1 时等价单轮（即基线）
  - **文件**：`retrieval/IterativeRetrievalOrchestrator.java`（新）、`RagRetrievalService.java`（重构挂接，step6.5 挂点预留）、`RetrievalRouter.java`（对齐激活）、`CoverageVerifier.java`（对齐激活）、`RagTraceContext.java`、Test ×2
  - **依赖**：Step 1｜**验证**：单测——覆盖→rounds=0；缺口→round1 补齐；轮次耗尽仍缺→INSUFFICIENT；预算守卫跳出；去重
  - **实现注（2026-09-03）**：
    - 编排器 `expand()` 落地；补充 query=**未覆盖 filter 值本身**（锚点即 query——原 query 已含该值，LLM 改写无增益只烧钱，`expand(allowLlmExpansion=false)` 仅向量化）。轮次上限+无进展守卫（补轮零新候选即停）+query 去重（已用集含原 query）三重界。**预算守卫未单列**：轮次耗尽即停+per-round cap 已界住开销，token 预算在 fitToBudget 装载端恒定不变
    - **挂接两路径**：抽 `runIterativeLoop` helper 单库 /retrieve 与多库 chat EvidenceResult 共用（首版只挂多库——单库测试 rounds=0 揪出）；插点=硬阈拒答后、step6.5 前（LOW_CONFIDENCE 不因补轮得救；灰区可被并集 bestSim 重算救出）
    - **②偏离（Step1 审计已记）**：INSUFFICIENT 现行判定本就在证据装载后（facts 空），零代码移动；轮次耗尽语义=stillMissing 非空+现行拒答路径原样
    - **③偏离（Step1 预警兑现）**：RagTraceContext 不动，rounds 走 TokenBudgetVO 新字段（预算 JSON 落 trace）+编排器 MDC info 日志
    - **RetrievalRouter 绕开（Step1 审计）**：补轮 `recallSupplement` 复用主链 PG 召回（expand→denseRecallL0/L1→gatherL2Candidates），不走 OpenSearch
    - CoverageVerifier required=filter 精确值且**排序输出**（Map.copyOf 无序，batch 取前 N 须稳定）；`RetrievalCandidate` implements CandidateText 统一判定面
    - 验证：Orchestrator 8 + CoverageVerifier 重写 5 + Service 2（SEMANTIC 零开销基线/EXACT 缺口补轮并集）=15；全量 2817/2817

- [x] **Step 3：NeighborExpander 激活**
  - **目标**：边界证据扩展相邻节点（表格截断/首尾段场景）
  - **动作**：①判定边界证据：TABLE 节点内容被截断标记、或证据位于文档首尾且长度 < 阈值；②扩展：按同文档 sibling 序号取相邻 L2 节点（PG 侧查询，OpenSearch 网关暂不扩展——`OpenSearchProductionRetrievalGateway` 补 NEIGHBOR 策略透传忽略，注释说明）；③扩展节点进证据需 rerank 过阈；④QueryPlanner NEIGHBOR 策略输出保持（现状已有）
  - **文件**：`context/NeighborExpander.java`、`RagRetrievalService.java`、`mapper/RagRetrievalQueryMapper.java`（sibling 查询）、Test ×1
  - **依赖**：Step 2｜**验证**：单测——表格截断证据扩出下一段；非边界证据零扩展
  - **实现注（2026-09-03）**：
    - ①口径落地：截断标记=content 含「已截断」（表格行/附件注入块/解析摘要同字样）**或** 证据位于同 parent 组首/尾且 content < `rag.retrieval.neighbor.short-content-chars`（默认 200）。**偏离**：Excel 行截断标记在 warnings 不入节点内容（行级 section 无标记可判），故 TABLE 专项判定不做——「已截断」字样口径已覆盖一切可判定截断
    - ②sibling 查询=`fetchSiblingRows(nodeIds)`：同 parent 全组（含自身），组内 id 序=文档序；文档有效性 JOIN 同关系查询（过期/已删/未建版本文档兄弟自然过滤）。**OpenSearch 网关 NEIGHBOR 透传注释未加**——网关侧无策略分支代码，无注入点可注释（Step1 审计已定 OpenSearch=影子链不承载）
    - ③过阈口径同 MAY_CITE：`keepMayAboveThreshold`（≥ round0 topK 最低分）；种子分=边界证据自身 rerankScore（DISABLED 直通模式天然过阈，真实 rerank 模式被重打分覆盖）；失败降级丢弃不伤主链
    - ④策略门=QueryPlan.strategies 含 NEIGHBOR（PROCEDURE 类）+ kill switch `rag.retrieval.neighbor.enabled`（默认 true）+ `max-nodes-per-query=4` 上限；两路径挂接（step6.5 后、step8 前，单库/多库同 helper `expandNeighbors`）
    - 验证：NeighborExpanderTest 3 + Service 4（边界判定静态 5 断言/表格截断扩下段/非边界零扩展/kill switch 零 sibling 查询）；全量 2824/2824

- [ ] **Step 4：LlmQueryPlanner（开关+降级）**
  - **目标**：LLM 生成 QueryPlan 可选启用
  - **动作**：①`query/LlmQueryPlanner.java`：输入 query+KB 上下文→输出结构化 QueryPlan（分类/子意图列表/filters/策略集，JSON schema 约束）；2s 超时；失败/超时/解析异常→规则版 QueryPlanner 结果；②`rag.queryplanner.llm.enabled` 默认 false；③子意图列表供 Step 2 CoverageVerifier 与 supplement 使用；④计费归户当前用户
  - **文件**：`query/LlmQueryPlanner.java`（新）、`query/QueryPlanner.java`（路由入口加开关分支）、`RagConfig`、Test ×2
  - **依赖**：Step 2（子意图消费方）｜**验证**：单测——正常规划/超时回退/JSON 解析失败回退/开关关闭零 LLM 调用；灰度：黄金集 A/B（开关开 vs 关 Recall/MRR 对比）

- [ ] **Step 5：基线回归门**
  - **目标**：证明激活循环没有破坏现状
  - **动作**：①黄金集全量跑：max-rounds=1（=基线行为）与 max-rounds=2（默认）两组；②覆盖场景 trace 断言 rounds=0+证据集逐条一致；③既有检索单测全量绿
  - **文件**：Test ×1（基线对比套件）
  - **依赖**：Step 2-4｜**验证**：两组指标输出落档；差异仅允许出现在「原 INSUFFICIENT 现补齐」的正向场景

## 联动点（WP2 专属细化）

| 触发 | 联动 | 边界 |
|---|---|---|
| max-rounds 调 1 | 行为=基线 | 默认 2；改 1 零迁移零代码即时生效 |
| LLM 规划器开 | supplement/CoverageVerifier 用 LLM 子意图 | 关→回退规则版子意图（QueryPlan 现状能力）；两开关独立 |
| 补轮命中新文档 | C1 边（step6.5）作用于并集 | 边带出只算一次；WP1 未合入时无此联动 |

## 验证汇总

- [ ] 单测新增 ~10；基线对比套件 1 套
- [ ] 黄金集：默认配置 Recall 不降、MRR 不降；正向改善场景记录
- [ ] 手测剧本：列表题缺一章→自动补轮召回；拒答题两轮仍缺→INSUFFICIENT 文案不变
