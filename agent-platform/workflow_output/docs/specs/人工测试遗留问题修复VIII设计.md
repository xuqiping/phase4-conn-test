# 规格 · 人工测试遗留问题修复VIII（2x 画布连线 2 项 + 12x 认证传输安全 2 项）

> SDD 特性级规格（Phase 1 产出）。实现须与本文件对齐；冲突时改实现或改本文档（注明原因）。
> 来源：[2x. 资产库和无限画布.md](../../人工测试问题/2x. 资产库和无限画布.md)「未解决」最后 2 项；[12x_认证系统.md](../../人工测试问题/12x_认证系统.md)「未解决」2 项（2026-08-29）。
> 用户拍板见 §3。前序：修复 III~VII 已收（VII 实测全过 `2b7b7ed0`~`07d63ae2`）。

## 1. 背景与代码现状事实（2026-08-29 探查，两路全量排查）

### 1a. 认证传输安全（12x 两问的排查答案）

**用户两问的直接回答**：① 登录/注册/重置全程密码以**明文 JSON** 提交（前端无哈希/加密），会不会泄露**取决于入口协议**——按[部署手册终.md](../deploy/部署手册终.md) Nginx 80→301→443 TLS 正规部署＝线缆全密文；当前实际入口 `http://117.72.25.74`（无域名降级）与本机 dev 均 http＝**账号密码、JWT、全部业务数据可被抓包**。② 全系统明文面清单见下表。

| # | 现状事实 | 位置 |
|---|---|---|
| ①部署 | 后端纯 HTTP 8080（无 ssl 块），TLS 完全依赖部署手册的 Nginx 边界；公网 IP 无域名走纯 http；安全体系 S1-S5 无传输层条目，HSTS 仅占位注释 | application.yml:2-5、SecurityConfig.java:59、部署手册终.md:206-249 |
| ②密码 | 前端明文 JSON POST `/auth/login`（无任何前端哈希/加密，全 src 无 JSEncrypt/RSA/SHA 密码处理）；后端 `@RequestBody LoginRequest` 明文收；注册/重置/注销同款 | PasswordLoginTab.vue:118、api/auth.ts:67-69、AuthController.java:31-35 |
| ③JWT | token 全链路 Bearer 头 + localStorage；baseURL 相对 `/api` 随站点协议；access 15min/refresh 7d 旋转 | request.ts:36-42、112-123、storage.ts:8-53 |
| ④WS | 两处 WS 握手 **token 走 URL query**（nginx access log 留痕），http 页面下还是明文 `ws://`；后端拦截器**优先取 query token**；`/ws/chat` permitAll（鉴权在拦截器） | chat.ts:577-583、projectGroup.ts:106-133、WebSocketAuthInterceptor.java:47-60 |
| ⑤邮件链接 | 重置/验证 token 走 URL query（30min/24h 单次有效，SecureRandom 32B）；通道配置**允许 http:// 前缀** | EmailService.java:103,127、AuthChannelSettingsUpdateRequest.java:23-24 |
| ⑥导出 | `GET /api/llm/providers/export` **回明文 API Key**（admin 权限内，@AuditLog 已有，无二次确认）；列表页不回显 key、AES 通道密钥 GET 已 mask、验证码接口不回码、签名 URL 强制 https+900s、DB/Redis/sidecar 全内网 | LlmController.java:113-121、AuthChannelSettingService.java:95-118 |
| ⑦日志 | ~~短信验证码 6 位以 INFO 写后端日志~~ **P3 实现期勘误**：该行 `code={}` 实为**阿里云应答状态码**（OK/isv.*），非验证码；全后端 grep 证实验证码明文从未进日志 | SmsService.java:413（排查误读） |

### 1b. 画布组与连线机制（2x 两项）

