# 计划 · 视频 TOKEN 计价按分辨率分档（V162 槽位 + 回落通用价）

> 规格：[视频TOKEN分辨率分档计价.md](../specs/视频TOKEN分辨率分档计价.md)（Phase 1 已定稿，Q1~3 已拍板）。
> 主规格联动：[积分计费系统.md](../specs/积分计费系统.md) B4。
> 硬闸门：本计划经用户许可后才进 Phase 3 实现。

## Chunk 总览（依赖顺序）

| # | 内容 | 层 | 依赖 |
|---|---|---|---|
| C1 ✅ | 迁移 V162 + 实体字段 | DB/实体 | 无 |
| C2 ✅ | 计价取档（videoCost）+ 单测 | 后端计费 | C1 |
| C3 ✅ | 配置侧全链（校验/透传/导出导入/模板）+ 单测 | 后端配置 | C1 |
| C4 ✅ | 价表页表单 4 槽 + 列表列 + API 类型（`b69f4a39`） | 前端 | C3（DTO 契约） |
| C5 ✅ | 文档收尾（feature-map / user-ops / 人工测试标记） | 文档 | C2~C4 |

---

## C1 · 迁移 V162 + 实体字段

- **目标**：`pricing_rule` 具备 `token_price_per_resolution JSONB` 存储；实体可读写。
- **动作**（伪代码）：
  1. 新建 `V162__pricing_token_price_per_resolution.sql`：
     `ALTER TABLE pricing_rule ADD COLUMN token_price_per_resolution JSONB;`
     （无数据迁移、无索引、无 CHECK——服务层校验，est V153 同范式）
  2. `PricingRuleEntity` 加字段，**逐字镜像 estPerResolution 先例**（:79-81）：
     `@TableField(typeHandler = JsonbStringTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS) private String tokenPricePerResolution;`
     注释注明：仅 VIDEO+TOKEN 行有意义，键 ⊆ {480p,720p,1080p,4k}，值 ¥/百万 token，无 general 键（通用价=priceInputPerMillion 列复用）。
- **涉及文件**：`db/migration/V162__pricing_token_price_per_resolution.sql`、`billing/entity/PricingRuleEntity.java`（2 个）
- **依赖**：无
- **验证**：
  - `mvn -q compile` 过
  - 本地 PG 起 Spring Boot（或跑任一 @SpringBootTest IT）→ Flyway 应用 V162，`\d pricing_rule` 见列，存量行值为 NULL
  - 回滚演练：`ALTER TABLE pricing_rule DROP COLUMN token_price_per_resolution;` 可执行（手册记录）

## C2 · 计价取档（videoCost TOKEN 分支）

- **目标**：结算真实扣费 `价 = 槽位[normalize(res)] ?? 通用价`；缺价口径逐字节不变。
- **动作**（伪代码）：
  ```
  // PricingService.videoCost TOKEN 分支（唯一改动点 :384-388）
  if tokens 无效: return ZERO                      // 现状不动
  价 = 槽位价(rule.tokenPricePerResolution, resolution) ?? rule.priceInputPerMillion
  if 价 == null: return ZERO                       // 「缺价=0 元」口径钉死不动
  return price(价, tokens)                          // 既有 helper，6 位 HALF_UP

  槽位价(json, res):                                // 新私有方法，仿 resolveEstPerSecond(:215)
    if json 空或 res 空: return null
    try: map = 静态 MAPPER.readValue(json)          // 复用类内既有 ObjectMapper 先例
         return map.get(normalizeResolution(res))   // "4K"→"4k"，与配置侧同函数
    catch: log.warn("槽位价解析失败回落通用价 ruleId=…"); return null   // 脏配置不炸结算
  ```
  - 入参 resolution 已在结算链透传（MediaGenTaskWorker:258），worker 零改动。
