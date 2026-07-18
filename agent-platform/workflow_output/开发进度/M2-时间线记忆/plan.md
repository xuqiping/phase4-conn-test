# M2 — 时间线记忆(原 10+16+22+24+29)

> 源:速查表09 待办 M2。schema 决策(2026-07-18,冲突3):**放弃 value json 化(#27 ❌)**,走标量字符串 + 分号分段 + 行首 ISO 日期前缀,对存量无损。时序判定(2026-07-18 用户裁决):**首次 pending 时 LLM 询问用户 → 打标持久化,该 key 后续复用,直到用户改**。

## 目标
1. **value schema**(原10机制):时序事实 value 存「日期+内容」段(`2026-06-25 住萧山;2027-01-01 住拱墅`),非时序(名字/偏好)维持单值。
2. **temporal 标机制**(用户决策):首次某 key 走到 pending 冲突 → 卡片附带 LLM 询问「这类按时间线记?(是/否)」→ 用户答 → 落 `memory_key_meta` 表;后续该 key 按 标走,panel 可改标。
3. **KEEP_BOTH 走时间线**(原10):merge 时 temporal=true → 各段带 updated_at 日期前缀按日期排序拼 `;`;temporal=false → 维持 `joinDistinct("，")`(中文逗号,**已核实非 ASCII**)。survivor 刷 updated_at(**现有 merge 不刷,M2 修**)。
4. **自定义合并**(原22):resolve 加「自定义」选项,input 默认填旧 value,手改落库(=KEEP_BOTH 手改版,刷 updated_at + 重 embed)。
5. **流水账/每日记账**(原16+24):daily_log 类 key 走时间线 value;**只记用户提交,不记 assistant 回答**(冲突1决策,答案半截砍)。
6. **日期进 entities**(原29):extract 抽日期/相对时间词(今天/昨天/本周/2026-07-18)进 entities 作检索锚点。分工:时序内容存 value 段,日期 token 进 entities,不双写。

## Chunk 拆分
- **chunk0 建表**:Flyway `V43__memory_key_meta.sql` 建 `memory_key_meta(user_id, memory_key, is_temporal, source, updated_at)` + entity/mapper。
- **chunk1 value schema util(后端)**:`MemoryValueTimeline` helper(parse 段 / 按日期排序 join `;` / 补段日期)+ 单测。存量兼容:无日期前缀段视作单值。
- **chunk2 temporal 标机制**:`memory_key_meta` 读/写端点(GET/PUT `/chat/memories/key-meta/{key}`)+ resolve 流程读标;首次无标 → pending 卡片标「待询问」。
- **chunk3 merge 改造**:`mergeValuesInto`/`mergePendingKeepBoth`/`mergeFlaggedKeepBoth` 按 temporal 标分支(时间线 vs 中文逗号 join),survivor `updated_at = now()` 刷 + 重 embed。单测覆盖两分支。
- **chunk4 extract 日期 entities**:extract prompt 加「抽日期/相对时间词进 entities」;`readEntities` 透传。daily_log key extract 仅 user message(不抽 assistant 回答)。
- **chunk5 自定义合并 + 流水账落库**:resolve DTO 加 `customValue` + decision `KEEP_CUSTOM`;后端走 merge 落库路径(刷 updated_at + 重 embed)。daily_log 识别 → 时间线段。
- **chunk6 前端**:utils 加 memory value timeline parse;panel value col 时间线多段渲染;key temporal 标编辑入口;resolve 卡片加「自定义」input + temporal 询问(首次)。
- **chunk7 双绿 + 冒烟**:mvn compile/test + vue-tsc + 99 套件 + playwright 冒烟 + commit。

## 功能联动点清单(v1.9 三处对齐)
| 触发动作 | 联动对象 | 预期 |
|---|---|---|
| 首次某 key pending 冲突 | resolve 卡片 | 显 temporal 询问「这类按时间线记?(是/否)」|
| 答「是」 | memory_key_meta | 落 is_temporal=true;merge 走时间线 |
| 答「否」 | memory_key_meta | 落 is_temporal=false;merge 走中文逗号 join |
| 该 key 再次冲突 | resolve 卡片 | 不再问,直接按标 merge |
| panel 改 key temporal 标 | memory_key_meta | 标翻转,后续 merge 按新标 |
| 时序 key 冲突点「合并保留」 | survivor value | 各段带日期按序拼 `;`,updated_at 刷 |
| 非时序 key 冲突点「合并保留」 | survivor value | 中文逗号 join(现状不变)|
| resolve 选「自定义」+ 改 value | survivor | 落用户改后 value,刷 updated_at + 重 embed |
| panel 看时序 key value | value col | 多段日期行渲染(非原样串)|

反向/半选:自定义 input 清空 → 阻止提交;temporal 标未答直接选合并 → 走默认(非时序中文逗号)不崩;老 value(无日期段)时间线 parse → 当单值渲染不崩。

## 安全检查清单
- [ ] 新端点 `GET/PUT /key-meta/{key}` 走 `@RequirePermission` + 用户隔离(user_id 从 JWT,非前端传)。
- [ ] customValue 入库前长度上限 + XSS(纯文本,不走 markdown)。
- [ ] temporal 询问/标改仅本人 key,admin 可查不可跨用户改。
- [ ] value 重 embed 走既有 embedding 端点,不引入新外部调用凭证。

## 运维考量清单
- [ ] 日志:merge 时间线分支 + temporal 标读写打 traceId 日志(记忆既有日志频道)。
- [ ] 监控:temporal 标命中率/询问次数(可选 metric,先日志)。
- [ ] 开关/降级:`rag.memory.timeline.enabled` KV 开关,关 → 退回中文逗号 join(现状),value 不带日期前缀。
- [ ] 健康:无新外部依赖(embed 复用既有)。
- [ ] 迁移:Flyway V43,**不可改已执行脚本**;memory_key_meta 初始空,按需填。
- [ ] 运维埋点随 chunk0/3 当下埋,不攒收尾。

## 测试方案
见 `workflow_output/docs/测试方案/M2-时间线记忆测试方案.md`(UI 联动:temporal 询问/自定义合并/时间线渲染 + playwright 冒烟)。后端单测覆盖 schema util + merge 两分支。

## 受众
B/C 类(用户操作 UI:temporal 询问/自定义合并/时间线展示/标编辑)→ 产 README + UserOps + FeatureMap + 测试方案。

## 风险/回归面
- merge 改造触及**所有 KEEP_BOTH 路径**(PENDING+FLAGGED),回归面大 → chunk3 单测 + IT 必须双分支覆盖。
- extract prompt 改影响**全量记忆抽取** → chunk4 回归既有 extract 套件(MemoryServiceTest 21/21)。
- temporal 标机制是新链路,首次询问 UX 须防「每次冲突都问」(标落库后该 key 不再问)。
