# 安全体系 S2 · 开发进度 4 - Phase 4 运行验证

> Phase 4 产物：S2「防篡改与越权」实机验收记录。渗透 P1~P9 + 联动 L1~L7 + 边界 E1~E5 逐条实证 + 换模型双 agent 交叉审查。
> 测试方案基准：[../../docs/测试方案/安全体系S2测试方案.md](../../docs/测试方案/安全体系S2测试方案.md)

| 字段 | 内容 |
|---|---|
| **日期** | 2026-08-10 |
| **验证人** | Claude（k3）实机执行 + opus（资金面）/sonnet（非资金面）双 agent 交叉审查 |
| **环境** | 本机 dev：PG 16 / Redis 2.6.12 / backend 8080（`mvn spring-boot:run`，含 S2 全部 commit + V81 迁移 + `AUDIT_HMAC_KEY` 环境注入） |
| **结论** | 待交叉审查回填 |

---

## 一、渗透自查用例（P1~P9）

| # | 用例 | 实测证据 | 判定 |
|---|---|---|---|
| P1 | 审计篡改检测（SEC-FR-040/042） | UPDATE id=67 的 detail_json → verify-chain：`ok=false firstBrokenId=67 breakReason=record_hash 重算不匹配（行内容被篡改）`；落 `audit/chain_broken` FAIL 审计行（id=70）；ERROR 日志 `审计链断裂! brokenId=67`；恢复原值后链复绿（canonical 按内容不按字节） | ✅ |
| P2 | 审计删行检测 | DELETE id=67 → verify：`ok=false firstBrokenId=68 breakReason=prev_hash 衔接断裂（疑似删行/重排）`；`INSERT ... OVERRIDING SYSTEM VALUE` 还原后复绿 | ✅ |
| P3 | 外部锚定（SEC-FR-043） | 校验成功后 `logs/audit-anchor.log` 追加 `2026-08-10T03:01:20… lastRowId=69 recordHash=97effc…` | ✅ |
| P4 | 存量行兼容 | 库含 64 行 S2 前存量（record_hash NULL）：verify `ok=true legacyRows=64 chainedRows=5`，NULL 连续前缀不误报 | ✅ |
| P5 | 单点登录踢人（SEC-FR-008） | A 双端登录：旧 access → 401 `code=40104 账号已在别处登录`；旧 refresh → 同 40104；新 token 正常；`auth/session_kicked` 审计仅踢的瞬间 1 行（id=74） | ✅ |
| P6 | 单点登录降级 | 停 Redis：sid 比对本身降级放行正常，但**过滤器链上游的 S1 存量黑名单检查无降级** → 全部已认证请求 401「未认证」（发现 F5，见修复节）；恢复 Redis 后新登录恢复踢人（A2→40104、A3→200） | ⚠️→✅（恢复路径）/ 🔴 发现 F5 |
| P7 | 低余额闸门（SEC-FR-126） | B(余额99<阈值100) 预置 inflight=1 → 媒体提交：HTTP 429 `code=42902 余额不足，请等待当前任务完成`；槽位退回（仍=1，拒绝不占槽）；落 `billing/inflight_rejected` FAIL 审计 `{"balance":99.00,"inflight":1}` | ✅ |
| P8 | 闸门恢复 | 槽位清空 → 放行（越过闸门后到 provider 缺失 404，证明闸门放行）；余额充到 500 → inflight=5 也放行（非低余额不过闸） | ✅ |
| P9 | 槽位泄漏兜底 | 泄漏槽位键实测带 `TTL=1800s`（acquire 计数=1 时建）；Redis 侧 TTL 与后端进程生死无关，杀进程重启等价覆盖 | ✅ |

**P7/P8 执行注记**：dev 库无 LLM/VIDEO provider 配置，chat 路径 `findProvider` 在闸门之前（LlmGateway.java:59 vs :64），provider 缺失会先 404 遮住闸门——故闸门用「Redis 预置在途计数 + 媒体 submit（闸门在 provider 解析**之前**，MediaGenTaskService.java:101-105）」实证，语义等价于「连发 2 个任务的第 2 个」。

