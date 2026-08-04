# 计划12 · 个人记忆重设计 · README

> 功能完成 README。**C 类**(用户地图 + 技术说明)。真相源 [总体设计](../../../项目工程文档/设计/个人记忆重设计-总体设计.md) + [主索引](../../../项目工程文档/计划/计划12-个人记忆重设计-实现开发计划.md) + [速查表 09 三件套](../../../项目工程文档/项目功能介绍/速查表/09-个人记忆与冲突解决.md)。
> 完成状态:代码侧 A-H 全绿(H'-1~H'-4 切流+删 legacy+V53 DROP+前端双栈期结束),Phase4 E2E 留项见 [测试方案](../../docs/测试方案/个人记忆测试方案.md)。

---

## 一、用户地图(这条功能给用户带来什么)

### 一句话
平台记住你每轮对话的要点(住址/偏好/项目背景…),下次聊天自动把相关记忆塞进 AI 提示词;你能在面板看/删/总结/裁决冲突,还能按项目把记忆只读共享给队友。

### 核心心智(三层)
1. **流水账(turn)**:每轮对话自动留一行(你说的一句 + AI 回的一段),像账本。不挂项目 = 个人私有;挂项目 = 项目成员可读。
2. **总结(summary)**:后台把一堆流水账压成精华条(800 条爱好对话 → 1 条爱好总结),省 token、AI 召回更快。
3. **标签(tag)**:每条记忆贴 `主体:主题` 标签(如 `我:居住`、`表哥:爱好`),系统自动归一同义标签,你只管改名/补别名,不用手动合并。

### 用户能做什么(9 能力 → 入口)
| 能力 | 入口 | 预期 |
|---|---|---|
| 对话自动留痕 | 正常聊天(无需操作) | 流式回复后异步落流水账(开生成则出 L0/L1/L2;关则留 raw 90 天) |
| 召回注入 | 聊天底栏「召回范围」气泡 | 勾个人/项目,新会话沿用上次;AI 回答带相关记忆 |
| 手动总结 | 面板「总结」页签 → 开始总结 | 弹框选 scope(个人/项目),跑完出精华条带溯源 |
| 冲突裁决 | 面板「冲突裁决」页签 | 时序矛盾(住萧山 vs 住拱墅)挂 pending,四选项(都留/新的/旧的/丢弃) |
| 标签库 | 面板「标签库」页签 | 改标签名/补别名(tag_id 不变);禁合并/拆分(误并不可逆) |
| 生成开关 | 面板「生成矩阵」页签 | owner 关项目生成 / 会员个人覆写;关后新对话不调生成 LLM(仍留 raw) |
| 项目 ACL | 面板「项目 ACL」页签 | owner 配「谁能读谁的流水账」;owner 默认全读 |
| 生命周期 | 面板「生命周期」页签 | 离职/被删项目后拉取自己流水账到新项目(copy 非 move) |
| 波及通知 | 聊天页右上角 badge | 别人撤回记忆波及你的总结 / 项目被删,3s 轮询提醒 |

> 细到「功能→步骤→界面变化→预期」的操作手册见 [User-Ops](../../docs/user-ops/个人记忆用户操作手册.md)。

### 隐私与遗忘
- 未挂项目的记忆严格私有,别人命中不了(无需额外设置)。
- 挂项目的记忆 = 项目成员只读(不能改你的,只能基于你的产自己一条)。
- raw(未生成的对话原文)默认 90 天后自动清;已生成总结的原文随总结保留。
- 流水账可随时查看/批量删除(仅本人,无导出)。

---

## 二、技术说明(给开发)

