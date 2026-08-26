---
description: "子计划 D：价表三改——去分辨率档（§4）+ 闲时价与时段（§5）+ 缓存 token（§6），同一改动面合并"
created-date: 2026-08-26
---

# 子计划 D：价表三改（分辨率/闲时/缓存）

> 主索引：[人工测试遗留问题修复II.plan.md](人工测试遗留问题修复II.plan.md)
> 规格：§4（Q3=A 彻底移除 SECOND 分辨率档）、§5（Q4=B 闲时价覆盖 CHAT+EMBED/RERANK）、§6（Q6=A 缓存从输入基数扣+单列计价）。

## 技术坑点预判

| 坑 | 规避 |
|---|---|
| 存量兼容是硬门槛：老价表（不配闲时/缓存价）计费必须逐分不变 | 三新列全 NULL 默认：off_peak_*=NULL→取忙时列；price_cached=NULL→缓存价=输入价；且 cachedTokens=null 时三腿退化为两腿——单测「改前后计费结果逐分一致」矩阵（老价表×CHAT/EMBED×流式/非流式） |
| OpenAI 与 Claude 缓存语义不同导致双算/漏算 | 口径归一（规格 §6.3）：tokens_input：OpenAI=prompt−cached；Claude=input+cache_creation；cachedTokens=命中读（OpenAI=cached_tokens；Claude=cache_read_input_tokens）。两家各自单测锁口径 |
| Claude 流式 cache_read 在 message_start、output 在 message_delta 两处拼装 | TokenUsage 建造时两段式合并（现有 input/output 已两段式，照抄加一段），单测 mock 两事件序列 |
| V159 SECOND 行合并取错行/误删 | 取 effective_from 最新（同刻取 id 大），其余逻辑删除；合并前迁移日志打印被并行的价差清单；合并后 resolveRule 对残留 resolution 请求走通用行兜底（回归断言） |
| 闲时判定每次查库成为计费热路径开销 | SystemSettingService 本就每请求实时查（既有哲学），计费频次=聊天结算频次（低频），不加缓存；若压测异常再加本地 30s 缓存（记录不做） |
| isOffPeak 跨零点窗口写错（22:00-08:00） | 窗口拆判断：end<=start 视为两段 [start,24:00)+[00:00,end)；单测全矩阵（边界 22:00 整/07:59:59/08:00:00、周末、enabled=false、JSON 非法回退忙时） |
| HOLD 跨闲忙边界多退少补口径漂移 | 显式取舍（规格 §5.3 §4）：hold 按发起时刻价、settle 按完成时刻价，多退少补吸收；单测锁两腿数值 |
| 候选展开/判重/导入导出去 resolution 漏一处 | 清单化六处：availablePricingModels 展开、VIDEO_RESOLUTION_SLOTS 常量（改 EST 用途）、countConflicting 判重、PricingRuleRequest 校验、ExportItem、前端表单——逐步勾 |
| est_per_resolution 误删（TOKEN 预检还靠它） | 明确保留：仅 SECOND 秒价行去分辨率身份；TOKEN est JSON 与四个新列并存，表单分区显示防混淆 |
| 前端表单三改叠加（去下拉+3 闲时输入+缓存输入）互相干扰 | sanitizePricingPayload 按 kind 清空无关字段扩展三新列；编辑态回显 NULL 显示 placeholder「留空=同忙时/输入价」 |

## 实现步骤

- [x] **D1：V160 迁移（结构+数据）**（实际 V160：V159 已被 A 计划回填占用；物理 DELETE 非 deleted=1——表无 deleted 列，append-only，价差 NOTICE 留痕）
  - **目标**：新列就位、SECOND 分辨率行合并
  - **动作**（纯 SQL Migration）：
    ```
    ALTER pricing_rule ADD:
        off_peak_input_per_million  NUMERIC(12,4) NULL
        off_peak_output_per_million NUMERIC(12,4) NULL
        off_peak_cached_per_million NUMERIC(12,4) NULL
        price_cached_per_million    NUMERIC(12,4) NULL
    ALTER llm_usage_logs ADD cached_tokens BIGINT NULL
    SECOND 行合并（kind=VIDEO 且 video_billing_mode='SECOND'）：
        按 (provider_id, model, has_reference) 分组多行时：
        保留 effective_from 最新（同刻 id 大）一行 → resolution=NULL
        其余 UPDATE deleted=1（逻辑删除）；迁移日志（RAISERROR NOTICE/注释）记录价差清单
    索引重建：去 resolution 段 → (kind, model, has_reference, effective_from DESC)
    ```
  - **文件**：`db/migration/V159__pricing_offpeak_cached_resolution.sql`（新）
  - **依赖**：无
  - **验证**：本地跑迁移——多分辨率模型合并为一行（最新价）；老行逻辑删可查；重复执行安全（Flyway 单次）

