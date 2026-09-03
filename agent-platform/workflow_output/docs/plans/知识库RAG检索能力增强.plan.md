---
description: "知识库 RAG 七项检索能力增强（C1 关联图/C2 附件召回/C3 多轮检索/C4 上下文嵌入/C5 多模态/C6 连接器/C7 全局问答）的实现计划总索引"
created-date: 2026-09-03
---

# Implementation Plan for 知识库RAG检索能力增强（总索引）

> Phase 2 产出。Phase 3 逐步勾选执行。只含伪代码，不含真代码。
> 来源规格：[../specs/知识库RAG检索能力增强设计.md](../specs/知识库RAG检索能力增强设计.md)
> **规模控制**：本文件只保留索引+全局横切项；各 WP 细化在子 plan，逐个 ≤5000 tokens。

## 背景与目标

规格 C1-C7 七项能力，按价值/风险分 6 个工作包（WP）：

| WP | 子计划 | 内容 | 优先理由 |
|---|---|---|---|
| WP1 | [知识库RAG检索能力增强_WP1_关联与附件召回.plan.md](知识库RAG检索能力增强_WP1_关联与附件召回.plan.md) | C1 关系图 + C2 ATTACHMENT | 直接闭 14x 两个未解决项 |
| WP2 | [知识库RAG检索能力增强_WP2_多轮检索.plan.md](知识库RAG检索能力增强_WP2_多轮检索.plan.md) | C3 有界循环 + LLM 规划器 | 激活休眠代码，回归风险集中 |
| WP3 | [知识库RAG检索能力增强_WP3_上下文嵌入.plan.md](知识库RAG检索能力增强_WP3_上下文嵌入.plan.md) | C4 LLM 定位表 | 索引管线改动，与 WP2 解耦 |
| WP4 | [知识库RAG检索能力增强_WP4_全局问答.plan.md](知识库RAG检索能力增强_WP4_全局问答.plan.md) | C7 L-KB + map-reduce | 复用 L1 与 WP2 循环设施 |
| WP5 | [知识库RAG检索能力增强_WP5_多模态.plan.md](知识库RAG检索能力增强_WP5_多模态.plan.md) | C5 图片原生向量 + ColPali 预留 | 协议扩展 |
| WP6 | [知识库RAG检索能力增强_WP6_连接器.plan.md](知识库RAG检索能力增强_WP6_连接器.plan.md) | C6 URL/S3/WebDAV 同步 | 独立新模块，最后落 |

执行顺序 WP1→WP2→WP3→WP4→WP5→WP6；WP3 与 WP2 无依赖可交错，WP4 依赖 WP2 的循环与子意图设施，WP5/WP6 相互独立。

## 全局技术坑点（各 WP 最毒的坑，细化见子 plan）

| 坑 | WP | 规避 |
|---|---|---|
| 答案缓存（AnswerCache evidence hash）不含关系边/附件注入内容 → 建边或换附件后旧缓存答案陈旧 | WP1 | evidence hash 计算扩边：+ 关系边集合 hash + 附件注入内容 hash（规格 §C1/C2 未明说，**本计划补钉**） |
| C3 激活循环引入回归：覆盖足够时必须 0 轮 0 开销，行为与基线逐字节一致 | WP2 | 黄金集基线对比测试：覆盖场景下 trace 断言 rounds=0、输出与基线一致 |
| 存量可选重建（C4）一次点错 → 全库重嵌 token 风暴 | WP3 | 重建确认框带成本预估（chunk 数 × 单价）+ 单库并发 job 限速 + 索引队列背压 |
| halfvec 维度固定（HalfVecUtil.DIM）——多模态模型维度 ≠ DIM 直接写库炸 | WP5 | 写前校验维度，不符则该库禁 IMAGE 通道（配置降级，不报错） |
| 同步大量文档瞬间打爆索引队列/LLM 计费 | WP6 | 每轮 ≤50 文档 + 索引 job 入队限速 + 失败不重试死循环上限 |

## 安全检查清单（P3 逐项验证）

