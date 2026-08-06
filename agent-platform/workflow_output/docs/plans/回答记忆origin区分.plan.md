---
description: "回答记忆 origin 区分 的实现计划"
created-date: 2026-07-22
---

# Implementation Plan for 回答记忆 origin 区分

> Phase 2 产出。Phase 3 逐步勾选执行。只含伪代码，不含真代码。
> 来源规格：本会话 brainstorming 决策（见 doc 09 演进与待办 + 下方「设计决策汇总」）。
> 文档规模：控制在 5000 tokens 内。

## 设计决策汇总（brainstorming 已定）

1. **用途**：AI 记自己说过的话（回答中的结论/推断/建议），后续对话可召回注入。
2. **数据模型**：`user_memories` 加列 `memory_origin VARCHAR(20) NOT NULL DEFAULT 'USER'`。
3. **唯一约束**：改 `(user_id, memory_origin, memory_key, COALESCE(home_project_id,-1)) WHERE conflict_id IS NULL`。同 origin 内 key 唯一，**跨 origin 同 key 共存**（用户事实与 AI 结论同 key 各自独立行）。
4. **冲突**：只在同 origin 内判（现状路径不动语义，只多一层 origin 过滤）。
5. **抽取**：一次 extract，prompt 同时喂 user 输入 + AI 回答，LLM 每条 fact 标 origin。不加 LLM 调用次数。
6. **召回**：默认只拉 USER；answer 开关开才注入；注入时 answer 行加 `[AI]` 前缀。
7. **开关**：全局设置页默认 + 底栏每会话覆盖（null=继承全局，同现有记忆读范围三态模式）。
8. **面板**：加「来源」列 + 按来源筛选。

## ⚠️ 对原有功能影响分析（必读）

> 用户要求：改动不对原有功能产生影响。本特性有 **1 处故意的行为变更 + 若干须保证零影响的点**，逐条列出。