- [x] **D2：PricingService 三改（计价核心）**（c9e1e43b；29/29——老口径逐分一致硬门槛/闲时列/缓存回落链/isOffPeak 矩阵全覆盖；另 69efa487 先行补提交了 8-25 遗留的 estimateVideoYuan fail-closed 实现，解耦两批改动）
  - **目标**：缓存三腿 + 闲时选价，签名不变
  - **动作**：
    ```
    + isOffPeak(LocalDateTime moment)：
        cfg = systemSetting JSON billing.off-peak.schedule（解析失败/enabled=false → false）
        周末 = moment 按 Asia/Shanghai 的 DayOfWeek∈{SAT,SUN}
        windows = 周末? cfg.weekend : cfg.weekday；end<=start 拆两段
        moment 时间 ∈ 任一窗口 → true
    + pickPrice(rule, moment, busyCol, offPeakCol)：
        isOffPeak && offPeakCol!=null ? offPeakCol : busyCol
    textCost(rule, in, cached, out, moment=now)：
        pIn  = pickPrice(input)；pOut = pickPrice(output)
        pCache = isOffPeak ? (offPeakCached??cached??pIn) : (cached??pIn)
        = in×pIn/1M + (cached??0)×pCache/1M + out×pOut/1M
    computeCost CHAT/EMBED 分支改调 textCost（cached 由 TokenUsage 传入，null 时两腿退化）
    单测重载：computeCostAt(rule, usage, moment) 显式时刻
    ```
  - **文件**：`billing/service/PricingService.java`、复用 `system/service/SystemSettingService.java`
  - **依赖**：D1
  - **验证**：单测——①老价表逐分一致矩阵；②闲时三价组合；③缓存价 NULL 回退；④闲时×缓存正交矩阵；⑤isOffPeak 全矩阵（含跨零点/周末/非法配置回退）

- [x] **D3：TokenUsage + Provider 解析（口径归一）**（8e914911；30/30。偏差：embed/rerank 响应无缓存字段，两处未加解析——计费侧 D2 已强制 EMBED/RERANK cached=null，语义不变）
  - **目标**：两家协议缓存字段全接上
  - **动作**：
    ```
    TokenUsage +cachedTokens(Long)
    OpenAICompatibleProvider 三处（流式末 chunk/chat 非流式/embed/rerank）：
        usage.prompt_tokens_details.cached_tokens → cachedTokens
        tokensInput = prompt_tokens − (cached??0)
    ClaudeProvider 两处：
        非流式：cache_read_input_tokens → cachedTokens
                tokensInput = input_tokens + cache_creation_input_tokens(??0)
        流式：message_start.usage 里同口径取（两段式拼装处）
    cache_creation 只并入输入基数不单独计价（1.25 溢价不建模，规格取舍）
    ```
  - **文件**：`llm/dto/TokenUsage.java`、`llm/provider/OpenAICompatibleProvider.java`、`llm/provider/ClaudeProvider.java`
  - **依赖**：无
  - **验证**：单测——OpenAI 带/不带 prompt_tokens_details；Claude 带/不带 cache_read/creation；两家口径各自锁数值

- [x] **D4：usage 落库与协议透传**（e649889c；UsageCollectorTest 8/8 + 67 回归。偏差：batchInsert 死代码未补列；D3 已并做 Provider 侧）
  - **目标**：cached_tokens 进表、进 DONE 消耗显示
  - **动作**：
    ```
    LlmUsageLogEntity +cachedTokens；UsageCollector.record 增参（调用点全 grep 补传）
    StreamEvent USAGE/DONE 的 usage map +cachedTokens（null 省略）
    usage 查询 VO（UsageDetailVO 类）+cachedTokens；前端调用明细列「缓存命中」
    前端聊天 DONE 消耗文案：「本次消耗 N tokens ≈ X 积分（含缓存命中 M）」M 省略当 null
    ```
  - **文件**：`billing/entity/LlmUsageLogEntity.java`、`billing/service/UsageCollector.java`、`chat/dto/StreamEvent.java`、`chat/service/ChatSessionService.java`、`billing/dto/UsageDetailVO.java`、`frontend/src/views/BillingAdminView.vue`（明细列）、聊天页组件
  - **依赖**：D3
  - **验证**：单测 record 透传；人工——同会话第二条消息 cachedTokens>0 且总积分低于无缓存

- [x] **D5：计费链透传（hold/settle）**（daa31f6；26/26。onSuccess/settleChatHeld 重载 +cachedTokens 尾参，旧重载委托 null→老调用两腿语义逐分不变；holdChat/settleChatCancelled/FAILED 路径不动——hold 时缓存命中不可预知，settle 用真实 cachedTokens 多退少补）
  - **目标**：缓存进三腿计价、hold 保守口径落地
  - **动作**：
    ```
    LlmBillingService：onSuccess/settleChatHeld 把 usage.cachedTokens 传 computeCost
    holdChat 估算：cached 不可预知 → 按 0（未命中保守预估），注释写明
    ESTIMATED 兜底路径 cachedTokens 落 null
    ```
  - **文件**：`billing/service/LlmBillingService.java`
  - **依赖**：D2、D3
  - **验证**：单测——hold 未命中/结算命中的多退少补数值；回归 HOLD 幂等键路径

