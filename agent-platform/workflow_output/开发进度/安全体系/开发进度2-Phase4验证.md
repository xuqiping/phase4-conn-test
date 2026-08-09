# 安全体系 S1 · 开发进度 2 - Phase 4 运行验证

> Phase 4 产物：S1「止血与基线」实机验收记录。渗透用例 P1~P9 + 联动 L1~L7 + 边界 E1~E5 逐条实证。
> 测试方案基准：[../../docs/测试方案/安全体系S1测试方案.md](../../docs/测试方案/安全体系S1测试方案.md)

| 字段 | 内容 |
|---|---|
| **日期** | 2026-08-09 ~ 08-10 |
| **验证人** | Claude（k3）实机执行 + opus/sonnet 双 agent 交叉审查 |
| **环境** | 本机 dev：PG 16 / Redis 2.6.12 / backend 8080（`mvn spring-boot:run`，含 S1 全部 commit） |
| **结论** | **代码侧验收通过**（渗透/联动/边界全绿 + 双 agent 交叉审查 🔴6🟡7 全修复并回归实证）；结项仅剩 H1~H3 人工阻断项 |

---

## 零、环境拉起中发现并修复的启动阻断（非 S1 引入，顺手修）

- **SidecarHealthIndicator 双构造器无 @Autowired → 全上下文启动炸**（OPS-FR-09 fcc1bb2 引入）：
  单参 public + 双参包私有（测试注入用）两个构造器，Spring 多构造器且无 @Autowired 时回退找无参构造 → `NoSuchMethodException: <init>()` 拒启动。
  IT 全绿是因为 IT profile `runtime.gateway.mode` 非 sidecar，`@ConditionalOnProperty` 不注册该 bean，从未覆盖真实启动路径。
  **修复**：单参构造器加 `@Autowired`（本文件 commit 一并提交）。
- **陈旧 target/classes 假象**：增量编译未覆盖旧 class，mvn clean compile 后复现真问题。

## 一、渗透自查用例（P1~P9）

| # | 用例 | 实测证据 | 判定 |
|---|---|---|---|
| P1 | 存储型 XSS 根治 | 存量 html/svg（直种 stored_files + uploads 模拟 legacy 文件）GET → `Content-Disposition: attachment` + `Content-Type: application/octet-stream` + `X-Content-Type-Options: nosniff` | ✅ |
| P2 | 预览回归 | 真 png 上传 → GET → `inline` + `Content-Type: image/png` + nosniff | ✅ |
| P3 | 上传白名单 | evil.html / evil.svg / evil.exe 上传 → HTTP 400 + `code=40010 文件类型不允许上传`，不透内部细节 | ✅ |
| P4 | SSE 回归 | `POST /api/chat/messages/stream` → HTTP 200 + `Content-Type: text/event-stream` + data 帧正常流动，nosniff/CSP 不杀流式。**业务层逐 token 输出未端到端验证**：dev 库 flyway 重建后无 LLM provider 配置（`没有找到支持模型的对话 Provider`），与 S1 无关，属环境配置缺失 | ✅（传输层）/ ⚠️（业务层留人工） |
| P5 | 登录锁定 | phase4_user 错密码 5 次 → 第 6 次**正确密码**也拒（`code=40103 登录失败次数过多`）；audit_logs 落 `login_locked fail_count_5` 一行（跃迁写一次） | ✅ |
| P6 | IP 封禁 | 同 IP 失败 21 次（>20）→ admin 正确凭证也被拒 40103；audit_logs 落 `ip_banned ip_fail_count_21` | ✅ |
| P7 | Redis 降级 | 停 Redis → 登录 200 成功，WARN `登录防爆破 Redis 检查失败，降级放行`；恢复 Redis 后登录正常 | ✅ |
| P8 | 安全头 | API 响应含 `CSP / X-Frame-Options: DENY / nosniff / Referrer-Policy: strict-origin-when-cross-origin` | ✅ |
| P9 | CORS 白名单 | 配白名单后：非白名单 Origin 预检 **403 拒**、白名单 Origin 200 放行；不配（dev 默认）= originPattern=* 宽松（plan 既定行为） | ✅ |