| # | 现状事实 | 位置 |
|---|---|---|
| ①组本质 | 组**不是节点**：`CanvasGroup{id,name,memberIds,color}` 独立数组+DOM 覆盖层（框体 `pointer-events:none` 仅头部可点），无 handle/无边能力；成员关系只存组侧；包围盒 rAF 派生（组成员被拖走盒变大，节点位置完全独立） | types/canvas.ts:69-76、CanvasBoard.vue:46-71、430-467 |
| ②组与边零关联 | 边只连节点（source/target 均节点 id）；组内节点 handle 与普通节点一致照常对外拉线；`onConnect`/`addEdge`/BFS 上游/一键整理均不读 groups | CanvasBoard.vue:802-818、1197-1209、CanvasView.vue:715-719 |
| ③handle | 全部节点走 `CanvasNodeBase`：固定 1 target（左）+1 source（右），无 id 无 connectable 定制；`<Handle>` 全工程仅此一处 | CanvasNodeBase.vue:43,59 |
| ④连接配置 | VueFlow 未传 `connectionMode/isValidConnection/autoConnect` → @vue-flow/core 1.48.2 默认 **loose + 20px handle 吸附 + connectOnClick 两段式点击**；`onConnect` 无校验（不查自环/重复/环） | CanvasBoard.vue:17-43、vue-flow-core.mjs:6190-6217 |
| ⑤本体松手丢弃 | 拖线松手在节点本体（非 handle）：库 20px 内无 handle 不发 connect；`onConnectEnd` 显式 `return` 放弃（注释「按 vue-flow 原语义放弃」）；落空白才走 quick-add 弹窗 | CanvasBoard.vue:838-860（放弃分支 852-854） |
| ⑥连线落库链 | onConnect：pushHistory('edge')→push 边（id `edge-{src}-{tgt}-{ts}`）→scheduleStoreReconcile→structure-changed→800ms 防抖 `PUT /api/canvas/{id}` | CanvasBoard.vue:802-818、CanvasView.vue:2180-2184、2431-2444 |
| ⑦边交互 | 边类型仅 `deletable`（贝塞尔+中点×删除）；选中边红粗辉光；无拖端点改连；程序化 addEdge 有自环/同向去重（拖拽 onConnect 无） | DeletableEdge.vue、CanvasBoard.vue:178-180、977-983、1197-1209 |

## 2. 外部调研结论（2026-08-29）

- **WS 鉴权出 URL 的业界通解**＝**首消息鉴权**（connect 时不带凭证，open 后第一条协议消息携带 token，服务端校验通过才进业务态，超时断开）。浏览器 WebSocket API 无法设自定义 header，首消息是唯一能避开 URL/日志的通道；Spring 侧用 HandshakeInterceptor 放行 + ChannelInterceptor 拦首条客户端消息实现。
- **vue-flow 组级端口**：官方父子组（parentNode/extent）会把组变节点、成员相对定位——与本项目「组=覆盖层+独立节点位置」模型冲突且迁移大。本项目走**自定义组端口+组边覆盖层**（组保持覆盖层，组边独立 SVG 渲染），改动收敛在 canvas 组件内。
- **RSA 前端加密密码**：防的是 http 降级入口下密码**字面量**泄露（密码复用撞库），不能替代 TLS（token/业务数据仍明文）；HTTPS 落地后可保留作纵深。属 CN 企业常见纵深手段。

## 3. 用户决策（2026-08-29 拍板）

| # | 问题 | 决策 |
|---|---|---|
| Q1 | 传输安全修复范围 | **暂无域名、后续会上**：HTTPS+RSA 密码加密**完整方案写进备案（§9），本轮不实现**；本轮只做纵深三件（VIII-3/4/5） |
| Q2 | 组边数据语义 | **广播+聚合**：外部→组端口＝广播给组内全部成员；组端口→外部＝全部成员输出聚合作为下游输入 |
| Q3 | 组建后成员单独对外拉线 | **并存保留**：历史成员级边不动照常工作，组端口是新增能力，两种边并存，老画布零迁移 |
| Q4 | 杂项范围 | WS token 出 URL ✓、短信验证码日志脱敏 ✓、导出接口加密码确认 ✓；邮件链接强制 https 前缀→**备案（§9），后续实现** |

## 4. 功能需求

### VIII-1 组整体对外拉线（组端口+组边，广播+聚合）P0