- [x] **鉴权**：C1 关联管理仅 canManage/owner；带出文档 canRead 复校；C6 连接器管理仅 owner/canManage（落地=isOwnerOrAdmin 治理级，较清单收紧；403 用例见 WP1/WP6 测试）
- [x] **权限穿透**：C1 step6.5 逐文档复检+静默丢弃（防侧信道：不报错不提示存在）——RelationGraphPostProcessor 批量复检，L1 边界用例锁死
- [x] **SSRF**：C6 全 URL 过 assertFetchSafe（含重定向后地址复检）；内网/非常规协议拒绝——落地名 `SsrfGuard.validate`+SafeHttpFetch 手动跟跳逐跳复检（五渗透字面量+302 跳内网两用例）；S3 endpoint 建客户端前先验
- [x] **加密**：C6 凭证 AES-GCM 落库（复用 AesEncryptService）；日志/trace/异常不落明文凭证——VO 零 config 字段只写不读+worker `sanitize` 剥 URL userinfo
- [x] **输入校验**：C2 描述 ≤4000 字必填、注入上限 8000 字；C6 config_cipher 结构校验（四态用例）；C1 边自环/跨库/语义等价重复拒绝
- [x] **审计**：C1 建边/删边/建议采纳、C6 连接器增删改/启停/立即同步，均 @AuditLog（connector_create/update/delete/enable/disable/sync）
- [x] **错误处理**：保密库 403 话术复用（不泄漏边存在性）；C6 同步错误摘要不含内网地址明文——内网地址不脱敏口径偏离（owner/admin 自配置已知，见 WP6 Step5 实现注②），凭证/userinfo 已脱

## 性能考虑与验证计划

- [x] step6.5 关系扩展 ≤150ms：批量 IN 单查（禁逐 doc 查边）+ 权限判定复用已载 KB（WP1）——落地已验；P95 实测归 Phase4
- [x] 附件图片注入未命中缓存 +≤3s：VLM 调用 2.5s 超时→降级仅描述注入（WP1）
- [x] 循环只在缺覆盖触发：trace rounds=0 场景延迟与基线差 <5ms（WP2）——基线对比用例锁死（覆盖足够=0 轮 0 开销）
- [x] 每文档 C4 +1 次 LLM（与 L1 同批）；每图 C5 +1 次 embed——计费归户 docOwner（WP3/WP5）
- [x] 连接器 1 req/s 限速、单轮 ≤50（WP6）——FetchLimiter+MAX_ACTIONS_PER_ROUND 用例锁死
- [ ] Phase 4：检索调试 P95、问答 P95 与基线对比不回归；黄金集 Recall/MRR 门禁不降

## 功能联动点清单（含反向/半选/批量边界）

| # | 触发动作 | 联动对象 | 预期变化 | 必测边界 |
|---|---|---|---|---|
| L1 | 建边 MUST_CITE(A→B) | A 命中的检索 | B 强制进证据 | **删边后**下次检索立即不带 B；B 无读权→静默丢弃不报错；A、B 同为命中→只出现一次；缓存旧答案在边变更后失效重算 |
| L2 | 建边 MUST_BE_CITED(A→B) | B 命中的检索 | A 强制进证据 | 与已有 MUST_CITE(B→A) 语义等价→拒绝并提示；删任一方向另一方向不受影响 |
| L3 | 附件文档上传新版本（fileRef 变） | 图片视觉描述缓存 | 缓存失效重算 | 旧版本缓存不再命中（fileRef 在 key 内）；文本附件直接读新原件 |
| L4 | 保密库开 ON | C1 带出/C2 注入 | 注入内容仍进 RAG 答案；fileRef 下载 403 不变 | 成员全局问答（C7）正常；owner 全不受限 |
| L5 | 换 embedding 重建索引 | ATTACHMENT/IMAGE 向量 | 一并重生成 | 重建中检索可用旧索引；失败→job 可重试 |
| L6 | 连接器源端删除文档 | 本地文档 | 标记 ISOLATED（默认）非硬删 | ISOLATED 文档不召回；源端恢复→下轮同步自动恢复 ACTIVE；「源删同步删」开关关闭时永久保留 |
| L7 | 问全局类问题 | 检索分支 | 走 C7 map-reduce，引用降文档级 | 混合问题（全局+细节）→概览后自动补局部检索轮；多库选择→取首库+提示 |
| L8 | 关闭 rag.contextual.llm.enabled | 新索引 | 回退纯规则前缀 | 存量 LLM 版索引不受影响照常检索；重开→新文档重新生效 |