## 二、功能联动用例（L1~L7）

| # | 用例 | 实测 | 判定 |
|---|---|---|---|
| L1 | 旧 token（无 sid） | 用 dev JWT_SECRET 手工铸无 sid claim 的合法签名 token → 40104 强制重登（`sid==null→false`，SessionService.java:85-87） | ✅ |
| L2 | 单点登录开关 | admin PUT `/api/system/settings/auth {singleSessionEnabled:false}` → 同账号双端同时 200；重新打开 → 旧会话立即 40104。立即生效无需重启 | ✅ |
| L3 | 改计费阈值 | PUT `/api/system/settings/billing {threshold:50,maxInflight:2}` → B(99) 变非低余额，inflight=3 仍放行；改回 {100,2} → 第 2 个放行、第 3 个 42902。**实时 DB 读取，改完立即生效** | ✅ |
| L4 | embed/KB 索引不过闸 | 代码实证：`LlmGateway.embed`（:143-160）无 acquire 调用；`acquire(null)` 系统调用短路（单测 acquire_systemCallOrBillingDisabled_notGated 覆盖）。dev 无 EMBEDDING provider，端到端留人工 | ✅（代码+单测） |
| L5 | 端点覆盖扫描（SEC-FR-010） | 启动日志：`注解保护=162 已评审仅登录=70 待评审未覆盖=4` + 4 条 WARN 逐端点列出；Prometheus `security_endpoints_unguarded 4.0`。4 端点处置见修复节 | ✅（机制）/ 🔴 抓出真问题 |
| L6 | 审计链写入并发 | 20 并发登录（每次写 login±session_kicked 审计）→ 链校验 `ok=true totalRows=127 chainedRows=63`，advisory lock 串行化无分叉 | ✅ |
| L7 | 媒体任务失败释放 | 植 PENDING 任务（无 provider 必失败）+ inflight=1 → worker 5s poll 认领 → FAILED 终态 → finally release → `inflight:u:4` 键删除 | ✅ |

**L5 抓出的 4 个未覆盖端点**（B1 设计目标即抓此类，机制验证有效）：

| 端点 | 实况 | 处置 |
|---|---|---|
| PUT /api/agents/{id}/permissions | 无注解，服务层 `assertManage`（admin 或创建者）有 ownership 兜底，不可越权；但 GET 兄弟有 `agent:read`、KB 绑定 PUT 兄弟有 `agent:update`，唯独它缺 | 待审查结论补齐（补 `agent:update` 对齐兄弟） |
| GET /api/departments | 无注解，只读组织架构引用数据 | 待审查结论（倾向白名单仅登录） |
| GET /api/billing/me/wallet、/me/usage | 无注解，查本人数据 | 待审查结论（倾向白名单仅登录） |

## 三、边界/异常场景（E1~E5）

| # | 场景 | 实测 | 判定 |
|---|---|---|---|
| E1 | AUDIT_HMAC_KEY 缺失启动 | 8081 端口实例不带 key 启动 → `IllegalStateException: AUDIT_HMAC_KEY 未配置…禁止使用空密钥启动` → 进程退出，端口从未监听（fail-fast 同 JWT_SECRET 范式） | ✅ |
| E2 | system_settings 直改非数字 | `billing.low-balance.threshold='abc'` → 闸门回退默认 100 正常拒绝（42902）；admin GET 设置页同路径回退 100 不炸；API 改回正常 | ✅ |
| E3 | agent_app 对 payment_orders/idempotency_keys DELETE | 本地 postgres 超管不下放权限**无法实证**（同 S1 E3）；V81 DO 块判 pg_roles 存在才 REVOKE，迁移已干净应用 | ⚠️ 留 H2 生产人工验 |
| E4 | 新端点不加注解 | L5 实证同机制：WARN + gauge+1；B2 越权 IT（403 显式/500 绝不可）Phase 3 已全绿 | ✅ |
| E5 | logout 后旧 token 复用 | logout 200 → 复用 → 401「未认证」（黑名单拦截）+ `session:user:3` 键已删，双保险 | ✅ |