| 子项 | 需求 |
|---|---|
| ①组端口 | 组包围盒**右缘中点 source 端口 ●、左缘中点 target 端口 ●**（与组头同款 `pointer-events:auto`，其余框体保持穿透）；端口随包围盒 rAF 自动跟随；悬停高亮、拖拽中放大（视觉规格随实现，对齐现有 handle 样式） |
| ②组边模型 | 边记录 source/target 允许伪 id **`group:{groupId}`**（节点 id 不变造冲突）；组→组边允许（广播+聚合自然组合）。**组边存独立 `groupEdges` 集合，不进 VueFlow v-model edges**（伪 id 引用不存在节点，进 v-model 库渲染断裂）——getSnapshot 合并落库、applySnapshot 恢复时按端点拆分，快照 JSON 结构不变、**无 schema 变更、老快照零迁移** |
| ③连接手势（两向） | **组→外部**：从组 source 端口拖线（自定义 overlay 画临时贝塞尔，非 vue-flow 连接线），松手落节点（本体或 handle 均认，口径同 VIII-2）→ 建边 `{source:'group:{gid}', target:nodeId}`；落空白→quick-add 新节点并连 组→新节点（复用 quick-add 链，addEdge 传伪 id）**外部→组**：从节点 source handle 拖线（vue-flow 原生连接线），松手落点在组包围盒内且不在任何节点/handle 上→建边 `{source:nodeId, target:'group:{gid}'}`（`onConnectEnd` 先于 quick-add 判定坐标∈组包围盒） |
| ④建边路径 | 组边与普通边同链路：pushHistory('edge')→push edges→scheduleStoreReconcile→structure-changed→防抖落库；拖拽建边补**自环（组连自己）与同向重复**校验（对齐程序化 addEdge 口径） |
| ⑤渲染 | 组边在**独立 SVG 覆盖层**渲染（组层同栈）：组端=包围盒边缘锚点，节点端=该节点 handle 侧中点（节点 position+已测定尺寸计算）；跟随现有 rAF 重算（节点拖动/resize/组成员变动即重绘）；hover 辉光、选中红粗（selectedEdgeId 复用）；**中点 × 删除按钮**（同 DeletableEdge 口径），Delete 键删除选中组边与普通边一致 |
| ⑥数据流语义 | 新纯函数 `resolveEdgesForFlow(edges, groups) → 规范化节点级边集`：target=`group:g`→展开为 g 全部成员（**广播**）；source=`group:g`→展开为 g 全部成员（**聚合**）；跨组边递归展开（组→组=g1 全员×g2 全员）；去重。**接入点**：上游解析/selectedAncestors BFS（CanvasView.vue:715-719 一带）、上游面板、生成数据流取输入——全部改走该函数，BFS 结果天然含组成员 |
| ⑦级联 | 组解散/删除（含成员全删自解散）→ 该组全部组边**级联删**（同成员修剪 watch 扩展）；**对端节点删除**→该节点名下组边级联删（与普通边 removeNodes 级联同口径）；组成员增删不动组边（广播/聚合随 memberIds 动态生效） |
| ⑧不参与的链路 | dagre 一键整理**排除组边**（广播边=N 倍权重会炸布局；组内成员自有边拉齐）；Ctrl+C/V 复制粘贴与「创建副本」**不带出组边**（组不在选中集，伪 id 天然排除，显式兜底过滤）；@引用、两段式点击、Lightbox 不感知组边 |
| ⑨展示口径 | 上游面板按**展开后成员**列（组边视觉在画布，面板列成员产物，与现有上游链一致）；related 辉光：选中组边→组成员+对端节点辉光（applyVisualClasses 扩展） |

### VIII-2 连线松手在节点本体＝直连该节点 P0

- `onConnectEnd` 改造：落点 `closest('.vue-flow__node')` 命中且**库未发 connect**（非 handle 落点）→ 解析节点 id → 方向判定：source handle 起拖→`{source:startNodeId, target:落点节点}`；target handle 起拖→`{source:落点节点, target:startNodeId}` → 校验（非同节点＝防自环、同向去重）→ 复用 onConnect 建边链（单步历史+落库）。
- 落点＝起点自身节点本体→静默忽略（不连不弹）。落 handle→库 onConnect 原路径（防重复建边：connect 与 connectEnd 双发，分支须判库已处理）。
- 落空白：**先判组包围盒**（VIII-1 ③外部→组），不在任何组内→维持现状 quick-add 弹窗不变。
- 顺带收口：拖拽建边（onConnect）补自环/同向重复校验，与程序化 addEdge 同口径（现状拖拽可建自环边）。

### VIII-3 WS token 出 URL（首消息鉴权）P0

- **协议**：连接 `/ws/chat`、`/ws/events` 不带 query token；open 后**首条客户端消息 `{type:'auth', token:'<access token>'}`**；服务端校验 JWT→回 ack→进业务态；**5s 未认证或认证失败→close(4401)**；认证前收到的非 auth 业务消息→直接断开（不排队）。心跳/订阅在 ack 后启动。
- **后端**：握手拦截器只留 Origin 校验（不再读 query/header token）；新增 ChannelInterceptor 拦首条客户端消息完成鉴权+session 标记已认证；`/ws/chat` permitAll 保持（鉴权移入拦截器）。**query token 通道直接移除不留兼容**（前后端同仓同发版）。
- **前端**：chat.ts、projectGroup.ts 两处 store 同步改（URL 去 token、open→auth→ack→再订阅/resumePendingTasks）；**断线重连链路同改**（重连后重新走首消息鉴权）；401 类 token 过期在 auth 阶段失败即走刷新/跳登录口径。

### VIII-4 短信验证码日志脱敏 P1（P3 实现期改口径）

