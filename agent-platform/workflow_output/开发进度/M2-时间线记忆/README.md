# M2 时间线记忆 · README

> 受众 B/C(用户直接操作 UI)。源:速查表09 待办 M2(原 10+16+22+24+29)。
> 设计源:[速查表09-演进与待办](../../../项目工程文档/项目功能介绍/速查表/09-个人记忆-演进与待办.md) M2 + 冲突决策 1/3/5。

## 一句话
冲突合并不再只丢保旧/新——**时序事实(住址/工作/日记)按时间线保留各日期段**,**非时序(名字/数量)维持去重合并**;用户可**自定义手改**合并值;首次冲突可设定该类记忆是否走时间线,后续复用。

## 用户地图(怎么用)
1. **冲突出现**:发消息产生同 key 冲突 → 记忆面板「待解决的记忆冲突」卡。
2. **设时间线标**(首次可选):卡上「时间线:是/否」。住址/工作/日记选「是」,名字/数量选「否」。设过该 key 复用,不再问。
3. **选解决方式**:
   - 保留新 / 保留旧 / 全删 —— 同前。
   - **合并保留** —— 时序 key:各段带日期拼成时间线(2026-06-25 住萧山;2027-01-01 住拱墅);非时序:中文逗号去重。
   - **自定义**(新) —— input 默认填旧值,手改后落库。
4. **看时间线**:我的记忆表「值」列,时序 value 展开成多段日期行。
5. **改标**:冲突卡「时间线」按钮重设(=panel 手改,优先级最高,直到再改)。

## 技术说明
### value schema(标量,对存量无损)
- 格式:`{YYYY-MM-DD} {内容};{YYYY-MM-DD} {内容}`(分号分段,行首 ISO 日期前缀)。
- 非时序/老数据:无日期前缀的单值,不破坏。
- 解析:[MemoryValueTimeline](backend/src/main/java/com/superprogrammer/chat/service/internal/MemoryValueTimeline.java)(后端)+ [memoryTimeline.ts](frontend/src/utils/memoryTimeline.ts)(前端镜像)。

### 时序判定机制(用户裁决 2026-07-18)
首次某 key 走到 pending 冲突 → 卡片让用户答「这类按时间线记?」→ 落 `memory_key_meta`(per-user per-key,source=LLM_ASK)→ 后续该 key merge 按标走。panel 可改(source=USER_OVERRIDE)。daily_log/diary/journal/_log key 天然时序,无标也走时间线。

### KEEP_BOTH merge 两分支
[MemoryConflictService.mergeValuesInto](backend/src/main/java/com/superprogrammer/chat/service/MemoryConflictService.java):
- temporal=true → `MemoryValueTimeline.mergeTemporal`(末项 new 带 resolve 日期前缀,old 按序拼 `;`,同 date+content 去重)。
- temporal=false → `joinDistinct` 中文逗号 `，`(现状不动)。
- mergeIntoRow 已含 `updated_at=now()` + 重 embed(失败 COALESCE 保旧向量)。

### KEEP_CUSTOM 自定义合并
resolve 加 decision `KEEP_CUSTOM` + customValue:survivor=old 首行,value 改 customValue + 重 embed,删多余/丢 new(PENDING/FLAGGED 双路径)。批量 resolveAll 不含(需逐条 customValue)。

### 写侧 gate(冲突决策 1)
extract 只抽用户消息,assistantResponse 入 prompt 喂空——AI 回答不入记忆。用户要记答案走 M5「记一下」主动通道(M5 待办)。

### 日期进 entities(冲突决策 5)
extract prompt 加日期/相对时间词(今天/昨天/本周/2026-07-18)进 entities 作检索锚点。分工:时序内容存 value 段,日期 token 存 entities,不双写。

## 建表(Flyway V43)
`memory_key_meta(id, user_id, memory_key, is_temporal, source, created_at, updated_at)`,uk(user_id, memory_key)。不走 BaseEntity 软删(同 user_memories 域)。详见 Feature Map。

## 测试
- 后端单测:MemoryValueTimelineTest 14/14 + MemoryKeyMetaServiceTest 6/6。
- 全量 mvn test:396 测,3 预存基线失败(MemoryConflictJudge entities-cap / RagRetrieval grayZone / RuntimeCallback 401,WIP 漂移非本功能)。
- 前端 vue-tsc 净 + vitest 99/99。
- 冲突 resolve KEEP_CUSTOM/temporal 分支:走 playwright E2E(测试方案实测列,待全栈起填)。

## commit 链
chunk0 e3257c42(表+entity/mapper)→ chunk1 ca24150a(value util+14测)→ chunk2 309e25c9(key-meta 端点+service)→ chunk3 91880a63(merge 两分支)→ chunk4 517ea0b0(extract 日期entities+写侧gate)→ chunk5 336adea0(KEEP_CUSTOM+daily_log)→ chunk6 6bf1e6db(前端渲染+自定义+temporal UI)。

## 待办
- [ ] playwright 冒烟(全栈起):KEEP_CUSTOM 落库 / 时序 merge 多段 / temporal 标持久化。
- [ ] M5 写侧 gate「主动要求记答案」通道(本功能已挡 AI 回答,M5 补用户主动通道)。
- [ ] timeline KV 开关(`rag.memory.timeline.enabled`):per-key default-false 已是真门(无标=现状),KV 开关作冗余总闸延后。