## 运维考量清单

| 类别 | 结论 | 说明 |
|---|---|---|
| 可观测性 | **做** | step6.5/循环轮次/附件注入/同步结果全进既有 RagTraceContext 与 BizMetrics；trace 加 round、channel 维度（消费方向后兼容：新字段可选） |
| 配置开关 | **做** | rag.retrieval.max-rounds、rag.queryplanner.llm.enabled、rag.contextual.llm.enabled、rag.attachment.inject.max-chars、rag.visual.colpali.enabled、KB 级 ColPali/连接器开关——出问题能关不用回滚 |
| 可回滚 | **做** | 全部新表/新列可 drop；modality 默认 'TEXT' 存量零变化；循环/LLM 开关默认关（C3 LLM 规划器）/开（循环有界），关=回到基线行为 |
| 限流/熔断 | **做** | VLM 注入 2.5s 超时降级；LLM 规划器 2s 超时回退规则；连接器 1 req/s；索引队列背压 |
| 运维入口 | **做** | 连接器「立即同步/查看错误/启停」；关系建议「忽略」；索引重建入口复用；ISOLATED 治理复用既有解除隔离 |
| 告警阈值 | **后续再说** | 连接器连续 3 轮 ERROR 状态置 ERROR 并在连接器 Tab 红标（站内可见即够，接告警中心后续） |
| 容量/性能 | **后续再说** | relations/embeddings 大表分区暂不做（量级 <百万）；connector_docs external_id 512 上限够用； embeddings modality 过滤必须走索引（加部分索引） |

## 整体验证（功能级）

- [x] 后端全量单测绿（基线 2735+，新增见各子 plan）→ 末次 **2915/2915**（+180）
- [x] 前端 vitest 全绿 + vue-tsc 0 错 → **1103/1103**（+10）+ vue-tsc 0
- [x] 黄金集评估门禁不降（C1-C4 各 ≥5 例先建用例再实现）→ 合成黄金集建毕：多轮 maxRounds 1→2 Recall 0.750→1.000/MRR 0.750→1.000（四类语义等价划分）；**真实库全量跑 Phase4**（评测中心 V115 数据集在库）
- [ ] playwright：L1-L8 联动点逐条（[测试方案](../测试方案/知识库RAG检索能力增强测试方案.md) L1-L8+S1-S17，Phase 4）
- [x] 与规格 §3-§9 对齐复核，偏离记各子 plan 备注——六子 plan 实现注全（ISOLATED=QUARANTINED 前缀分流/历史轮次不做/SPI 全量枚举等）

## 术语表

| 术语 | 大白话 | 简单案例 |
|---|---|---|
| WP | 工作包——一批一起实现交付的步骤 | WP1=关联+附件 |
| step6.5 | 检索管道 rerank 之后、证据组装之前新插的关系处理步 | 命中法条→带出免责条款 |
| 语义等价边 | 方向不同但含义相同的两条关系 | MUST_CITE(B→A) 与 MUST_BE_CITED(A→B) |
| 有界循环 | 有硬上限的「查→看够不够→再查」 | 最多 2 轮 |
| 背压 | 下游处理不过来时上游自动减速 | 索引队列堆积→同步入队限速 |
| ISOLATED | 文档隔离态：保留但不召回 | 源端删除的同步文档 |
| 降级路径 | 主功能失败时自动换的备胎 | VLM 超时→只注入描述 |

## 备注

- **迁移版本号**：实施时先盘点 `db/migration` 全量最新序号（plan 日所见已到 V169+，资产库并行特性占用中），各 WP 迁移号在子 plan 用「V1xx」占位、开工时定。上轮 plan 踩过「误记序号」坑（见 [知识库模型选择与保密权限.plan.md](知识库模型选择与保密权限.plan.md) 备注）。
- AnswerCache 扩 hash 为本计划对规格的补充（坑点表第 1 行），实施后回写规格 §C1/C2 一行。