- **实现期勘误**：排查指认的 `SmsService.java:413 code={}` 实为阿里云**应答状态码**（OK/isv.*），非验证码；全后端 grep 证实验证码明文从未进日志。实际落地：`code=` 改名 **`respCode=`** 消歧义（防后人误当验证码），并补 **SmsService/EmailService 日志回归测试**（发码全链路断言日志无明文验证码），把「验证码永不进日志」锁进测试口径。dev 调试需要真码时看 Redis，不放宽日志。

### VIII-5 LLM 密钥导出加密码二次确认 P1

- **后端**：`GET /api/llm/providers/export` 改 **POST**（body `{password}`）——密码绝不能进 URL query（日志面）；校验当前登录用户密码（复用注销接口同款校验逻辑 AuthController.java:127-137 一带），错→`BusinessException`；@AuditLog 保留（记导出动作）。旧 GET 端点删除（同仓发版）。
- **前端**：设置页 LLM 供应商导出按钮→弹密码输入确认框（NModal+NInput type=password）→确认后 POST 下载 blob；调用点实现轮定位（api/llm 一带）。

## 5. 非功能需求

- **性能**：组边覆盖层随既有 rAF 重绘（≤50 组边增绘 <2ms/帧，纯 SVG 属性更新）；`resolveEdgesForFlow` 纯函数 500 边 ≤1ms；WS 首消息鉴权增 1 RTT（本地/内网 <5ms，无感）；零新增轮询。
- **安全**：WS token 不再进 nginx access log（消除凭证日志留痕）；导出密码走 body+校验（会话被劫持者无密码拿不走明文 key）；SMS 日志脱敏消除运维面读码；无新端点攻击面（export 由 GET 改 POST 属收紧）。
- **兼容/回滚**：画布快照无 schema 变更（伪 id 是普通字符串，老画布零迁移）；WS 协议变更须前后端同版发布（同仓同发，升级窗口内旧前端 WS 不可用——部署手册补一句）；全部纯代码 revert 即回现状。
- **依赖**：零新增 npm/maven 依赖。

## 6. 数据模型

- `CanvasEdge.source/target`：值域扩为 `nodeId | 'group:{groupId}'`，无新字段；快照 JSON 结构不变、无 Flyway 迁移。
- WS：无持久化变更（鉴权是会话态）。
- 导出：无表变更。

## 7. 测试策略

- **单测**：①`resolveEdgesForFlow`：广播展开（外部→组=N 条）、聚合展开（组→外部=N 条）、组→组递归（M×N+去重）、组解散后组边过滤、空组/超限组边界；②onConnectEnd 方向判定纯函数：source 起拖落本体/target 起拖落本体/落自身忽略/去重；③组边级联：删组/解散→边清、**删对端节点→其组边清**、增删成员边保留、快照合并-拆分往返一致；④拖拽建边自环/去重校验；⑤WS 首消息状态机（未认证消息丢弃、5s 超时断开、ack 后进业务）——后端 Spring WS 测试切片；⑥SMS/邮件日志脱敏断言（list appender 捕获）；⑦导出 POST 密码错误 401 路径。
- **组件测试**：CanvasBoard 组端口渲染位置（包围盒中点）、**组边不进 VueFlow v-model（传入 edges 全为节点 id）**、组边 SVG 重绘跟随、快照保存/恢复含组边（合并-拆分往返一致）、quick-add 落组内建组边、VII 回归（复制粘贴不带组边、✨ 排除组边）。
- **人工测试标记**（需真实浏览器手势）：①建组→从组右端口拖线到外部节点本体松手→组边成+落库+刷新恢复；②外部节点 handle 拖线落组包围盒空白→组边成；③组边聚合/广播语义：生成下游节点时上游输入含组全员产物；④选中组边×删除/Delete 删除；⑤解散组→组边消失；⑥拖线落节点本体直连（两方向）、落自身忽略；⑦WS：打开聊天/积分实时页→nginx access log 无 token；断网重连自动重鉴权；token 过期重连走刷新；⑧导出：正确密码出文件、错误密码报错、审计有记录；⑨老画布（无组）全功能回归。
- **回归**：修复VI 文件拖拽/图片粘贴、VII 复制粘贴/一键整理/两段式点击、上游面板/Lightbox、批量生成/一键关联（不读组边口径不变）、@引用、自动保存与撤回重做。

## 8. 边界与不做

