# Office AI 安全助手实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: `phase3-implement`。模型 API Key 只允许存在服务端。

**目标：** 提供自然语言规则、Excel 字段映射、数据质量体检和本地敏感扫描；AI 只返回待确认建议。

---

### Chunk 1：本地敏感扫描与发送预览

- [ ] **目标：用户清楚知道哪些内容将离开本机。**
  - 动作：本地识别手机号、邮箱、证件号、银行卡、姓名候选和自定义词；生成脱敏副本；发送对话框逐字段展示并默认脱敏。
  - 涉及文件：`src-tauri/src/office/ai/sensitive_scanner.rs`、测试、`src/types/officeAi.ts`、`src/components/office/ai/AiDataPreview.vue`、`src/stores/officeAiStore.ts`、语言包。
  - 依赖：统一安全底座。
  - 伪代码：`extract minimum metadata/text -> detect spans -> mask -> require explicit confirmation`。
  - 验证：未确认不发请求；路径、密码和文件正文默认不在 payload；键盘可展开查看每类数据。

### Chunk 2：服务端 AI 网关与 Key 隔离

- [ ] **目标：客户端无法获得或复用平台 Key。**
  - 动作：新增 Provider 接口、服务端密钥读取、超时/最大 Token/熔断；DTO 只接受四类功能的版本化 schema。
  - 涉及文件：`server/src/main/java/com/superprogrammer/officeai/controller/OfficeAiController.java`、`service/OfficeAiGatewayService.java`、`provider/AiProvider.java`、供应商实现、DTO、配置、JUnit 测试。
  - 依赖：Office Pro 与 AI 钱包。
  - 伪代码：`authenticate -> validate feature/schema/size -> reserve -> provider with server secret -> parse -> settle/release`。
  - 验证：前端包/响应/日志无 Key；超时和供应商 5xx 释放积分；未知功能拒绝。

### Chunk 3：防白嫖与可观测性

- [ ] **目标：防重复、超量、账号共享和成本失控。**
  - 动作：Redis 多层限流、用户并发、日限额、requestId 幂等、重复载荷检测、月成本熔断和审计指标。
  - 涉及文件：`server/src/main/java/com/superprogrammer/officeai/service/OfficeAiRateLimiter.java`、`OfficeAiUsageService.java`、配置/指标/测试、`SettingKeys.java`。
  - 依赖：Chunk 2。
  - 伪代码：`rate keys=user/device/ip/feature; reject before provider; alert on anomaly`。
  - 验证：并发越限、重放、余额不足、套餐撤销、成本熔断均不调用供应商。

### Chunk 4：自然语言规则生成

- [ ] **目标：把自然语言转换为受限规则，不生成脚本。**
  - 动作：定义 Excel/Word/PPT 规则 schema；AI 返回后本地白名单、类型、范围和风险校验；展示差异并逐项接受。
  - 涉及文件：`src-tauri/src/office/ai/rule_validator.rs`、`src/components/office/ai/AiRuleAssistant.vue`、`src/api/officeAi.ts`、store/测试、服务端 prompt 模板资源。
  - 依赖：Chunk 1–3。
  - 伪代码：`AI JSON -> strict decode -> reject path/command/script/unknown field -> merge accepted rules`。
  - 验证：提示注入文本无法增加未授权动作；全拒绝不改变手工规则；重复请求不重复扣费。

### Chunk 5：Excel 字段映射建议

- [ ] **目标：提高不一致表头映射效率，但用户保留最终决定权。**
  - 动作：只发送列名、类型、统计摘要和用户确认的脱敏样例；返回候选映射、理由和置信度；接入现有字段映射页。
  - 涉及文件：`src-tauri/src/office/ai/column_payload.rs`、`src/components/office/ai/AiColumnSuggestions.vue`、Excel store、服务端 schema/测试。
  - 依赖：Excel Chunk 4、AI Chunk 1–3。
  - 验证：AI 建议不会自动勾选；类型冲突仍是阻断项；拒绝建议后手工映射完整可用。

### Chunk 6：数据质量体检

- [ ] **目标：本地检测事实，AI 负责解释和修复建议。**
  - 动作：本地计算重复、缺失、异常值、日期/金额格式；只把统计摘要发送 AI；结果关联行/列但不自动修改。
  - 涉及文件：`src-tauri/src/office/ai/data_quality.rs`、测试、`src/components/office/ai/DataQualityReport.vue`、服务端 schema/测试。
  - 依赖：Chunk 1–3。
  - 伪代码：`local facts -> optional AI explanation -> user converts selected advice to deterministic rule`。
  - 验证：无 AI 时本地报告仍可用；AI 积分耗尽不影响批处理；异常定位可复核。

### Chunk 7：管理员与运维

- [ ] 增加余额/用量查询、按 requestId 诊断、功能开关、模型/成本上限、失败率和成本告警；禁止后台查看正文；运行 JUnit/Vitest/cargo tests，更新文档并提交存档点。

## 重点坑点与安全清单

- Prompt Injection：文档只作为数据；模型无工具和文件权限。
- Token 估算偏差：按供应商实际用量结算，预扣保守上限后释放差额。
- 日志泄露：DTO `toString`、异常和 HTTP 客户端日志必须脱敏。
- 模型输出漂移：版本化 schema、严格解析、未知字段拒绝，不用正则从自然语言猜动作。
- 缓存串用户：若缓存建议，key 必须含 userId、feature、payload digest、schema version，内容仍不得跨用户共享。

## 术语表

| 术语 | 大白话 | 示例 |
|---|---|---|
| payload digest | 请求内容的不可逆摘要 | 判断是否是重复请求 |
| schema version | AI 输出结构的版本号 | 升级字段时可兼容旧结果 |
