# 开发进度 8 · Phase4 验证（S3+S4+S5 统一）

> 范围：S3（AI 安全）+ S4（文件与主机）+ S5（检测响应与收尾）全部 Step 完成后的统一验证。
> 依据指令：`S3-S5全完成后统一进Phase4验证`。产出：全量回归 + 第二 AI 交叉审查 + 修复 + 记档。

## 一、全量回归（2026-08-15）

| 层 | 结果 | 判定 |
|---|---|---|
| 后端 mvn test | **1960 tests, 2F**（MemoryAssetRecallServiceTest 2 例） | 2F 为**预存**——已在基线 commit 3414eacf（S3 起点前）用 git worktree 复跑证实，与 S3-S5 改动无关 |
| 前端 vitest | **431 passed / 7 failed**（PricingConfig 5 + VideoGen 2） | 7F 为预存（stash 验证范式，Step2 已记） |
| 前端 vue-tsc + build | 0 错误，构建通过 | ✅ |
| python sidecar | 3 collection errors（缺 prometheus_fastapi_instrumentator 模块） | 预存环境项（非代码）；签名对拍 2 例在 Step4 单独跑绿 |
| boot 冒烟 | health UP，err log 空，AEAD 解密错误 0 | ✅（本地库密文=DEFAULT_SECRET，boot 不注 LLM_ENCRYPTION_SECRET） |
| ops 脚本 | security-baseline-check.ps1 语法 0 错；FIM 四态沙箱验证（Step7） | ✅ |

切片测试上下文修复（预存基建漂移，随本轮修）：AuthServiceAuditTest +@Mock MfaService（S5 新依赖）；AuditLogControllerTest +@MockBean SystemSettingService/UserMapper（SecurityConfig/controller 构造演进）；SystemSettingLegacyEndpoint404Test +@MockBean AuthChannelSettingService（08-13 预存 commit 058500e3）。

## 二、第二 AI 交叉审查（cavecrew-reviewer）

范围 `git diff 66194c6f^..HEAD`（S3+S4+S5 共 20 commits，+8033/−437）。**10 findings 全修**，commit `c95ffe68`（16 files +236/−31）：

| # | 级 | 问题 | 修复 |
|---|---|---|---|
| 1 | HIGH | MfaService.isBound 读 secret 异常 fail-open→静默回落单步登录 | **fail-closed**：异常按已绑定处理（挡第二屏走恢复码/管理员通道）——MFA 是强制层，不适用「检测层不自残」 |
| 2 | MED | verifyMfa 一次性非原子（验码成功后才 set 黑名单，双发竞态） | `consumeMfaTokenOnce`：**setIfAbsent 抢占** jti 拉黑位；抢输=同票被复用（重放）→ TOKEN_INVALID + 审计 `mfa_token_replayed` |
| 3 | MED | refresh 旋转拉黑非原子（并发双发同票各自成功旋转） | 旋转拉黑改 **setIfAbsent 原子闸**；抢输按重放拒（计数+审计 `refresh_replayed`） |
| 4 | MED | AES 生产态信号仅 CORS——Nginx 同源生产不配 CORS 漏检 | **prod profile 主信号 + CORS 辅信号**双判定（AesEncryptServiceTest +2） |
| 5 | MED | 前端 401 直跳登录，refreshAccessToken 死代码（用户每 15min 被踢） | request.ts：401 先**静默刷新+重放原请求**（_retry 防环）；单飞共享 Promise；动态 import 避静态环（request←store←api←request） |
| 6 | MED | getRuntimeCallbackHmacMode 大小写/空格敏感（手输 " enforce" 静默回落 DUAL） | trim + equalsIgnoreCase；RuleConfigController PUT 侧枚举键 dual/enforce 显式 400（所见即所得） |
| 7 | MED | ChatController 3 个旧会话入口缺 @RateLimit（LLM 成本攻击面） | POST /sessions、/sessions/{id}/messages(/stream) 补 @RateLimit(chat_send 20/60s) |
| 8 | LOW | persistRecoveryHashes 写失败静默吞=恢复码重放窗口 | 上抛 IllegalStateException→verifyAndConsume 外层按验证失败处理（fail-closed） |
| 9 | LOW | 门禁白名单 grep -v -F 无锚定（"foo.vue:1" 误豁免 foo.vue:12 行） | frontend-html-gate.sh + mybatis-dollar-check.sh 加 **-x 整行精确匹配** |
| 10 | LOW | K1a 只查 0.0.0.0/:: 全接口绑定，单播地址（192.168.x.x）绕过 | 非回环全算：badList 输出 地址:端口 精确定位 |

修复后回归：后端 1960（+4 新增原子竞态用例：MFA 并发重放抢输拒 / refresh 并发旋转抢输拒），2F 仍=预存 MemoryAsset；前端 431/7 预存、tsc+build 绿。

通过域一句话（审查确认）：sync-offsite 增量语义 OK；RuntimeCallbackSecurityFilter enforce 无旁路；SSRF 三路收口 OK；deleteAccount 匿名化彻底；WS Origin fail-closed；蜜罐/围栏/脱敏/配额无假绿。

## 三、出口条件核对

- [x] S3/S4/S5 plan Steps 全部勾选（S3 6步 / S4 6步 / S5 7步）
- [x] 安全检查清单逐项验证（各 plan 内清单 + 交叉审查兜底）
- [x] 运维考量清单落实（开关 9 个入 EDITABLE_KEYS；指标/审计/降级路径随 chunk 埋）
- [x] README / FeatureMap / User-Ops 齐备（S3、S4、S5 三套）
- [x] 全量自动化回归通过（预存失败已记档归因）
- [x] 第二 AI 交叉审查 + 修复 + 复测（`c95ffe68`）

## 四、遗留与后续

- **预存失败处置**：MemoryAsset 2 例、前端 7 例、python 3 collection errors——非安全链改动引入，归原功能域另行修复。
- **记录在案**（本阶段明确不做）：vite@8 major 升级（devDependency，独立任务）；HttpOnly+BFF（待真实 XSS 事件再评估）；DNS rebinding 连接期 IP 绑定（增强项）。
- **需运维窗口人工项**（S5 Step7 已记）：服务器 FIM 基线首跑归档确认；sync-offsite 任务注册（OFFSITE_TARGET 待配）；备份窗口切换。