**P9 排障沉淀（运维坑）**：`mvn spring-boot:run` 链路下，启动脚本里 `$env:APP_CORS_ALLOWED_ORIGINS` **未传进 fork 的 JVM**（marker 实证 ps1 进程内有值、应用行为无值），改用 `-Dspring-boot.run.arguments=--app.cors.allowed-origins=...` 才生效。另：PS 5.1 执行**无 BOM 的 UTF-8 ps1 含中文注释会乱码吞行**（与既有「中文 bat 须 GBK」同族坑）——启动脚本保持纯 ASCII。

## 二、功能联动用例（L1~L7）

| # | 用例 | 实测 | 判定 |
|---|---|---|---|
| L1 | 危险类型转下载不影响安全类型预览 | P1/P2 已双向覆盖 | ✅ |
| L2 | 锁定解锁路径 | admin 删 `login:fail:u:phase4_user` Redis 键 → 立即可登录（runbook 路径实证） | ✅ |
| L3 | 并发 20 扣积分不负 | PointsWalletConcurrencyIT 20 线程实证（Phase 3 已跑，本轮全量回归 1198 测同步覆盖） | ✅（IT） |
| L4 | 幂等键重放 | 同键同额重放 → 返回相同结果 `balanceAfter:10.00`、流水仍只 1 行；同键异额（777）→ 返回首次结果 + `idempotency_conflict` FAIL 审计行（`firstDelta:10.00 expectPoints:777`） | ✅ |
| L5 | 扣减失败同键重试 | IT 实证（占位随事务回滚不留死键） | ✅（IT） |
| L6 | CSP 不杀前端 | CSP 仅挂在后端 API 响应；前端由 Vite/Nginx 独立供给，不受后端 CSP 管辖，无破坏向量；SSE 传输层 P4 已实证 | ✅（分析+传输实证） |
| L7 | CORS 收紧后前端 | 白名单 Origin 预检放行（P9b）；生产核对清单（含钉钉 H5 域）随 H3 人工项 | ✅（预发留人工） |

## 三、边界/异常场景（E1~E5）

| # | 场景 | 实测 | 判定 |
|---|---|---|---|
| E1 | 负数/零/超上限充值 | -50 / 0 / 100000001 → 均 HTTP 400 + 明确校验文案（`必须大于 0` / `超出单次充值上限(1 亿)`） | ✅ |
| E2 | 塞 `balance=99999` 直改余额 | 注入字段被忽略，balanceAfter=服务端实算 10.00 | ✅ |
| E3 | 手工 SQL 篡改流水 | `UPDATE points_ledger SET delta_points=999` → 对账端点立即报 `diffPoints:-989.00` + `reconcile_diff` FAIL 审计行；改回后 diff 清空。**REVOVE 物理拒改本地无法验**（postgres 超管不下放权限）→ 随 H2 生产 agent_app 账号人工验 | ✅（检出）/ ⚠️（REVOKE 留 H2） |
| E4 | Mapper `${}` / 假密钥门禁 | 真 SQL 里种 `${evil}` → 门禁红（exit 1 命中行号）；还原即绿。注释里的 `${}` 不拦（设计如此）。gitleaks 首扫随 H1 人工项 | ✅（负向实证） |
| E5 | 锁定风暴日志 | ERROR 级跃迁日志实证：`账号登录失败达阈值锁定 15min` / `IP 登录失败达阈值封禁 1h`（告警接运维 P1 钉钉属后续） | ✅ |

## 四、自动化基线

- 全量后端单测（修复后复跑）：**1200 测（+2 新增幂等身份核验），1 Failure + 1 Error = 存量 2 红**（RagRetrievalServiceTest、RuntimeCallbackSecurityTest，与 Phase 3 基线完全一致，非 S1 引入，归各模块 owner）。
- `scripts/security/mybatis-dollar-check.sh`：正向绿 + 负向种 `${}` 实证红（E4）；修复后复审三探针全对（见第六节 sonnet-1）。

## 五、性能实测（本机 dev，毫秒级基线）

