# 安全体系（S1~S5）· 技术说明

> 多 Agent 平台纵深防御体系。本 README 面向开发者（A 类），用户自助指引见 [../../docs/user-ops/安全体系用户操作手册.md](../../docs/user-ops/安全体系用户操作手册.md)。

## 阶段一览

| 阶段 | 主题 | 覆盖 | 状态 |
|---|---|---|---|
| S1 | 止血与基线 | 密钥治理/gitleaks 门禁/`${}` SQL 门禁/安全事件中心/限流闸门/上传加固/安全响应头 | 已验收（Phase4 闭环） |
| S2 | 防篡改与越权 | 审计 HMAC 哈希链+锚点/单点登录/低余额在途闸门/越权 IT 脚手架（AbstractPrivilegeIT）/注册密码策略 | 已验收（Phase4 闭环） |
| S3 | AI 安全（OWASP LLM） | LLM01 围栏+KB 隔离 / LLM02 输出打码 / LLM07② 提示词指纹 / LLM10 限流+会话封顶 / LLM08 检索越权 IT / C4 参数校验 / C2 前端渲染门禁 | 代码侧闭环（2026-08-15，待 Phase4 交叉审查） |
| S4 | 文件与主机加固 | F-2 magic number / F-3 解析炸弹三件套 / F-4 存储配额+上传限流 / L6 计费回归断言 / F-5+K3 Nginx 红线 / K1~K5 基线+巡检脚本 | 代码侧闭环（2026-08-15，待 Phase4 交叉审查） |
| S5 | 检测响应与收尾 | A4 refresh 旋转 / A6 TOTP / H SSRF 三路收口 / C5+C8+F2 残点 / G5 审计 / I 供应链+M4 蜜罐 / J2 注销+J4 隐私 / M1 IR+M3 FIM+M5 备份防勒索+M6 狩猎 | 代码侧闭环（2026-08-15，待 Phase4 交叉审查） |

## S4 架构速记（六步）

1. **magic number（Step1）**：`FileUploadValidator` 纯魔数嗅探（Tika `detect(byte[])`，**禁**文件名提示防改名绕过），`store()` 扩展名白名单后第二关；三态：危险拒 40010 / 兼容放行 / 未知放行+计数观察。
2. **解析炸弹三件套（Step2）**：`ImageGuard.dimensions` 头读取不解码（像素炸弹不 OOM，1 亿像素上限）；Tika `parseToString` 显式 100k 字符限长（zip bomb 截断）；FFmpeg `rw_timeout=30s` + 抽帧/拼接帧预算（恶意容器不无限逐帧）。
3. **配额+限流（Step3）**：`store()` 第三关 per-user `SUM(size) WHERE ACTIVE` 配额（默认 2048MB，0=关）；7 上传端点 @RateLimit `upload_file` 10/60s。
4. **L6 断言（Step4）**：零生产代码——两层回归断言锁死「工作流节点 LLM 调用必走 `chat(request, trustedUserId)` 双参计费重载」。
5. **Nginx 红线（Step5）**：手册模板红线（严禁 uploads 直达=IDOR 通道）+ K3 五项（server_tokens/client_max_body_size/慢连接超时/autoindex/limit_req）。
6. **主机基线（Step6）**：`部署手册-主机基线.md`（K1/K2/K4/K5 章）+ `security-baseline-check.ps1` 只读巡检（FAIL 退出码 1，月跑防漂移）。

## S3 架构速记（六步）

1. **围栏（Step1）**：KB 证据/联网结果/记忆三路不可信注入点统一过 `UntrustedContentFence.wrap()` 包 `<retrieved_data>` + 声明；strip 防内容自带伪围栏逃逸。挂点：RagRetrievalService / ChatSessionService.resolveWebSearch / 记忆召回两处。
2. **KB 隔离（Step2）**：解析线程 `scanInjection()`（InjectionSignatureLibrary.matchFull，4k 滑窗）命中 → QUARANTINED + 残留节点/向量清洗 + KIND_KB_INJECTION（HIGH）；`unquarantine` 重发 DocumentUploadedEvent 复活解析。前端文档列表红标 + 解除按钮（knowledge:manage）。
3. **输出打码+指纹（Step3）**：`LlmGateway` 唯一出口——同步 `maskSync`、流式 StreamMasker（40 字符 carry 跨 chunk）+ PromptLeakDetector（SkillStep/WorkflowNode 静态 systemPrompt 的 32 字符 shingle，≥2 连中遮蔽）。检测异常一律原文直通（不自残）。
4. **限流+封顶（Step4）**：canvas_run/workflow_run/rag_ask @RateLimit 10/60s 热更；`LlmRequest.sessionId` 全链归户 → 发送前 `SUM(tokens)≥security.llm.session-token-cap`（默认 500000，0=关）→ 42903 固定话术 + MEDIUM 事件。
5. **越权 IT+校验（Step5）**：`KnowledgeRetrievalPrivilegeIT` ×4（B 检索 A KB 403 / 回调 token 401 fail-closed / 伪造 userId 按 execution 归属 / 缺 executionId 400）；13 写端点 @Valid + 上限约束。
6. **前端门禁（Step6）**：`frontend-html-gate.sh` grep v-html/innerHTML 命中即红（白名单：MentionTextarea 全段转义）；红线入通用约束.md（DOMPurify 强制）。

## 运维开关（全热更，安全管理 → 规则配置）

`security.ai.fence.enabled` / `security.ai.kb-scan.enabled` / `security.ai.output-mask.enabled` / `security.ai.prompt-leak.enabled` / `security.llm.session-token-cap`（0=关）/ `security.rate.canvas_run|rag_ask|workflow_run|upload_file.max` / `security.upload.magic-sniff.enabled` / `security.upload.max-pixels`（1 亿）/ `security.upload.max-parse-chars`（10 万）/ `security.user.storage-quota-mb`（2048，0=关）。原则：检测层故障一律放行 + WARN（可用性 > 强制力）。