- **涉及文件**：`billing/service/PricingService.java`、`PricingServiceTest`（2 个）
- **依赖**：C1
- **验证**（单测，全部先红后绿）：
  1. 4K 任务 + 槽{"4k":111.2} → cost = tokens÷1M×111.2（6 位 HALF_UP）
  2. 720p 任务未配槽 → 回落 priceInputPerMillion
  3. 槽+通用全空 → ZERO（0 元交付口径）
  4. "4K"/" 1080P " 大小写空格 → normalize 命中
  5. resolution=null → 直取通用价（不进槽）
  6. 脏 JSON（"{bad"）→ warn + 通用价，不抛
  7. SECOND 分支、est 估价、tokens=null → 既有用例全绿（回归）
  - 集成（真 PG）：Cdance 两面各配 4 档 → mock usage.total_tokens 提交结算 → 实耗=4k 档价×token÷1M；HOLD 多退少补金额随档变
- **明确不动**：resolveRule 命中链、fallback 不跨行取价（用例：只配无参考行，带参考任务 fallback 到该行通用价）。

## C3 · 配置侧全链（校验/透传/导出/导入/模板）

- **目标**：价表 CRUD/导入导出全链支持槽位，VTR-4/5/6 口径落地。
- **动作**（伪代码，全部仿 estPerResolution 既有范式 :464-560）：
  1. `validatePricingRule`：非 VIDEO+TOKEN 行带槽 → 400；键白名单 ⊆ {480p,720p,1080p,4k}（**无 general**，有即 400）；值 >0（est 允许 ≥0，此处更严——价非 0 才有意义）；`resolution` 列仍恒 null（D6 回归钉死不动）
  2. 归一化函数 `normalizeTokenPriceSlots`（仿 normalizeEst :539-560）：键 trim+小写、剔 null 值、未知键/非正数拒；**归一后空 map → 存 NULL**（与未配等价）
  3. `applyRequest`（:587）：request.tokenPricePerResolution != null 才落（null=不动现有槽，编辑表单全量提交时前端恒带字段）；序列化失败 IllegalStateException 同 est
  4. `toExportItem`/`toItemRequest`（:613 一带）+ `PricingRuleExportItem`/`PricingRuleRequest` 加 `Map<String,BigDecimal> tokenPricePerResolution`
  5. 导入 upsert（:151）三分支：**字段缺失或 null=不动库中槽（防旧文件误清）；`{}`=清空（存 NULL）；非空=整体覆盖**（部分键=其余档被清，导入预检提示语明示「整体覆盖」）
  6. `generateTemplate`：VIDEO TOKEN 行预填 4 槽空骨架（值空，注释「留空=按通用价」）
- **涉及文件**：`billing/service/PricingConfigService.java`、`billing/dto/PricingRuleExportItem.java`、`billing/dto/PricingRuleRequest.java`、VO（价表列表项 DTO，仿 est 字段位置）、`PricingConfigServiceTest`（≤5 个）
- **依赖**：C1
- **验证**（单测）：
  1. 合法 4 键过；非法键/general 键/0/负数/SECOND 行带槽/CHAT 行带槽 → 400
  2. 编辑：提交 null → 槽不动；提交 {} → 槽清空；提交部分键 → 整体覆盖
  3. 导出往返：带槽导出→导入→库值一致；旧格式文件（无字段）导入成功且不动槽
  4. 模板含 4 槽骨架
  5. 判重键/身份锁定/候选展开用例全绿（回归）
  - 集成（真 PG）：价表页接口 create→list→export→import 全链落库断言

## C4 · 前端价表页（表单 4 槽 + 列表列 + 类型）

- **目标**：admin 可视化配 4 档价；列表可读；导入导出透传。
- **动作**（伪代码，全部仿 est 槽位 UI 先例 PricingConfigView.vue:108-126）：
  1. TOKEN 分支表单：现有「输入每百万 token 价」label → 「通用每百万价（¥/百万，未单列档位按此计）」；下方 4 个选填输入（480p/720p/1080p/4K，占位「留空=按通用价」）；软提示一行「未配价=按 0 计费」（通用价可空现状不变，非硬校验）
  2. `sanitize`（:360-405）：**VIDEO+TOKEN 行恒带字段**（全空 → `{}` 显式清空；有填 → 只含已填档，空串剔除）；非 VIDEO+TOKEN 行**恒不带字段**（后端 400 双拦之外再挡一层）——防「清空全部槽保存」被后端 null=不动语义吞掉旧槽
  3. 列表新增「每百万价」列：无槽显通用价；有槽显「通用 X · 480p a/720p b/…」摘要（只列已配档）
  4. 导入预检提示补「槽位=整体覆盖，空对象=清空」一句话（既有导入确认弹层内）
  5. `api/billing.ts`：`PricingRuleRequest`/列表项类型 + `tokenPricePerResolution?: Partial<Record<'480p'|'720p'|'1080p'|'4k', number>>`
