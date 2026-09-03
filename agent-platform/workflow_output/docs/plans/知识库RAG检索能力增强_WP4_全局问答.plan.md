---
description: "C7 全局语义问答（L-KB 库级摘要 + map-reduce 分支）的实现计划（WP4）"
created-date: 2026-09-03
---

# Implementation Plan for WP4：全局问答

> 上级索引：[知识库RAG检索能力增强.plan.md](知识库RAG检索能力增强.plan.md)｜规格：[§9 C7](../specs/知识库RAG检索能力增强设计.md)

## 坑点预判（WP4 内）

| 坑 | 规避 | 验证 |
|---|---|---|
| L-KB 生成成本失控（大库数百文档 L1 全量） | 触发条件节流（文档数变更 ≥10% 或距上次 >7 天）；分批 ≤20 L1/次；低峰 cron；失败重试上限 3 后置 ERROR 待手动 | 单测触发条件矩阵 |
| 全局回答引用漂移（模型编造未参与 map 的文档） | 文档级 CitationChecker：reduce 输入的候选引用集=实际参与 map 的 docId 白名单，越界→重生成→仍失败 abstain（复用既有三段式） | 单测越界引用拒 |
| GLOBAL 分类误判（普通问题走了 map-reduce 又慢又泛） | 规则先行（「总结/趋势/整体/全库」词表），LLM 规划器开时以其分类为准但置信低回落规则；调试面板显示分类依据 | 单测误判回落；黄金集既有问题全不走 GLOBAL |
| 混合问题（全局+细节）只答一半 | GLOBAL 分支完成后检测未覆盖子意图（复用 WP2 CoverageVerifier）→自动补一轮局部检索，两类证据分列 | 单测混合分支 |
| L-KB 摘要泄露面 | 摘要仅注入 prompt，任何 API 不下发（VO 不含）；保密库成员全局问答走 RAG 出口语义不变 | 接口扫描测试无 summary 字段 |
| map-reduce 延迟（50 文档×批 15=4 批串行 LLM） | 批间并行（有界 2 并发）；预算内优先大摘要；超时 30s 兜底降级「仅 L-KB 概览+提示缩小范围」 | 延迟计量；降级路径单测 |

## 实现步骤

- [x] **Step 1：L-KB 数据层与生成 Worker**（commit f4e8ecfd，2847/2847）
  - **目标**：库级摘要自动生成、节流、不出库
  - **动作**：①迁移 `V1xx__knowledge_base_summaries.sql`（规格 §9.1 DDL）；②`global/KbSummaryWorker.java`（@Scheduled 低峰默认 04:30）：触发判定（变更 ≥10% 或 >7 天）→取库内全 ACTIVE 文档 L1 摘要分批 ≤20 → map 每批浓缩要点 → reduce 合成库级摘要 ≤2000 字+主题清单 → 写表（新 version 行）；③失败重试 3 次→状态 ERROR；④摘要读取仅 Service 内部，无对外 VO
  - **文件**：迁移 ×1、`global/KbSummaryWorker.java`（新）、实体/Mapper ×1、Test ×2
  - **依赖**：无（L1 现状已有）｜**验证**：单测——触发矩阵/分批/重试上限/无 L1 文档库跳过；API 层无暴露 ✅（KbSummaryWorkerTest 3 例）
  - **实现注（偏离）**：表加 status 列（READY/ERROR——计划动作③要求 ERROR 态而规格 DDL 未列，小偏离已注）；实体独立不继承 BaseEntity（本表无 deleted 列且 version 为业务版本，继承带 @TableLogic 会拼 deleted=0 炸 SQL）；重试=整体生成重试（map+reduce 一体计一次 attempt）

- [ ] **Step 2：GLOBAL 分类与 map-reduce 分支**
  - **目标**：「总结全库」类问题走全局回答
  - **动作**：①`QueryPlanner` 增 GLOBAL 分类（规则词表；LLM 规划器开时优先其结果）；②`global/GlobalAnswerStrategy.java`：取该库 L1 全集分批 ≤15 → map 提要点（每批一次 LLM）→ reduce 合成答案；答案开头 L-KB 概览段（标注来源不占引用编号）；③引用降文档级：CitationChecker 增文档级模式（白名单=参与 map 的 docId）；④kbIds 多选取首库+提示；⑤批间并行 ≤2、30s 超时降级；⑥trace 记 global 分支与批数
  - **文件**：`query/QueryPlanner.java`、`global/GlobalAnswerStrategy.java`（新）、`RagRetrievalService.java`（分支挂接）、`service/internal/CitationChecker.java`、Test ×2
  - **依赖**：Step 1｜**验证**：单测——GLOBAL 分类/引用白名单/越界拒/多库提示/超时降级；黄金集普通问题不进 GLOBAL

- [ ] **Step 3：混合问题跟进轮**
  - **目标**：全局+细节混合问题两类证据齐答
  - **动作**：①GLOBAL 完成后 CoverageVerifier 检测剩余子意图（依赖 WP2 设施；WP2 未合入则用规则缺口判定）→补一轮局部检索（现有管道）→细节证据分列引用；②答案结构：概览段（L-KB）+要点段（map）+细节段（局部检索）
  - **文件**：`GlobalAnswerStrategy.java`、`context/CoverageVerifier.java`（复用）、Test ×1
  - **依赖**：Step 2、WP2 Step 2（可后并）｜**验证**：单测混合 query 三段结构

- [ ] **Step 4：前端与调试可见性**
  - **目标**：全局模式可辨识、可调试
  - **动作**：①检索调试面板：GLOBAL 分支标识+批数+参与文档数显示；②问答界面全局回答的文档级引用渲染（[1]《标题》无段落锚点，可点跳文档）；③多库提示文案
  - **文件**：`RetrievalDebugPanel.vue`、chat 引用渲染组件、Test ×1
  - **依赖**：Step 2｜**验证**：vitest 渲染分支；手测「总结这个库」全流程

## 联动点（WP4 专属细化）

| 触发 | 联动 | 边界 |
|---|---|---|
| 文档大量新增（≥10%） | 下个低峰 L-KB 重生成 | 中间期用旧版概览（标注生成时间）；生成失败旧版继续服务 |
| 文档删除 | 下轮重生成自然剔除 | 概览不实时跟随（容忍度=节流窗口）；引用只指向现存文档 |
| 保密库成员问全局 | 答案照常 | L-KB 不下发 API；引用无下载钮（保密链路现状） |
| GLOBAL + C1 边 | step6.5 不作用于 GLOBAL 分支（map 集非「检索命中」） | 混合跟进轮的局部检索部分正常走边 |

## 验证汇总

- [ ] 单测新增 ~8
- [ ] 手测剧本：「总结整个库」三段结构+文档级引用；50 文档库延迟 <30s；普通问题零影响（黄金集回归）
