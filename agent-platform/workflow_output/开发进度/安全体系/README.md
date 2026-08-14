# 安全体系（S1~S3）· 技术说明

> 多 Agent 平台纵深防御体系。本 README 面向开发者（A 类），用户自助指引见 [../../docs/user-ops/安全体系用户操作手册.md](../../docs/user-ops/安全体系用户操作手册.md)。

## 阶段一览

| 阶段 | 主题 | 覆盖 | 状态 |
|---|---|---|---|
| S1 | 止血与基线 | 密钥治理/gitleaks 门禁/`${}` SQL 门禁/安全事件中心/限流闸门/上传加固/安全响应头 | 已验收（Phase4 闭环） |
| S2 | 防篡改与越权 | 审计 HMAC 哈希链+锚点/单点登录/低余额在途闸门/越权 IT 脚手架（AbstractPrivilegeIT）/注册密码策略 | 已验收（Phase4 闭环） |
| S3 | AI 安全（OWASP LLM） | LLM01 围栏+KB 隔离 / LLM02 输出打码 / LLM07② 提示词指纹 / LLM10 限流+会话封顶 / LLM08 检索越权 IT / C4 参数校验 / C2 前端渲染门禁 | 代码侧闭环（2026-08-15，待 Phase4 交叉审查） |

## S3 架构速记（六步）

1. **围栏（Step1）**：KB 证据/联网结果/记忆三路不可信注入点统一过 `UntrustedContentFence.wrap()` 包 `<retrieved_data>` + 声明；strip 防内容自带伪围栏逃逸。挂点：RagRetrievalService / ChatSessionService.resolveWebSearch / 记忆召回两处。
2. **KB 隔离（Step2）**：解析线程 `scanInjection()`（InjectionSignatureLibrary.matchFull，4k 滑窗）命中 → QUARANTINED + 残留节点/向量清洗 + KIND_KB_INJECTION（HIGH）；`unquarantine` 重发 DocumentUploadedEvent 复活解析。前端文档列表红标 + 解除按钮（knowledge:manage）。
3. **输出打码+指纹（Step3）**：`LlmGateway` 唯一出口——同步 `maskSync`、流式 StreamMasker（40 字符 carry 跨 chunk）+ PromptLeakDetector（SkillStep/WorkflowNode 静态 systemPrompt 的 32 字符 shingle，≥2 连中遮蔽）。检测异常一律原文直通（不自残）。
4. **限流+封顶（Step4）**：canvas_run/workflow_run/rag_ask @RateLimit 10/60s 热更；`LlmRequest.sessionId` 全链归户 → 发送前 `SUM(tokens)≥security.llm.session-token-cap`（默认 500000，0=关）→ 42903 固定话术 + MEDIUM 事件。
5. **越权 IT+校验（Step5）**：`KnowledgeRetrievalPrivilegeIT` ×4（B 检索 A KB 403 / 回调 token 401 fail-closed / 伪造 userId 按 execution 归属 / 缺 executionId 400）；13 写端点 @Valid + 上限约束。
6. **前端门禁（Step6）**：`frontend-html-gate.sh` grep v-html/innerHTML 命中即红（白名单：MentionTextarea 全段转义）；红线入通用约束.md（DOMPurify 强制）。

## 运维开关（全热更，安全管理 → 规则配置）

`security.ai.fence.enabled` / `security.ai.kb-scan.enabled` / `security.ai.output-mask.enabled` / `security.ai.prompt-leak.enabled` / `security.llm.session-token-cap`（0=关）/ `security.rate.canvas_run|rag_ask|workflow_run.max`。原则：检测层故障一律放行 + WARN（可用性 > 强制力）。

## 指标与事件

- 指标：`security.ai.fence.applied` / `security.ai.kb.quarantined` / `security.ai.output.masked` / `security.ai.prompt.leak`。
- 事件：KB_INJECTION（HIGH）/ PROMPT_LEAK（HIGH）/ LLM_SESSION_CAP（MEDIUM），均 ACT_NONE（处置归管理员复核），payload 只带 ids/计数（PII 红线）。

## 测试资产

单测：UntrustedContentFenceTest 7 / DocumentParserInjectionScanTest 6 / InjectionSignatureLibraryTest 8 / SensitivePatternCatalogTest 6 / OutputSanitizerTest 11 / ChatSessionServiceTest 21（含封顶×4）。IT：KnowledgeRetrievalPrivilegeIT 4（真实 PG，`mvn test -Dsurefire.excludedGroups= -Dtest=KnowledgeRetrievalPrivilegeIT`）。门禁：`scripts/security/{frontend-html-gate,mybatis-dollar-check,gitleaks-scan}.sh`。

## 后续

S4（对拍去重后子 plan）→ S5。开发进度：[开发进度5-S3.md](开发进度5-S3.md)；坐标底图：[../../docs/feature-map/安全体系.feature-map.md](../../docs/feature-map/安全体系.feature-map.md)。