**E5 排障注记**：logout 请求本身也会被 sid 比对拦截（已踢的旧 token logout → 40104 而非执行登出），语义可接受（已踢=已登出效果）；另 Redis 数据丢失 → 全员 40104 强制重登一次（fail-closed 于缺键，F6 知悉项）。

## 四、性能实测（本机 dev，与 S1 基线对比）

| 接口 | 实测 | S1 基线 | 备注 |
|---|---|---|---|
| POST /api/auth/login | ~140-170ms（p50≈150ms） | ~155ms | bcrypt 主导；新增 session 写 + HMAC 链 + advisory lock **无可测开销** |
| GET /api/auth/me（认证请求） | ~13-37ms | — | 新增 sid 比对 1 次 Redis GET，亚毫秒 |
| GET /api/audit/logs/verify-chain | ~27ms（127 行全量扫+逐行 HMAC 重算） | — | 行数线性，万行级~2s 内可接受（每日定时+手动） |
| POST /api/media/video（闸门拒绝路径） | ~17-23ms | — | 含 2 次余额查 + 1 次 settings 读 + INCR/DECR |

S2 无独立 performance_goals（安全加固类），以上为回归基线存档：安全链路对正常请求无可见时延影响。

## 五、交叉审查结论（双 agent，换模型）

按 Phase4 高危功能换模型要求：**opus 审资金面**（L7 闸门/计费设置/审计链资金面），**sonnet 审非资金面**（A8 单点登录/B1 扫描/降级范式/审计链非资金面 + F5/F6 复核 + L5 四端点处置建议）。两边均按 8 维清单 + 三个最怀疑位置 + spec 漂移清单输出。

### opus（资金面）— 0🔴 2🟡 4🟢

| # | 级别 | 发现 | 处置 |
|---|---|---|---|
| O1 | 🟡 | MediaGenTaskService.submit：acquire 占槽后 provider 解析/validate/insert 等 6 个可抛点无补偿释放 → 低余额用户一次失败提交自锁 30min | **已修**：拆 submit/doSubmit，try/catch 补偿 release |
| O2 | 🟡 | LlmGateway.chatStream 组装期 acquire 双泄漏路径：Flux 未被订阅（永不执行 doFinally）、provider 组装抛异常 | **已修**：acquire 移入 Flux.defer 订阅期，组装异常释放，doFinally 配对 |
| O3 | 🟢 | InflightGateService.acquire 先 INCR 后读阈值设置 → settings 查询抖动能留下无配对 INCR | **已修**：两次 getLong 移到 INCR 之前（独立 try，失败降级放行不动计数） |
| O4 | 🟢 | release 内 `!walletService.isEnabled()` 判断 → 运行期关计费后 release 不执行，占槽泄漏 | **已修**：删除该判断（submit 计数后运行期关计费，release 仍须配对） |
| O5 | 🟢 | LlmGateway chat 路径余额查两次（requireAffordable + acquire 内 getBalance） | **已修**：requireAffordable 改返回 BigDecimal，acquire(userId, balance) 复用 |
| O6 | 🟢 | plan 写 verify-chain 在 /api/system/audit/verify-chain，实现在 /api/audit/logs/verify-chain | 实现更合理（落在 AuditLogController + system:audit:read），**plan 已回改** |

### sonnet（非资金面）— 1🔴 3🟡 + 建议