- **本轮不实现**（§9 备案）：HTTPS 落地、RSA 密码加密、邮件链接 https 前缀强制——三项拍板进备案，后续轮做。
- 组边不做「指定出入口成员」模式（拍板=广播+聚合）；不禁止成员单独对外拉线（拍板=并存）；不做组嵌套组（现有组模型一节点仅属一组，组边组→组已是两层，够用）。
- 组边不参与 dagre 布局、不带出复制粘贴/创建副本（§4 ⑧口径）；组边无 hover 预览聚合面板（上游面板按成员列已覆盖）。
- WS 不留 query token 兼容期；不迁移 SSE（POST SseEmitter 不带凭证进 URL，无此问题）。
- 广播语义成本提示：外部→组边在生成时聚合 N 成员产物，积分/上下文消耗随组规模放大——本轮不加拦截（预估/余额闸门已全局兜底），文档口径写进 user-ops。

## 9. 备案（已设计未排期，后续轮实现）

| # | 项 | 方案要点 | 触发条件 |
|---|---|---|---|
| 备-1 | **HTTPS 落地**（治本） | 域名+CA 证书（过渡可自签/Cloudflare Tunnel）；nginx 模板部署手册终.md:206-246 已备；后端开 **HSTS**（SecurityConfig.java:59 占位转正）；新增 `app.security.require-https`（校验 X-Forwarded-Proto=http 即 403/301，生产开）；CORS 生产白名单禁 `*`（CorsConfig 空配置回退 `*` 收紧）；邮件 verifyUrl/resetUrl 前缀校验拒绝 http://（AuthChannelSettingsUpdateRequest.java:23-24 正则收紧） | 域名+证书到位 |
| 备-2 | **RSA 密码加密**（纵深） | 后端 RSA 密钥对（env 注入+支持轮换），暴露公钥接口；前端登录/注册/重置/注销/导出确认等全部密码入口公钥加密后提交，后端私钥解密走现有校验；防 http 降级入口密码字面量泄露与撞库；HTTPS 落地后可保留作纵深 | 随备-1 或先行 |
| 备-3 | 传输面复查 | 备-1/2 落地后按 §1a 表全项复扫（含 WS wss、导出、邮件链接），问题单 12x 两项勾销口径留档 | 备-1 验收时 |

## 10. 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-08-29 | 建立规格（VIII-1~5+备案 §9，Q1~Q4 拍板） | 2x 增补 2 项（组整体拉线/本体松手直连）+ 12x 增补 2 项（传输安全两问） |
| 2026-08-29 | P3 实现完成（A `4cd830eb` 12 文件 / B `cf1327df` 24 文件；前端全量 956 用例+tsc 0 错、后端全量 mvn test 过；双轮独立 review 共修 6 处：A 侧拖线组存活校验/blur 清理/落点不吸附坐标，B 侧 @ToString.Exclude 防密码落审计/isOpen 防超时竞态/verifyUserPassword 补 ACTIVE） | Chunk A/B 落地；四处实现超集偏差已记录：①B3 改口径（§1a⑦/§4 VIII-4 勘误：`code=` 实为阿里云应答码非验证码，改名 `respCode=`+回归锁定，验证码明文从未进日志）②WS 用 WebSocketHandlerDecorator 而非 ChannelInterceptor（裸 Spring WS 无 STOMP 概念，等价实现）③`/ws/events` 一并 permitAll（旧版该端点 HTTP 层实为拦截漏洞，鉴权移入首帧后净增强）④`verifyUserPassword` 抽公共方法（注销处内联无可复用点）。挂待人工验证（M1-M11/N1-N9） |

## 11. 术语表

| 术语 | 大白话 | 案例 |
|---|---|---|
| 组端口/组边 | 组框边缘的连线圆点；连到组（而非组里某个节点）的线 | 拖组右缘圆点到下游节点＝整组输出喂给它 |
| 广播+聚合 | 进组的一条线＝组里每个人都收到；出组的一条线＝组里每个人的产物都算数 | 外部→组：全员输入；组→外部：全员输出并集 |
| 伪 id | 用特殊前缀标记「这端是组不是节点」的字符串 | `group:g123` |
| 首消息鉴权 | WebSocket 连上后第一条消息先交令牌，验完才让说话 | URL 不再带 token，日志抓不到 |
| HSTS | 告诉浏览器「本站只准走 https」的响应头 | 中间人想把链接降级成 http 也降不动 |
| RSA 前端加密 | 浏览器用公钥把密码揉成密文再发，服务器私钥解开 | 抓包只抓到密文，拿不到密码原样 |
| 备案 | 设计好了、拍板要做、但本轮不写代码的方案 | §9 三项，域名到位后实现 |
| quick-add | 拖线落空白弹出的「选个节点类型」快捷建点弹窗 | 现有功能，本轮只加「落组内＝连组」分支 |