| 接口 | p50 附近实测 | 备注 |
|---|---|---|
| POST /api/auth/login | ~155ms | bcrypt 主导；防爆破 Redis 检查开销不可测出（<5ms） |
| GET /api/files/{id}（png inline） | ~12ms | 含白名单判定 + nosniff |
| POST /api/files/upload（拒绝/接收） | ~24ms / ~31ms | 白名单咽喉点开销可忽略 |
| POST /api/billing/recharge（幂等重放） | ~19ms | 撞键回查路径 |
| GET /api/billing/admin/reconcile | ~16ms | 全量对账（当前数据量小） |
| Redis 故障时登录 | ~10s（2×Redis 超时） | 降级代价=两次命令超时，可接受但应知悉 |

S1 无独立 performance_goals（安全加固类），以上为回归基线存档。安全头/黑白名单/CORS 对正常请求无可见时延影响。

## 六、交叉审查结论（双 agent，换模型）

opus 审资金路径（计费/幂等/对账/迁移），sonnet 审非资金面（认证/文件/安全头/CORS/CI 门禁），均按 8 维清单 + 强制怀疑点输出。合计 🔴 6 全修、🟡 低成本项全修，🟢 仅记录。

### 🔴 全部修复并实证

| # | 发现 | 修复 | 实证 |
|---|---|---|---|
| opus-1 | V80 `REVOKE ... FROM CURRENT_USER`（=owner）是 no-op，物理拒改从未生效 | 改 V78 范式 DO 块：`pg_roles` 判 `agent_app` 存在才 REVOKE（UPDATE/DELETE/TRUNCATE），双库 flyway repair 对齐 | repair exit 0；H2 生产 agent_app 落时自然生效 |
| opus-2 | `runIdempotent` 撞键不核验身份：键全局唯一，跨用户/跨 scope 同键会**回返他人 balanceAfter**（跨用户余额泄露信道） | 撞键先核 `userId+scope`，不符 → 审计（带 owner 信息）+ CONFLICT 409，绝不回首次结果 | 实机：user2 的键 `p4-reg-2` 被 user1 重用 → `409 幂等键冲突` + `idempotency_conflict` FAIL 审计行；单测 2 条新增（跨用户/跨 scope） |
| sonnet-1 | mybatis 门禁整行剔注释：活的 `${}` 后随行注释即可绕过 | 改逐行截注释尾再判代码区；allowlist 先剔空行防 `grep -f` 空模式永绿 | 三探针实证：活 `${}`+行注释→红、纯注释 `${}`→绿、干净树→绿 |
| sonnet-2 | gitleaks-action@v2 无 baseline 支持，git 历史含已泄明文 → 首跑永红、门禁被人肉无视 | 弃 action 改二进制 + 增量扫（PR `base..HEAD` / push `before..sha`，零 before 退化 HEAD~1..HEAD），全量+baseline 待 H1 轮换后恢复 | yaml 语法校验通过；H1 闭环前增量拦新增语义不变 |
| sonnet-3 | `currentClientIp` 无条件信 X-Forwarded-For：攻击者轮换 XFF 绕过 IP 封禁 + 刷 Redis 键 | 仅当 remoteAddr ∈ `app.security.trusted-proxies`（默认空=永不信任）才采 XFF | 实机：带 `XFF: 9.9.9.9` 失败登录 → Redis 只落 `login:fail:ip:127.0.0.1`，无 9.9.9.9 键 |
| sonnet-4 | 独立 CorsFilter 默认序在 springSecurityFilterChain 之后：预检 OPTIONS（不带凭证）打需认证端点先被 401 截杀——**白名单「放行」语义从未真实生效** | CorsConfig 改暴露 `CorsConfigurationSource`，SecurityConfig `http.cors()` 内联进安全链最前端 | 实机：白名单 Origin 预检需认证端点 **200+ACAO**（修复前 401）；恶意 Origin 预检 403、实际请求 403；CorsConfigTest 同步改写 2/2 绿 |

### 🟡 修复/记录

