# M3 — RAG 记忆设置动态化 + entities 计数可配 · plan

> 合并原待办 #14(按检索模式动态显隐)+ #19(entities 计数用户可配)。中优先,纯设置层(前端 tab + 后端 KV + Judge prompt),不动对话主链路。
> 受众:B(运维/管理员用),收尾产 README + Feature Map + User-Ops + 测试方案(UI 主观显隐)。
> 启动前已读 [速查表 09-个人记忆-演进与待办.md](../../../项目工程文档/项目功能介绍/速查表/09-个人记忆-演进与待办.md) M3 节。

## 一、目标(大白话)

1. **动态显隐**:后台「记忆设置」tab 顶部选检索模式后,下方只露该模式相关旋钮,无关的全隐藏。避免用户被无关选项干扰。
2. **entities 计数可配**:记忆入库时 LLM 抽「召回关键词」的数量上限(总数/变体/专名/上位词)从**代码硬编码**搬进**设置页**,管理员可调。改完老数据靠既有「重抽关键词」端点吃新配。

## 二、现状(调查结论)

- 前端 [RagMemorySettingsTab.vue](../../../frontend/src/components/settings/RagMemorySettingsTab.vue) 已有全部 4 模式控件,但用 `:disabled` 锁无关控件(:55/69/83/95),**没真正隐藏** → 视觉仍堆满。M3 改 `v-if` 按模式分组显隐。
- 后端 [SystemSettingService.java](../../../backend/src/main/java/com/superprogrammer/system/service/SystemSettingService.java) KV 路径成熟(`getValue`/`upsert` + `getBoolean`/`getInt` 系列),9 个 `rag.memory.*` key 已存在。M3 加 1 个 JSON 复合 key `rag.memory.entities-config`。
- [MemoryConflictJudge.java](../../../backend/src/main/java/com/superprogrammer/chat/service/internal/MemoryConflictJudge.java) 硬编码:
  - `readEntities` :386 `out.size() >= 20`(totalMax=20)、:384 `s.length() > 8` 跳过。
  - EXTRACT_PROMPT(:42-101)文本写死「≤20 个」「上位词上限 10」「至少 5 个」「变体 1-3 个」。
  - `batchExtractEntities`(:269-294)同调 readEntities。
- 重抽端点已存在:`POST /api/system/settings/rag-memory/reextract-entities`([SystemSettingController:136](../../../backend/src/main/java/com/superprogrammer/system/controller/SystemSettingController.java)) → [MemoryService.reextractEntitiesAsync:1220](../../../backend/src/main/java/com/superprogrammer/chat/service/MemoryService.java)。老数据吃新配靠它,零新增。
- 检索模式 4 值字面量:LLM_FULL_CONTEXT / EMBEDDING_VECTOR / VECTOR_KEYWORD / LLM_KEY(无 enum 类,白名单在 SystemSettingService:142)。

## 三、设计决策

### entities-config 存储形态(冲突 1)
- **单 JSON key** `rag.memory.entities-config`,value = `{"totalMax":20,"variantMin":1,"variantMax":3,"properNounMin":1,"properNounMax":5,"hypernymMin":5,"hypernymMax":10}`。
- 理由:7 子项原子读写,前端一次表单提交,后端一次反序列化;比 7 条独立 KV 少 6 次 upsert + 6 次迁移 seed。
- 默认值 = 现 V38 硬上限(零行为变更,存量无感)。

### 模式→显隐映射(冲突 2)
| 模式 | 显隐的旋钮组 |
|---|---|
| LLM_FULL_CONTEXT(默认) | 全量阈值 + 标签语言 |
| EMBEDDING_VECTOR | (无额外) |
| VECTOR_KEYWORD | 关键词召回块阈值 + 关键词通道上限 + **entities-config** |
| LLM_KEY | LLM_KEY 粗筛候选数 + LLM_KEY 精排开关 + **entities-config** |
| 所有模式常驻 | 记忆总开关 + 处理模式 + 检索模式 + 3 个运维按钮(回填/重抽/清理) |

- entities-config 只在用到「召回锚点」的模式(LLM_KEY / VECTOR_KEYWORD)露;LLM_FULL_CONTEXT 全量喂不靠 entities,EMBEDDING_VECTOR 纯向量也不靠。

### Prompt 动态注入(冲突 3)
- EXTRACT_PROMPT 文本里「≤20 个」「至少 5 个」「变体 1-3 个」「上限 10」「专名」**改为 `%d` 占位符**,extract 方法 `String.format` 注入配置值。
- BATCH_ENTITIES_PROMPT(:184)同改。
- prompt 模板仍为常量主体,占位符注入数值——不整体重写模板,降回归面。

### Java 硬截断
- `readEntities` :386 `20` → 读 `config.totalMax`;:384 `> 8` 字符上限**保留**(分词限制,与计数无关)。
- 新增按类截断?**不加**——LLM 已按 prompt 数量自律输出,Java 层只兜底 totalMax 上限即可,避免过度工程化破坏现有 readEntities 单遍逻辑。

## 四、步骤拆分(chunk 级)

### Step1 — 后端:entities-config KV + 读取 + seed ✅
- SystemSettingService 加常量 `RAG_MEMORY_ENTITIES_CONFIG = "rag.memory.entities-config"`。
- 新 record `MemoryEntitiesConfig`(7 字段 + defaults() + normalized() 兜底归一)。
- 新方法 `getMemoryEntitiesConfig()`/`updateMemoryEntitiesConfig()`(Jackson 反序列化,损坏回退默认 + 告警)。
- Flyway **V45__seed_memory_entities_config.sql** seed 默认 JSON。
- (单测留 Step5/收尾,先 compile 验证)