| # | 级别 | 发现 | 处置 |
|---|---|---|---|
| F1 | 🔴 | AuthService.isTokenBlacklisted 裸调 redisTemplate.hasKey（S1 存量代码），排在 SessionService 降级之前 → Redis 宕机全员 401，违反 plan「停 Redis 全链可请求」验证标准 | **已修**：try/catch 降级放行+WARN；P6 复验实证（停 Redis 连续 200 + 登录 200 + 双 WARN 日志） |
| F2 | 🟡 | logout 盲删 session:user:{userId} 键 → 被踢旧会话在降级窗口/开关关闭时调 logout 可踢飞新会话（logout-bomb） | **已修**：clearSession(userId, sid) 比对 sid 相等才删（GET-then-DEL 竞态良性已注释）；E5 复验实证 |
| F3 | 🟡 | SessionService.isCurrent 开关读取在 try 外 → system_settings 查询抖动 → 全员 40104 | **已修**：开关读取移入独立 try（异常→WARN 降级放行） |
| F4 | 🟡 | SecurityEndpointRegistry 死条目 `/api/billing/wallet/**`（端点已不存在） | **已修**：删除；顺手补登 `/api/billing/me/**`、`/api/departments` 两条仅登录白名单，javadoc 澄清评估面 |
| F5' | 建议 | D4 锚定只写不读（启动不校验 anchor 与 DB 链根一致性） | 暂缓：S3 候选加固，记入待办 |
| F6' | 建议 | verify-chain 全表加载内存 | 暂缓：plan 已认 MVP，万行级再改游标 |
| F8' | 建议 | KB IT 依赖 seed 数据 | 记录：IT 环境前置条件 |
| F9' | 建议 | security_endpoints_unguarded gauge 重复计数 cosmetic | 暂缓：仅展示层 |

另：sonnet 复核 P6 暴露的 401 根因定级 🔴 成立（可用性事故级，且恰是 plan 明示验证项）。

## 六、修复与回归

**修复清单**（全部完成，对应上表「已修」行）：

1. `AuthService`：isTokenBlacklisted 降级放行+WARN；refreshToken 复用该方法；logout 黑名单写入包 try/catch（降级：登出继续，token 残留至自然过期）+ 提取 logoutSid 改调 clearSession(userId, sid)
2. `SessionService`：isCurrent 开关读取移入独立 try 降级；clearSession 改比对 sid 删除；newSession WARN 文案补「Redis 恢复后须重新登录一次」
3. `InflightGateService`：acquire 拆双参重载（余额复用）；阈值读取移到 INCR 前（失败降级不动计数）；release 删计费开关判断
4. `PointsWalletService`：requireAffordable 返回 BigDecimal（余额复用省一次查库）
5. `LlmGateway`：chat/chatStream 余额复用；chatStream acquire 移入 Flux.defer + 组装异常释放 + doFinally 配对
6. `MediaGenTaskService`：submit 拆 submit/doSubmit + 异常补偿 release
7. `SecurityEndpointRegistry`：删死条目 + 补 2 条白名单 + javadoc 红线（scanner 只认识注解与白名单，SecurityConfig URL 规则保护的端点会被误判）
8. `AgentController.saveAgentPermissions`：补 `@RequirePermission("agent:update")`（L5 处置：agent 全线写操作管理向，user 角色无此权限不破坏普通用户）

**回归**：

| 层 | 结果 |
|---|---|
| 受影响单测（AuthService/SessionService/InflightGate） | 44 测绿（新增 9 测：黑名单降级/logout Redis 宕/开关读失败/clearSession 三态/release 运行期关计费/settings 读失败/余额复用） |
| 全量后端套件 | **1260 测 0 失败 0 错误**（S2 开发完成时 1252 → +8） |
| 实机复验（新 key 重启后） | **P5** 旧 access+refresh 双 40104 ✅；**P6** 停 Redis 认证×3 + 登录全 200、WARN 双降级点日志实证 ✅；**P7** 42902 + inflight 退回 1 ✅；**E5** logout→access 401/refresh 40102/会话键 login 后存在 logout 后删除 ✅；**verify-chain** 新链 18 行全绿（chainedRows=18, firstBrokenId=null）✅ |
| B1 启动扫描 | 注解保护=163 已评审仅登录=73 **待评审未覆盖=0** ✅ |

**环境事件记录**：旧 AUDIT_HMAC_KEY 只存在于已终止 shell 未持久化 → 本次重启示新 key（已写入未入库的 local-dev-env.ps1），audit_logs TRUNCATE 从 GENESIS 重建（dev 测试数据，P1~P9 证据已在上文存档）；Redis 强杀重启丢 session 键属预期（P8 恢复路径不受影响）。

## 七、人工阻断项状态（承接 S1 H1~H3，S2 新增 H1'~H3'）