- **涉及文件**：`views/admin/PricingConfigView.vue`、`api/billing.ts`（2 个）
- **依赖**：C3（DTO 契同盟）
- **验证**：
  - `npx vue-tsc --noEmit`
  - vitest（若有 PricingConfigView 用例则补：TOKEN 行显 4 槽 / SECOND 行不显 / sanitize 全空槽出 `{}`、非 TOKEN 不带字段；无既有用例则以 vue-tsc+人工覆盖）
  - **人工**：给 seedance 有参考/无参考两行各配 通用+4 档 → 保存重进回显；列表摘要正确；导出 JSON 核对字段
- **无障碍**：4 槽输入沿 est 槽位同款 label/aria 结构；纯数字输入 `inputmode="decimal"`。

## C5 · 文档收尾 + 人工测试标记

- **目标**：Phase 4 验收材料齐；不提前勾销问题单。
- **动作**：
  1. `feature-map/积分计费系统.feature-map.md` 价表节补 `token_price_per_resolution` 字段与 videoCost 取档行
  2. `user-ops/积分计费系统用户操作手册.md` 价表配置节补「4 档槽位操作 + 回落规则 + 导入覆盖语义」三句
  3. `人工测试问题/7_积分系统.md` 未解决项挂「待人工验证」标记（**不勾销**——真跑 seedance 4K/未配档回落/导出导入三项后再勾）
  4. 规格《视频TOKEN分辨率分档计价》变更记录补实现行
- **涉及文件**：上述 4 个文档
- **依赖**：C2~C4 完成
- **验证**：文档链接/行号指向真实代码；人工测试三项标记齐全。

---

## 技术坑点预判

- **MyBatis-Plus NULL 更新静默跳过**：JSONB String 字段清空（存 NULL）必须 `FieldStrategy.ALWAYS`（est 先例 :79-80）——漏了则「导入 {} 清空」「编辑清槽」双双不生效且无报错，只能靠单测「提交 {} → 库值 NULL」钉死。
- **脏配置炸结算**：videoCost 抛异常 → 整任务 FAILED 退预扣（fail-closed）。槽 JSON 解析必须 try/catch → WARN + 回落通用价，脏价表不废任务。
- **键归一双侧失配**：「4K」大写直存则结算取 "4k" 取不到 → 静默多扣/少扣。归一必须在**存储时**做（normalizeTokenSlots 内），结算侧同用 `PricingService.normalizeResolution`，两侧一个函数。
- **双 JSONB 撞脸**：`est_per_resolution`（¥/秒，预估）与 `token_price_per_resolution`（¥/百万，扣费）同表同形态——review/测试盯字段名，用例显式区分（配 est 不配槽 → 预估变扣费不变）。
- **BigDecimal 全程**：禁 double 中转；槽值×tokens 走既有 `price()` helper 保 6 位 HALF_UP。
- **Flyway 号位**：V161 已被占用，新文件 V162；已发布迁移（V160/V161）checksum 一字不动。
- **性能**：结算一次 `ObjectMapper.readValue`（静态 mapper 复用，est 同款）——视频任务完工一次一单，非热路径，不加缓存。取档价=同一已命中行内 JSON 取键，**零新增 SQL**。列表摘要纯前端拼接。预估链（estimateRatePerSec/est_per_resolution）零改动——别把槽价泄进估价。
- **前端 SCSS 嵌套坑**（本轮播放键同款）：新表单行沿用既有 grid/flex 类，若新建 BEM 块，元素类与兄弟平级。
- **导入三态**：Java 侧字段缺失与显式 null 同为 null（=不动），`{}` 才是清空——提示语按此口径写，别造「缺字段=清空」歧义文案。