### Step2 — 后端:Judge 读配置 + prompt 动态注入 ✅
- MemoryConflictJudge 注入 SystemSettingService。
- EXTRACT_PROMPT + BATCH_ENTITIES_PROMPT 硬编码数字改**命名 token**(`{TOTAL_MAX}`/`{VARIANT_MIN-MAX}`/`{HYPERNYM_MIN-MAX}`),`applyEntitiesConfig` helper 替换(避免与既有 %s 的 String.format 顺序耦合)。
- `readEntities(JsonNode, int totalMax)` 截断读 config.totalMax;readFact 透传 cfg。

### Step3 — 后端:并入 RagMemorySettings(决策修订,非新端点)✅
- **修订**:不新建 `PUT /entities-config` 端点,直接并入既有 `RagMemorySettings` VO/Update/Controller(与其他 8 旋钮一致,少一个端点)。
- DTO 校验靠 `MemoryEntitiesConfig.normalized()`(clamp/min>max 互换/totalMax 上调到合计),Controller update 时 null 跳过。

### Step4 — 前端:API + tab 动态显隐 + entities 旋钮组 ✅
- [api/system.ts](../../../frontend/src/api/system.ts) 加 `MemoryEntitiesConfig` 接口 + `RagMemorySettings.entitiesConfig`。
- RagMemorySettingsTab.vue:4 模式旋钮 `:disabled` → `v-if`;entities 卡片仅 LLM_KEY/VECTOR_KEYWORD 显(7 NInputNumber,min/max 联动约束);切回非召回模式不提交 entitiesConfig(本地 ref 保留)。
- vue-tsc 绿。

### Step5 — 收尾产出 + 进度记录 + 总览更新 ✅
- README / Feature Map / User-Ops / 测试方案 全产。
- 开发进度.md + 总览表加 M3 行 + 沉淀规范。

## 五、安全检查清单

- [ ] entities-config 写回端点 `@RequirePermission("system:config:update")` 或既有 admin 门禁(对齐其他 rag-memory 写回端点)。
- [ ] DTO 校验:totalMax ∈ [1,50],各 min≤max 且 ≥1,防 LLM 被 0/负数/超大值搞崩(prompt 爆 + 截断异常)。
- [ ] JSON 反序列化 try-catch 兜底默认(非法/损坏 value 不让 extract 链路抛)。
- [ ] 不引入新外部依赖(用既有 Jackson)。

## 六、运维考量清单

- [ ] **日志**:entities-config 读取失败告警 log("memoryEntitiesConfig" 关键词,带 traceId);重抽端点复用现有进度日志 memoryReextract。
- [ ] **配置开关**:entities-config 本身即配置,无需额外 feature flag;默认值 = 旧硬上限,零行为变更即灰度。
- [ ] **降级**:读 config 异常 → 走默认值不崩 extract 链路;前端读失败 → 旋钮填默认值不阻塞保存其他项。
- [ ] **健康检查**:无需新增(KV 表健康已覆盖)。
- [ ] **监控**:无新指标(纯设置层,无高频调用)。
- [ ] **存量兼容**:V45 seed 默认值 = 旧硬编码,老数据/老 prompt 行为零变;改配后老记忆靠既有重抽端点收敛。

## 七、功能联动点清单(v1.9 三处对齐)

1. **检索模式 ↔ 旋钮显隐**:切模式 → 对应旋钮组显/隐(entities-config 仅 LLM_KEY/VECTOR_KEYWORD 显)。反向:切回 LLM_FULL_CONTEXT → entities 卡片隐但不丢已填值(本地 ref 保留,保存时按当前模式决定是否提交)。
2. **entities-config 数值 ↔ Judge prompt**:改 totalMax → 下次 extract prompt 注入新值 + readEntities 截断阈值变。**不立即重抽老数据**(需手动点重抽按钮)。
3. **重抽端点 ↔ 新 config**:点重抽 → reextractEntitiesAsync 走 MemoryConflictJudge.extract → 自动吃最新 config(零改动,天然联动)。
4. **半选/边界**:totalMax < hypernymMax(逻辑冲突)→ DTO 校验拒;min>max → 拒;前端旋钮同步 min/max 约束联动(NInputNumber :min/:max)。

## 八、人工测试方案(需产)

UI 显隐属主观体验 + 联动半选 → 按 Phase3.1 §三需产测试方案。覆盖:
- 正向:4 模式各自显对应组、entities 旋钮可调可存、重抽后新记忆按新 totalMax 抽。
- 反向/半选:切模式不丢已填值、非法值被拒、读 config 失败兜底默认。
- 联动:见上 §七 逐条。

## 九、出口条件(对齐 Phase3.1 §七)
- [x] plan 全步骤勾选。
- [x] 安全 + 运维清单逐条落实(DTO 校验 normalized / JSON 兜底 / 权限 role:manage 既有)。
- [x] 测试方案产出(联动点全覆盖含反向/半选/兜底)。
- [x] README + Feature Map + User-Ops(B 类)产出。
- [x] mvn compile 绿 + vue-tsc 绿,全 commit(0cd4888d / 470bfedf)。
- [x] 进度文档 ≤5000 tokens。
- [x] 规范沉淀(prompt 命名 token 范式)。