### 架构(独立新栈,命名空间 `/api/chat/memory/*`)
活聊天 [ChatSessionService](../../../backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java) 切流(H'-1):召回 → [MemoryRecallPipeline](../../../backend/src/main/java/com/superprogrammer/chat/service/internal/MemoryRecallPipeline.java);写入 → [MemoryGenerationService](../../../backend/src/main/java/com/superprogrammer/chat/service/internal/MemoryGenerationService.java)(fire-and-forget)。9 控制器 / 30+ service / 13 前端组件,全清单见 [速查表 09 主表](../../../项目工程文档/项目功能介绍/速查表/09-个人记忆与冲突解决.md)。

### 数据层(9 新表 + ACL + 扩列,建表用 Flyway V47-V53)
流水账为主体(`memory_turns`),标签独立表(`memory_tags`),总结提炼层(`memory_summaries`+`memory_summary_coverage`),成员/设置/总结勾选/通知 5 表,`memory_recall_acl` 授权,`memory_conflicts` 扩列。旧 `user_memories`/`user_memory_projects` V53 DROP。表用途/字段/关联注解见 [Feature Map](../../docs/feature-map/个人记忆.feature-map.md)。

### 九迭代实现(A→H)
A 数据层 → B 标签归一 ‖ I1 ACL 前置 → C 生成写入 → D 召回 → E 总结冲突 → I2/I3 ACL 接入 → F 前端 ‖ I4 前端 ACL → 生命周期 hook → H 收尾。逐迭代偏离裁决/坑见 [演进与待办](../../../项目工程文档/项目功能介绍/速查表/09-个人记忆-演进与待办.md),逐轮进度 [开发进度1~16](./)。

### 关键设计决策(why)
- **主体是流水账非事实**:事实抽取层挂流水账,总结是压缩层 → 治旧模型 token 爆炸 + 无提炼层。
- **标签独立表 + 写时归一 + 禁手动并拆**:治旧模型 key 不稳定;误并不可逆。
- **召回恒只读自己总结**:他人总结是他人视角提炼,注入污染上下文。
- **召回 scope 与写入目标解耦**:默认勾{个人}不跟随召回,防读项目记忆时新隐私被共享。
- **总结不可分享**:总结 project_id 单值,非可共享原始料。
- **DISCARD 连带软删 source turns**:防 worker 再生成同冲突死循环。
- 算法 why 详见 [设计与原理](../../../项目工程文档/项目功能介绍/速查表/09-个人记忆-设计与原理.md)。

### 运维埋点(开发期已埋)
- **可观测**:召回 pipeline 7 步每步打点(含 traceId)+降级 notes;总结 worker 任务状态/耗时/失败率;LLM 调用计量(生成/总结/reflect 分开)。
- **配置开关**:gen 开关/离职开关/总结周期/raw TTL 90 天/防膨胀阈值/anchor 阈值 全走 `system_settings` KV + 独立设置表,免发版可改。
- **运维入口**:总结 worker `POST /consolidation/trigger` 手动重跑;raw 查看/批量删;波及通知 ACK。
- **限流/降级**:LLM 超时+重试+applyClean 兜底;召回降级(reflect 失败→只读 L1,选标签失败→null,selector 启发式);worker 异常不阻塞对话。
- **告警**:未总结流水账 >100 → 面板告警按钮。

### 安全(对照总体设计 §6 十五向量)
scope 过滤强制 / 项目成员交集 / 标签聚合 scope / 标签对外只露 label+subject+topic / provenance scope / 冲突 scope / IDOR / 召回侧信道二次校验 / 缓存 evict / 旧端点 404 / admin 边界(不可读用户内容) / prompt 注入 `<memory_data>` / 批量 ownership / 项目读取 ACL(readableAuthors) / 输入校验+审计。15 向量越权 IT 留 Phase4 E2E。

### 旧栈清理(H 收尾)
旧 `MemoryService`(1278 行)/`MemoryController` 全族/`UserMemory`/`MemoryBlockClassifier` 等 25 main+7 test 整删;`MemoryConflictJudge`/`MemoryQueryCache` 瘦身保新栈依赖;前端 `chat.ts` 16 legacy 方法删 / `MemoryManagerPanel` 瘦身 / `RagMemorySettingsTab` 删;V53 DROP 旧表。旧设计备份分支 `agent-platform-old_jiyi`(a1163c53)。