## 安全检查清单（P3 逐项验证）

- [ ] 端点零新增：create/update 复用 `pricing:manage` + `@AuditLog`（现状不动，回归确认）
- [ ] 服务层白名单：键 ⊆ 4 档 + 值 >0（防异常键/脏价入 JSONB）
- [ ] 导入 200 行上限 + 逐行容错现状维持
- [ ] 全链 BigDecimal（6 位 HALF_UP）防舍入漂移
- [ ] 无 PII、无新日志敏感字段
- [ ] 普通用户不可达价表端点（现状 ownership/权限回归）

## 功能联动点清单（只列正向必漏 bug）

| # | 触发动作 | 联动对象 | 预期变化 | 边界（反向/取消/批量） |
|---|---|---|---|---|
| 1 | 表单 kind 切非 VIDEO | 4 槽输入区 | 隐藏且提交不带字段 | 切回 VIDEO+TOKEN 重显已填草稿；IMAGE/CHAT 行永不出槽 |
| 2 | videoBillingMode SECOND↔TOKEN 切换 | 槽位区/秒价区互斥显隐 | TOKEN 显槽隐秒价，反之亦然 | SECOND 行带槽提交 → 后端 400（前后端双拦） |
| 3 | 有参考/无参考两行分别配槽 | 列表两行各自摘要 | 互不串价 | fallback 命中无参考行时取**该行**槽/通用，不跨行（C2 用例钉） |
| 4 | 槽位填值保存 | 列表「每百万价」列 | 通用+已配档摘要 | 全空槽=只显通用；清空档位保存 → 摘要回落 |
| 5 | 导出 | JSON 行带 tokenPricePerResolution | 无槽行不带字段 | 旧客户端读新文件忽略未知字段（Jackson 兼容） |
| 6 | 导入 null / {} / 非空 | 库中槽位 | 不动 / 清空 / 整体覆盖 | 一行非法 → 仅该行跳过（逐行容错），他行照常 |
| 7 | 编辑身份字段 | providerId/model/kind/hasReference 锁定 | 槽位可改（非身份字段） | 判重键、候选展开（参考面×2）不因槽变化 |
| 8 | 结算（任务完工） | 流水/账单金额 | 按任务分辨率档价扣 | 分辨率未传/未配/脏 JSON → 通用价或 0 元口径（C2 用例 2/3/5/6） |

## 运维考量清单（7 类逐条落字）

- **可观测性**：**做**——槽 JSON 解析失败 WARN（含 ruleId，可定位脏行）；其余复用现有结算流水/审计日志。不加新指标。
- **配置开关**：**不做**——槽位本身即运营配置；「下线分档」=清槽回落通用价，无需开关无需发版。
- **可回滚**：**做**——迁移 ADD COLUMN nullable，回滚 `DROP COLUMN`；业务回滚=清槽（行为回存量逐字节）。上线前演练记入手册。
- **限流/熔断**：**不做**——无新外部依赖，Ark 链路现状。
- **运维入口**：**做（零新增）**——价表页编辑/导入导出即修脏价入口；无需脚本。
- **告警阈值**：**后续**——D9 est-vs-实耗偏差监控已存在，档价上线自动纳入新口径；单独「档价命中率」告警不本期。
- **容量/性能**：**不做**——JSONB 单行 ≤4 键；结算单次 parse；无增长维度。

## 人工测试标记（自动化覆盖不了，P4 用）

1. admin 价表页给 seedance2.0 配「有/无参考 × 通用+4 档」→ 表单/列表/导出三处一致
2. 真跑 seedance 4K 任务 → Ark 回传 token、扣费单价=4k 档、流水/账单对得上
3. 改跑 720p（未配档）→ 按通用价扣
4. 旧导出文件导入 → 已配槽不被清

## 出口

- [x] C1~C5 全绿（2026-08-27，单测 36/36+53/53、vue-tsc 0、build 过）；人工测试 4 项待 P4 → 全过后勾销 7_积分系统.md 未解决项、回写主规格变更记录
- [x] **硬闸门：未经用户明确许可不写任何实现代码**（用户 2026-08-27「批，进phase3吧」）