- [ ] **S1-H1/G1 密钥轮换**（最高优先，S2 的 AUDIT_HMAC_KEY 入同批清单）
- [ ] **S1-H2**：生产 agent_app 非超管账号 → 顺带验 REVOKE（S1-E3 右半 + S2-E3）
- [ ] **S1-H3**：生产 CORS 白名单核对 + gitleaks baseline
- [ ] **S2-H1'**：AUDIT_HMAC_KEY 生产注入（`openssl rand -base64 48`，轮换=旧链无法续验需先锚定归档）
- [ ] **S2-H2'**：单点登录上线公告（全部旧 token 强制重登一次，与 G1 公告合并）
- [ ] **S2-H3'**：链校验告警通道接入（audit/chain_broken 目前 ERROR 日志+审计行）
- [ ] **P4 业务层**（S1 遗留）：配好 LLM provider 后真跑一次逐 token SSE

## 八、现场清理

验收测试痕迹：s2usera/s2userb 账号及其积分（99/1000，密码本次复验已重置为 admin123）、media_gen_tasks id=1（FAILED）、media:gen 权限授予 user 角色（评估是否保留——若正式环境 user 角色本应有视频权限则保留）、Redis 测试键（inflight:u:* 已清、login:fail 无）。~~audit_logs 测试行保留~~ → **已随 key 轮换 TRUNCATE**（见六节环境事件），当前表内为复验产生的新链 18 行。

## 九、S2 验证环境快速启动速查表

| 步骤 | 命令/要点 |
|---|---|
| ① PG/Redis | PG 服务常驻；Redis：`cd /d/IT/redis && ./redis-server.exe`（无 conf 文件，默认 6379；强杀丢键属预期） |
| ② 环境变量 | 见 `agent-platform/local-dev-env.ps1`（未入库）：JAVA_HOME=jdk-17 / JWT_SECRET / DB_PASSWORD / RUNTIME_CALLBACK_TOKEN / **AUDIT_HMAC_KEY**（缺则启动 fail-fast） |
| ③ backend | `agent-platform/backend`：`mvn spring-boot:run`（须 JAVA_HOME 指向 jdk-17；脱离 shell 常驻，TaskStop 会连坐） |
| ④ 就绪探针 | `curl -X POST localhost:8080/api/auth/login` 返回 401/400 即就绪；启动日志应有 `权限覆盖扫描(B1): ... 待评审未覆盖=0` |
| ⑤ 链校验 | admin token → `GET /api/audit/logs/verify-chain`（ok:true 即链绿）；锚定文件 `logs/audit-anchor.log` |
| ⑥ 踢人复现 | 同账号登录两次 → 旧 token 请求得 `40104`；开关 `auth.single_session.enabled`（system_settings，实时生效） |
| ⑦ 闸门复现 | 用户余额 < `billing.low-balance.threshold`（默认100）时 `redis-cli SET inflight:u:{userId} 1` → media/chat 提交得 `42902`；恢复 `DEL inflight:u:*` |
| ⑧ 降级复现 | 杀 Redis 进程 → 认证/登录仍 200 + WARN「降级放行」日志；恢复后原 token 继续可用 |
| ⑨ 全量回归 | backend 目录 `mvn test`（JAVA_HOME=jdk-17），基线 1260 测绿 |
| ⚠ key 轮换 | AUDIT_HMAC_KEY 轮换=旧链无法续验，轮换前先锚定归档（H1' 人工项） |

## 十、总结论
**S2 防篡改与越权 Phase4 运行验证：通过。** P1~P9 / L1~L7 / E1~E5（E3 留 H2 生产账号）全绿；双 agent 换模型交叉审查 1🔴+5🟡 全部修复并实证回归（全量 1260 测绿 + 实机复验 P5/P6/P7/E5/verify-chain）；低成本建议 3 项顺手修复，暂缓 4 项记入待办（锚定只写不读→S3、verify 全表内存→万行级、KB IT seed 依赖记录、gauge 计数 cosmetic）。规格漂移已全部回写 plan（存量不回填/开关名/verify-chain 路径/流式 defer 语义/TTL fail-open 近似/+1 settings SELECT）。遗留人工阻断 H1~H3 + H1'~H3' 见七节，均不阻塞开发线推进 S3。