- [ ] **D6：配置面去分辨率（后端）**
  - **目标**：候选/判重/校验不再有 SECOND 分辨率维度
  - **动作**：
    ```
    availablePricingModels：VIDEO 候选 = 参考面×2（去 ×5 档展开），hint 同步
    countConflictingProviderModelHasRefResolution：去 resolution 维（改名 …HasRef）
    保存校验：SECOND 行 resolution 强制 null（非 null 拒绝）；VIDEO_RESOLUTION_SLOTS
        仅保留 est_per_resolution JSON 校验用途（更名 EST_RESOLUTION_SLOTS）
    导入：SECOND 行 resolution 字段忽略；导出/模板：不再带
    行身份 (provider, model, kind, hasReference)——编辑不可改身份维持
    ```
  - **文件**：`billing/service/PricingConfigService.java`、`billing/mapper/PricingRuleMapper.java`、导出导入 DTO
  - **依赖**：D1
  - **验证**：单测——候选 2 槽位；判重不看 resolution；带 resolution 的旧导出导入成功且被忽略；老结算请求（带 resolution）命中通用行

- [ ] **D7：价表前端表单（三改合一）**
  - **目标**：视频两行制；文本类闲时/缓存输入
  - **动作**：
    ```
    PricingConfigView.vue：
        删 SECOND 分辨率下拉（resolutionOptions 移除）；TOKEN est 槽位保留
        CHAT/EMBED/RERANK 表单增：闲时输入价/闲时输出价/闲时缓存价/缓存价
            四输入（placeholder「留空=同忙时/同输入价」）
        sanitizePricingPayload 扩三新列清空逻辑；'' → null
    ```
  - **文件**：`frontend/src/views/admin/PricingConfigView.vue`、`frontend/src/api/billing.ts`（PricingRuleRequest 增字段）
  - **依赖**：D6
  - **验证**：人工——新增 Cdance2.0 只有两行（有/无参考）；文本模型配闲时价保存回显；留空行为=改前

- [ ] **D8：闲时时段配置页**
  - **目标**：admin 可配每日闲忙时段（分工作日/周末）
  - **动作**：
    ```
    system_settings 键 billing.off-peak.schedule：
        {"enabled":false,"timezone":"Asia/Shanghai",
         "weekday":[{"start":"22:00","end":"08:00"}],
         "weekend":[{"start":"00:00","end":"24:00"}]}
    SystemSettingService：getOffPeakSchedule/updateOffPeakSchedule（JSON 解析+校验：
        HH:mm 格式/数组≤4/非法抛参数错——非法配置拒绝保存而非静默回退）
    SystemSettingController：并入 GET/PUT /api/settings/billing（同款权限+@AuditLog system:update 类码）
    SettingsView.vue 计费 tab：增「闲时时段」卡——enabled 开关 +
        工作日/周末各一组窗口编辑器（增删窗口行，n-time-picker）
    ```
  - **文件**：`system/service/SystemSettingService.java`、`system/controller/SystemSettingController.java`、`frontend/src/views/SettingsView.vue`
  - **依赖**：D2（读侧同键）
  - **验证**：单测 JSON 校验（格式/段数/跨零点合法）；人工——改配置即时生效（闲时发起聊天按闲时价）

- [ ] **D9：est 偏差校准提示（附带项，规格 §1.4-5）**
  - **目标**：TOKEN 模式 est 输入旁显示近 7 天偏差
  - **动作**：PricingConfigService 增轻量聚合（近 7 天同 kind/model/hasRef 的实耗 vs 预估均值偏差%），价表页 TOKEN est 槽位旁 tag 显示「近7天实耗偏高 N%」；查询走 SQL AVG 不拉明细
  - **文件**：`billing/service/PricingConfigService.java`、`PricingConfigView.vue`
  - **依赖**：D7
  - **验证**：人工——有历史任务的模型显示偏差 tag

- [ ] **D10：测试收口**
  - 规格 §4.5、§5.5、§6.5 人工测试全过；硬门槛回归：老价表（不配新列）计费与改前逐分一致

## 功能联动点清单

| 触发 | 联动对象 | 预期 | 边界 |
|---|---|---|---|
| 闲时开关切换 | 全部文本计费 | 即时换价 | 跨边界在途回答按结算时刻价（多退少补） |
| 缓存价留空 | 计费 | =输入价 | cachedTokens=null 时缓存腿整体消失 |
| V159 合并行 | 带 resolution 的存量在途任务结算 | 命中通用行兜底 | 回归断言 |
| 去分辨率后新增价表 | 候选列表 | 每模型至多 2 槽位（有/无参考） | TOKEN est 输入仍在（预检用） |
| cachedTokens 进 DONE | 聊天消耗显示 | 「含缓存命中 M」 | null 省略不显示 |
| est 偏差 tag | 管理员校准 est | 收窄多退少补幅度 | 无历史数据时 tag 隐藏 |

## 验证收口

- [ ] D1-D10 全绿；7x-4/7x-5/9x-1 三项可勾销；老价表零变化回归矩阵是放行前提