| 点 | 影响原功能? | 说明 / 规避 |
|---|---|---|
| **M2 写侧 gate（回答不入记忆）** | **是（故意）** | 现状 [MemoryConflictJudge.java:215](agent-platform/backend/src/main/java/com/superprogrammer/chat/service/internal/MemoryConflictJudge.java#L215) 喂空串给 assistant。本特性放开 = **回答开始进记忆**。这是特性的核心目的，属预期变更。doc 09 的 M2「写侧 gate」决策标注「被回答记忆特性取代」。 |
| 存量记忆行 | 否 | migration 回填 `memory_origin='USER'`，所有老行归 USER，行为不变。 |
| 召回结果 | 否（开关默认关） | `includeAnswer` 默认 false → 召回 SQL 退化为 `WHERE memory_origin='USER'`，与今天 100% 一致。老用户不打开开关感知不到。 |
| 唯一索引 drop/重建 | 低风险 | 迁移在事务内 drop 旧建新。老行已回填 USER，唯一性维度多加 origin 不引入新冲突。迁移失败的回滚脚本同脚本内提供。 |
| 抽取 LLM 返回漏标 origin | 否 | 降级默认 USER（安全侧，不污染 user 唯一槽）。 |
| 冲突/分流路径 | 否 | processFacts 候选查询加 origin 过滤，同 origin 同 key 走原逻辑。跨 origin 不进冲突判定 = 新增独立行，不动旧 user-user 路径。 |
| 底栏读范围 payload | 否 | 加可选 `memIncludeAnswer`，null=继承全局。老前端不发此字段 → 继承全局 → 默认关 → 行为不变。 |

**结论**：除「回答开始进记忆」这一预期变更外，存量数据、召回、冲突、面板对老用户零行为变化。

## 技术实现坑点预判与规避措施

| 技术点/功能块 | 可能的坑 | 规避措施 | 验证方式 |
|---|---|---|---|
| 唯一索引重建 | drop+create 非原子，中途失败留脏 | 迁移脚本内 drop 后立即 create，同事务；附回滚脚本 | 迁移后 `\d user_memories` 查索引；插重复行验证拒绝 |
| origin 过滤加进 12 处召回 SQL | 漏改某条 SQL → answer 记忆泄漏进默认召回 | 集中改 mapper，单测覆盖每条 SQL 的 USER-only 路径 | 关开关时插 ANSWER 行验证不被召回 |
| 跨 origin 同 key | 老逻辑 `findCleanByHomeKey` 返回多行被当 refinement 覆盖 | 该查询加 origin 过滤，只返同 origin | 单测：user/answer 同 key 不互相覆盖 |
| extract prompt 双输入 | LLM 把 user 内容误标成 ANSWER | 降级策略 + prompt 明确「含糊按 USER」；judge 单测断言 origin 字段 | mock LLM 返不同 origin，断分流正确 |
| 缓存键 | MemoryQueryCache 按 scope 签名，未含 origin → 开关切换不刷新 | scope 签名并入 `includeAnswer` 标志 | 切换开关后验证召回结果变化（缓存失效） |
| 全局设置变更不立即生效 | KV 改了但前端/会话缓存旧值 | 读侧每次读 SystemSettingService（现状），不加会话级缓存 | 改设置后下一轮验证生效 |

## 安全检查清单

- [x] **鉴权/授权**：复用现有 MemoryController ownership 校验（`ensureOwned`）；设置页 admin only。无新端点暴露。
- [x] **输入校验**：origin 值后端强校验 ∈ {USER, ANSWER}，DDL CHECK 约束兜底。
- [x] **数据加密**：无新增敏感字段。
- [x] **审计日志**：抽取/入库走现有 `log.debug/info`；开关变更无强审计需求。
- [x] **错误处理**：LLM 漏标降级不抛；迁移失败事务回滚。
- [x] **CORS/CSRF**：无新增 web 面。
- [x] **依赖安全**：无新依赖。

## 性能考虑与验证计划

- [x] **查询效率**：12 处召回 SQL 加 origin 条件，索引含 origin 前缀（user_id, memory_origin, ...）→ 走索引，无 N+1。
- [x] **缓存策略**：scope 签名并入 includeAnswer，切开关自动失效。
- [x] **并发处理**：同 origin 唯一约束并发写靠 DB 唯一索引兜底（现状 applyClean 撞唯一兜底 UPDATE 不变）。
- [x] **资源使用**：一次 extract 不增 LLM 调用；answer 文本可能长 → prompt token 上限沿用现有截断。
- [x] **性能验证**：Phase 4 测「开关开 vs 关」召回延迟差（应 < 5%）。

## 功能联动点清单

> 本功能联动少（数据写入/读取偏静），逐条列。

- [ ] **底栏「含 AI 回答记忆」switch ↔ 全局设置**
  - 触发：底栏 switch 切换；联动：本会话召回 origin 过滤。
  - 边界：null（未操作）= 继承全局默认；true=强开；false=强关。三态。
  - 反向：改全局默认后，已显式设过的会话不受影响（会话值优先）。
- [ ] **抽取标 origin ↔ panel 来源列**
  - 触发：每轮 extract 返 origin；联动：panel 来源列渲染 tag。
  - 边界：漏标降级 USER → 列显「用户」（与默认一致，不暴露降级）。
- [ ] **跨 origin 同 key 共存 ↔ panel 去重展示**
  - 触发：同 key 两条（user + answer）；联动：panel 各自一行，来源列区分。
  - 边界：不合并展示（语义不同：用户事实 vs AI 结论）。

## 运维考量清单

| 项 | 做/不做/后续 | 说明 |
|---|---|---|
| 可观测性 | 做 | 抽取日志含 origin 分布；召回日志加 `includeAnswer` 字段。 |
| 配置开关 | 做 | 全局 `rag.memory.recall-answer` 默认 false；出问题关掉即回退。 |
| 可回滚 | 做 | Flyway V47 加列/改索引附回滚脚本；列有默认值，回滚 drop 列安全。 |
| 限流/熔断/降级 | 不做 | 复用现有 LLM 超时/降级（LLM 漏标→USER 兜底）。 |
| 运维入口 | 后续 | answer 记忆手动清理/批量改 origin 复用现有面板编辑，暂不加专用端点。 |
| 告警阈值 | 不做 | 无新关键指标。 |
| 容量/性能预案 | 后续 | origin 加索引，无分区需求。 |

## 实现步骤

### Chunk A：后端数据模型（独立可测，零依赖）

- [ ] **Step 1：Flyway migration 加列 + 改唯一索引**
  - **目标**：加 `memory_origin` 列，改唯一约束含 origin。
  - **动作**（伪代码）：
    - `ALTER TABLE user_memories ADD COLUMN memory_origin VARCHAR(20) NOT NULL DEFAULT 'USER';`
    - `UPDATE user_memories SET memory_origin='USER' WHERE memory_origin IS NULL;`（显式回填，保险）
    - `ALTER TABLE user_memories ADD CONSTRAINT ck_memory_origin CHECK (memory_origin IN ('USER','ANSWER'));`
    - drop 旧唯一 `uk_user_memories_user_key_home`
    - 建新 `uk_user_memories_user_home_origin_key UNIQUE (user_id, memory_origin, memory_key, COALESCE(home_project_id,-1)) WHERE conflict_id IS NULL`
  - **文件**（1）：
    - `backend/src/main/resources/db/migration/V47__user_memories_origin.sql`
  - **依赖**：无。
  - **验证**：迁移成功；`\d user_memories` 查列+索引+CHECK；插同 origin 同 key 重复行验证拒绝；插跨 origin 同 key 验证共存。

- [ ] **Step 2：UserMemory 实体 + origin 常量**
  - **目标**：实体映射新列。
  - **动作**：
    - `UserMemory` 加 `private String memoryOrigin;`
    - 加常量/枚举 `ORIGIN_USER="USER"`、`ORIGIN_ANSWER="ANSWER"`
    - `newMemory(...)` 默认设 USER（若 fact.origin 空）
  - **文件**（2）：
    - `backend/.../chat/entity/UserMemory.java`
    - `backend/.../chat/service/MemoryService.java`（newMemory 方法）
  - **依赖**：Step 1。
  - **验证**：mvn compile。

### Chunk B：后端抽取（一次 extract 双标 origin）

- [ ] **Step 3：ExtractedFact + prompt 改造**
  - **目标**：extract 同时抽 user + answer，LLM 标 origin。
  - **动作**：
    - `ExtractedFact` record 加 `String origin`
    - `MemoryConflictJudge.extract` 行 215 喂空改喂真 `assistantResponse`
    - `EXTRACT_PROMPT` 重写：收 user 输入 + AI 回答两段；返回 JSON 每条加 `origin` 字段；加说明「USER=用户原话所述事实，ANSWER=AI 回答中给出的结论/推断；含糊按 USER」
    - 解析时 origin 漏标 → 默认 USER
  - **文件**（2）：
    - `backend/.../chat/service/internal/ExtractedFact.java`
    - `backend/.../chat/service/internal/MemoryConflictJudge.java`
  - **依赖**：Step 2。
  - **安全**：输入校验 origin ∈ {USER,ANSWER}。
  - **验证**：`MemoryConflictJudgeTest` 加 case（mock LLM 返混合 origin，断言分流）；单测 extract 返 origin。

### Chunk C：后端冲突/入库（同 origin 内判）

- [ ] **Step 4：processFacts 分流加 origin 过滤**
  - **目标**：候选查询限同 origin，跨 origin 不冲突。
  - **动作**：
    - `UserMemoryMapper.findCleanByHomeKey`/`findCleanByBlock`/`findAllClean`/`findDistinctKeys` 加 origin 参数（或布尔 includeAnswer，写侧固定按 origin 单查）
    - `classifyFact`、processFacts ② 候选查询传 fact.origin
    - `ExtractedFactSnapshot` 加 origin
    - `MemoryConflictService.buildFromSnap`/`createFlagged`/`insertSnapScoped`/`createPending` 物化新行带 origin（同 V38 修法，scope 继承不变）
    - `applyClean` 新行 `m.setMemoryOrigin(f.origin())`
  - **文件**（4）：
    - `backend/.../chat/mapper/UserMemoryMapper.java`
    - `backend/.../chat/service/MemoryService.java`
    - `backend/.../chat/service/MemoryConflictService.java`
    - `backend/.../chat/mapper/UserMemoryMapper.xml`（若 SQL 在 xml）
  - **依赖**：Step 3。
  - **验证**：`MemoryServiceTest` 加 case：user/answer 同 key 不互相覆盖、不冲突；跨块同 key 同 origin 仍判冲突（防回归）。

### Chunk D：后端召回（默认 USER，开关开含 ANSWER）

- [ ] **Step 5：MemoryScope + 召回 SQL origin 过滤**
  - **目标**：默认只召回 USER，开关开含 ANSWER。
  - **动作**：
    - `MemoryScope` 加 `boolean includeAnswer`（默认 false）
    - `MemoryQueryCache.scopeSig` 并入 includeAnswer（缓存键区分）
    - 12 处召回方法加 origin 过滤：`findFullContext`/`findTopKByVector`/`findByKeyword`/`findTopKByAnchor`/`findAnchorBm25`/`findAllClean`/`findDistinctKeys`/`findCleanByBlock`/`findCleanByHomeKey`/`countByScope` → includeAnswer=false 时 `memory_origin='USER'`，true 时不限
    - `formatLine` answer 行加 `[AI]` 前缀
  - **文件**（3）：
    - `backend/.../chat/service/internal/MemoryScope.java`
    - `backend/.../chat/mapper/UserMemoryMapper.java` + xml
    - `backend/.../chat/service/MemoryService.java`（formatLine + 调用处透传）
  - **依赖**：Step 2。
  - **验证**：单测关开关时插 ANSWER 行不被召回；开开关召回且带 `[AI]` 前缀；缓存切开关失效。

- [ ] **Step 6：全局设置 + 装配到会话读 scope**
  - **目标**：全局开关 + 底栏覆盖落到读 scope。
  - **动作**：
    - `SystemSettingService` 加 `getMemoryRecallAnswer()`（默认 false）+ setter；并入 `RagMemorySettings` VO/Update/Controller（非新端点，同 M3 模式）
    - `ChatSessionService` 装配读 scope 时：includeAnswer = 会话 payload `memIncludeAnswer`（null→全局默认）
  - **文件**（3）：
    - `backend/.../system/service/SystemSettingService.java`
    - `backend/.../system/controller`（RagMemorySettings 相关）
    - `backend/.../chat/service/ChatSessionService.java`
  - **依赖**：Step 5。
  - **验证**：单测三态（null 继承 / true / false）；改全局后下一轮生效。

### Chunk E：前端

- [ ] **Step 7：VO + panel 来源列/筛选**
  - **目标**：面板区分 user/answer 记忆。
  - **动作**：
    - `UserMemoryVO` 加 `memoryOrigin`
    - `MemoryManagerPanel.vue` 加「来源」列 tag（用户/回答）+ 筛选下拉
  - **文件**（3）：
    - `backend/.../chat/dto/UserMemoryVO.java`
    - `frontend/src/components/chat/MemoryManagerPanel.vue`
    - `frontend/src/api/chat.ts`（类型，若有）
  - **依赖**：Step 2。
  - **验证**：vue-tsc；面板显示来源列；筛选取值正确。

- [ ] **Step 8：设置页开关 + 底栏 switch**
  - **目标**：全局默认 + 底栏覆盖。
  - **动作**：
    - `RagMemorySettingsTab.vue` 加 switch「召回时包含 AI 回答记忆」
    - `ChatView.vue`「读取记忆范围」区加「含 AI 回答记忆」switch，payload 透 `memIncludeAnswer`
  - **文件**（2）：
    - `frontend/src/components/settings/RagMemorySettingsTab.vue`
    - `frontend/src/views/ChatView.vue`
  - **依赖**：Step 6。
  - **验证**：vue-tsc；playwright 冒烟（设全局、底栏覆盖、null 继承三态）。

## 整体验证（功能级）

- [ ] mvn compile + 全部既有单测零回归（MemoryServiceTest 21/21、ChatSessionServiceTest 14/14、MemoryConflictJudgeTest）
- [ ] 新增单测：origin 分流、跨 origin 同 key 共存、召回 origin 过滤、开关三态
- [ ] vue-tsc 净 + 99 既有前端测试绿
- [ ] playwright 冒烟：回答进记忆（origin=ANSWER）→ 关开关不召回 → 开开关召回带 [AI] → panel 来源列/筛选
- [ ] 与设计决策 8 条对齐复核
- [ ] doc 09 待办更新：M2 写侧 gate 标注被取代 + 新增「回答记忆 origin」条目

## 术语表

| 术语 | 大白话 | 简单案例 |
|---|---|---|
| origin（记忆来源） | 这条记忆是从「用户说的话」还是「AI 回答的话」里提炼的 | 用户说「女儿叫小红」→ USER；AI 说「建议用方案B」→ ANSWER |
| 唯一约束（unique index） | 数据库不允许同一组字段重复的规则 | 同一用户、同一来源、同一 key 只能存一条 |
| 召回（recall） | 对话时从记忆库里把相关记忆挑出来喂给 AI 的过程 | 用户问「我女儿叫啥」→ 召回「女儿=小红」 |
| 写侧 gate | 决定「什么内容值得抽成记忆」的开关 | M2 的 gate=回答不入记忆；本特性放开它 |
| 三态（tri-state） | 开关有三种值：开/关/未设（继承默认） | 底栏 memIncludeAnswer：null=跟全局、true=强开、false=强关 |
| origin 过滤 | 查记忆时按来源筛 | 默认只查 USER 来源的记忆 |

## 备注

- 本特性**故意变更** M2 写侧 gate（回答不入记忆 → 入记忆）。除此之外对存量功能零影响。
- M5 读侧 gate（agent/工作流不注入记忆）仍独立待办，本特性不动。
- 若 Step 4 发现 `findCleanByHomeKey` 等改签名牵连过广，可改为 mapper 内重载方法（旧签名保留按 USER 查，新签名带 origin），把改动面压到最小——Phase 3 决策，回来更新本备注。