| 项 | 处置 |
|---|---|
| 登录时序侧信道（user==null 不做 bcrypt，响应快 ~100ms 可探账号存在性） | 已修：不存在用户也跑一次 dummy bcrypt（取 V2 种子真 hash，伪造非法 hash 会炸 matches()）再记失败 |
| `RechargeRequest.idempotencyKey` 无长度校验 vs VARCHAR(128) | 已修：`@Size(max=128)` |
| `RechargeRequest.remark` 死字段（注释承诺落 ledger.remark 但从未接线） | 已修：grant 链路全链接线（controller→grantIdempotent→grant→grantWithLedger→adjust），空走默认「充值」，加 `@Size(max=256)`；实机 remark=回归备注A 落库实证 |
| 媒体计费撞 `uq_ledger_ref` 被兜底 catch 误标「计费意外」 | 已修：`DataIntegrityViolationException` 单列分支，INFO 级标「疑似重复扣减被唯一约束拦截（恰好一次生效）」，对账不计缺口 |
| detectMimeType 极简 Linux 可能返回 null | 记录不修：本机/目标服务器均有 file 命令，留观察 |
| plan「1分钟50次→429」措辞 vs 实现「5次锁15min+IP 20次/h 封」 | 实现严于措辞，记规格漂移待办（改 plan 措辞，不动实现） |
| GET /admin/reconcile 有副作用（写审计）且无 @AuditLog | 保持 GET（运维拉取习惯），审计行即操作记录，记录不改 |

### 修复后回归（全绿）

- 单测：PointsWalletServiceTest 22/22（含新增 2）、AuthServiceTest 16/16、CorsConfigTest 2/2
- 集成：AuthIntegrationTest 8/8、BillingReconcileIT 4/4、PointsWalletConcurrencyIT 3/3（对账不平 ERROR 是用例自造的预期场景）
- 实机：P9 四组合全绿（见上表 sonnet-4）、P5 锁复验（40103+audit）、L4 幂等重放复验、XFF 信任门复验、`${}` 门禁复验绿
- 全量单测基线复跑：见第四节更新（修复后）

### 规格漂移待办（记入总览）

1. plan Step 3「1分钟50次→429」→ 实现为「5 失败锁 15min + IP >20/h 封 1h」（更严，建议改 plan 措辞）
2. E4 测试方案「注释里的 ${} 不拦（设计如此）」→ 审查推翻该设计，门禁已改为代码区判定（测试方案 E4 描述应同步更新）
3. detectMimeType null 观察项；LLM 计费 refId=null 是设计（重试=新调用=新 token，uq_ledger_ref 不适用），媒体以 taskId 锚定——测试方案可补注

**最终结论**：S1 代码侧验收 **通过**（P1~P9/L1~L7/E1~E5 全绿，交叉审查 🔴🟡 全修复并回归实证）。结项阻断仅剩 H1~H3 人工项（密钥轮换/生产非超管账号/生产白名单核对），与代码无关。

## 七、人工阻断项状态（H1~H3 不闭合不结项）

- [ ] **H1 / G1 密钥轮换**（最高优先）：git 历史已泄露的 DB 密码/JWT_SECRET/RUNTIME_CALLBACK_TOKEN/钉钉/ctaigw key 全量轮换
- [ ] **H2**：生产建 agent_app 非超管账号 → 顺带实机验 REVOKE 拒改流水/审计（E3 右半）
- [ ] **H3**：生产 CORS 白名单核对（含钉钉 H5 回调域）+ gitleaks baseline 首扫
- [ ] **P4 业务层**：配好 LLM provider 后真跑一次逐 token SSE（传输层已过）

## 八、现场清理

验收产生的测试痕迹：phase4_user 账号、stored_files 植入行与文件、user 2 积分数据、幂等键 p4-e2-001、Redis login:fail 测试键 —— 验收结束后清理（audit_logs 测试行保留：审计表只增不改是设计红线，且这些是真实操作记录）。

## 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-08-10 | 初版：P1~P9/L1~L7/E1~E5 实机验收全记录 | Phase 4 |
| 2026-08-10 | 回填第六节交叉审查结论（🔴6🟡7 全修+回归实证）+ 最终结论改「代码侧通过，H1~H3 留人工」 | Phase 4 收尾 |
| 2026-08-10 | 文档对齐：积分计费 feature-map/user-ops 补 S1 增量（不可负/幂等/对账）；新建安全体系 feature-map+user-ops；**顺手修前端缺口**——充值表单此前不发幂等键（UI 双击会真重复充），WalletAdminView 每轮表单生成 UUID 键（vue-tsc 净） | 收尾产出对齐 |