## 指标与事件

- 指标：`security.ai.fence.applied` / `security.ai.kb.quarantined` / `security.ai.output.masked` / `security.ai.prompt.leak` / `security.upload.magic.denied{reason}` / `security.upload.magic.unknown` / `security.upload.quota.denied`。
- 事件：KB_INJECTION（HIGH）/ PROMPT_LEAK（HIGH）/ LLM_SESSION_CAP（MEDIUM），均 ACT_NONE（处置归管理员复核），payload 只带 ids/计数（PII 红线）。S4 拒收为业务拦截只计数不进事件中心；巡检 FAIL 才是告警面。

## 测试资产

单测：UntrustedContentFenceTest 7 / DocumentParserInjectionScanTest 6 / InjectionSignatureLibraryTest 8 / SensitivePatternCatalogTest 6 / OutputSanitizerTest 11 / ChatSessionServiceTest 21（含封顶×4）/ FileUploadValidatorTest 10 / ImageGuardTest 7 / FileStorageServiceTest 11（含配额×4）/ RuntimeNodeCallbackServiceTest 11（含归户断言）/ LlmCallHandlerTest 5（含计费重载断言）。IT：KnowledgeRetrievalPrivilegeIT 4（真实 PG，`mvn test -Dsurefire.excludedGroups= -Dtest=KnowledgeRetrievalPrivilegeIT`）。门禁：`scripts/security/{frontend-html-gate,mybatis-dollar-check,gitleaks-scan}.sh`；巡检：`scripts/ops/security-baseline-check.ps1`。

## S5 架构速记（七步，2026-08-15 代码侧闭环）

1. **refresh 旋转（Step1 `2ee9bd18`）**：换 access 同时换 refresh（同 sid），旧票拉黑值=`rotated` 区别 logout 的 `1`；黑名单命中且=rotated → 重放检出（MEDIUM 事件+计数，复用 TokenReuseRule）。开关 `security.auth.refresh-rotation.enabled`（默认开）。
2. **TOTP（Step2 `4dc6444f`）**：`TotpService` RFC6238 零依赖自实现（官方测试向量对拍）；绑定流 secret 加密存 system_settings（不在 EDITABLE_KEYS）；两步登录 mfaToken（5min 一次性 jti 拉黑+5 次试错封顶）；8 组恢复码只存 SHA-256。开关 `security.auth.totp.required`（默认关，只建议不硬阻断）。
3. **SSRF 三路收口（Step3 `d039334`）**：连通测试/媒体回源/搜索重定向全过 SsrfGuard——SearxngClient 弃自动跟随改手动逐跳 ≤3 跳每跳 assertPublicUrl；SanitizeUtil 补 CGNAT 100.64/10+IPv6 ULA。
4. **残点（Step4 `a5b7746b`）**：WS Origin 白名单（共读 CORS 配置，误配 fail-closed）；KB visibility/Agent status 枚举白名单；sidecar 回调 HMAC（`ts.body` 签名+±300s 窗+恒定时间比对+BodyCachingRequest 重放 body，dual/enforce 热更双轨）。
5. **密码学审计（Step5 `50f78f5`）**：AesEncryptService 生产态弱密钥 fail-fast（复用 CORS 信号）；审计文档落字 A7 维持 localStorage/F1 sourcemap 关。
6. **供应链+蜜罐（Step6 `c4ddfac`）**：pom profile `deps-check`（不绑构建）+ `deps-check.sh` 五门禁汇总（audit 只卡生产依赖）；HoneypotController 四 canary 404 伪装+HIGH 事件（复用 5min 去重）。
7. **隐私+收尾（Step7 `bdec27a7`）**：DELETE /auth/account 软删匿名化（deleted_{uuid}+DELETED+随机口令+踢全会话+双 token 拉黑+TOTP 清痕）；/privacy 隐私页；运维手册 §十 IR 预案（P1~P4+取证保全）；FIM SHA256 基线段（-Rebaseline 重建）；offsite 弃 /MIR 改 point-in-time 周快照（防勒索扩散）；K6 月度狩猎章。

**S5 开关**：上述 + `security.honeypot.enabled`（默认开）/ `security.runtime.callback.hmac-mode`（dual/enforce）。**指标**：`security.auth.refresh.rotated|replayed` / `security.auth.mfa.verify{result}` / `security.ssrf.denied{source}` / `security.callback.auth{result}` / `security.honeypot.hit{path}`。**事件**：TOKEN_REUSE（MEDIUM，重放检出）/ HONEYPOT（HIGH）。

**测试资产**：TotpServiceTest 9 / MfaServiceTest 13 / AuthServiceTest 31（旋转/两步/注销）/ RuntimeCallbackSecurityFilterTest 12（RFC4231 向量）/ WebSocketConfigTest 3 / KB 可见性 5 / Agent 14 / AesEncryptServiceTest 7 / HoneypotControllerTest 5 / SearxngClient 8 / SanitizeUtil 10 + python 回调加签 2；门禁单入口 `scripts/security/deps-check.sh`。

## 后续

S3+S4+S5 统一 Phase4 交叉审查。开发进度：[开发进度5-S3.md](开发进度5-S3.md) / [开发进度6-S4.md](开发进度6-S4.md) / [开发进度7-S5.md](开发进度7-S5.md)；坐标底图：[../../docs/feature-map/安全体系.feature-map.md](../../docs/feature-map/安全体系.feature-map.md)。
