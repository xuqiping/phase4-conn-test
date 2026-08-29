# 计划 · 人工测试遗留问题修复VIII（Chunk A 画布连线 / B 传输纵深 / C 文档收尾）

> Phase 2 产出，源自 [specs/人工测试遗留问题修复VIII设计.md](../specs/人工测试遗留问题修复VIII设计.md)（Q1~Q4 拍板已入规格 §3）。
> 硬闸门：本文件过审并获明确许可前不写实现码。

## 〇、总览与依赖序

```
A1 组边数据层(纯函数+状态) → A2 组端口渲染+组边SVG → A3 连接手势(两向+本体直连) → A4 数据流接入 → A5 A轮测试
B1 WS后端首消息鉴权 → B2 WS前端两store → B3 验证码日志脱敏 → B4 导出POST+密码确认 → B5 B轮测试
C 文档收尾(feature-map/user-ops/测试方案/问题单/部署手册注记；依赖 A5+B5 全绿)
```

A 与 B 完全并行无依赖；C 串行在最后。

---

## Chunk A · 画布：组整体拉线 + 本体松手直连（VIII-1/2，P0）

### A1 组边数据层（纯函数+组件状态）

- **目标**：组边可存、可存取快照、可级联、可展开为节点级边集。
- **动作**（伪代码）：
  ```
  types/canvas.ts: CanvasEdge.source/target 注释值域扩 'group:{groupId}'（无结构变更）
  新 utils/groupEdges.ts:
    isGroupEndpoint(id) -> id.startsWith('group:')
    groupIdOf(id) -> id.slice(6)
    resolveEdgesForFlow(edges, groups) -> 节点级边集:
      普通边直通; 组端点→组成员展开; 组→组=成员×成员; Set 去重
      （空组/已删组: 该边跳过——防悬挂广播）
    splitSnapshotEdges(allEdges) -> {flowEdges, groupEdges}   // 恢复用
    mergeSnapshotEdges(flow, group) -> allEdges               // 保存用
  CanvasBoard.vue:
    const groupEdges = ref<CanvasEdge[]>([])          // 不进 VueFlow v-model
    getSnapshot(): edges = merge(flowEdges, groupEdges)
    applySnapshot(): split 后 flow→v-model、group→groupEdges
    pushHistory 快照与 removeNodes/删组 watch 均走合并口径
    cascade: removeGroup() / 成员修剪watch / removeNodes() 里
      filter(e => 两端均不含该组伪id / 该节点id) 于 groupEdges
  ```
- **涉及文件**：types/canvas.ts、utils/groupEdges.ts(新)、components/canvas/CanvasBoard.vue、utils/groupEdges.test.ts(新)
- **依赖**：无
- **验证**：vitest groupEdges 全用例（展开广播/聚合/组×组/空组跳过/合并-拆分往返一致/级联删）；vue-tsc 0 错。

### A2 组端口渲染 + 组边 SVG 覆盖层

- **目标**：组包围盒左右缘出可拖端口；组边画出来且跟着动。
- **动作**（伪代码）：
  ```
  CanvasBoard.vue groups 覆盖层内:
    组框右缘中点 <button class=port-source> / 左缘中点 <port-target>
    （pointer-events:auto 同组头; aria-label「组输出/输入端口」; title 提示语义）
    端口位置随 scheduleGroupBounds 的 rAF 结果绑定
  新组件级 SVG 层（同栈, pointer-events:none, 边路径与×按钮 auto）:
    每条 groupEdge: path = bezier(组端=包围盒边缘锚点, 节点端=handle侧中点[节点position+测量尺寸])
    hover 辉光 / selected 红粗（selectedEdgeId 复用）/ 中点 ×（provide canvasRemoveEdge 复用→删组边+structure-changed）
    rAF 重算扩展: 组bounds 或 相关节点 position/size 变 → 重绘组边（脏标记, 非每帧全量）
  拖线: port pointerdown → window pointermove 画临时贝塞尔(overlay) → pointerup 进入 A3 分派
    （setPointerCapture; pointercancel 清理; 拖线中端口放大）
  ```
- **涉及文件**：CanvasBoard.vue（含内嵌 SVG 层，不拆新文件亦可；若拆则 components/canvas/GroupEdgeLayer.vue 新）
- **依赖**：A1
- **验证**：vitest 组件测试——端口渲染于包围盒中点；传入 v-model 的 edges 不含伪 id；组边 SVG 随成员拖动重绘；×删除落库（structure-changed 触发）。

### A3 连接手势（外部→组 / 组→外部 / 本体松手直连）

- **目标**：三个新手势各自成边，口径与校验统一。
- **动作**（伪代码）：
  ```
  CanvasBoard.vue onConnectEnd 改造（伪代码分派树）:
    tgt 命中 handle 且库已发 connect(justConnected=true) → return        // 防双建
    tgt 命中 .vue-flow__node 节点B:
      B == 起拖节点 → 静默 return                                        // 防自环
      起拖 source handle → 建边{source:start, target:B}
      起拖 target handle → 建边{source:B, target:start}
      → 校验(同向去重含组边) → 复用 onConnect 建边链(单步历史+落库)
    否则（落空白/穿透元素）:
      flowPos = screenToFlowCoordinate(event)
      若 flowPos ∈ 某组包围盒 且 起拖是 source handle → 建组边{source:start, target:'group:{gid}'}
      否则 → 现状 quick-add 不变
  A2 端口拖线 pointerup:
    落点命中节点(本体/handle 均认) → 组边{source:'group:{gid}', target:node}（source 端口）或反向（target 端口）
    落点 ∈ 另一组包围盒 → 组→组边
    落空白 → quick-add(position, 'group:{gid}')   // 新节点+组→新节点边, 复用现有链
  onConnect 补校验: 自环/同向重复(与程序化 addEdge 同口径)
  新纯函数 utils/groupEdges.ts: decideDropTarget(event, ctx) → 'handle'|'node:B'|'group:g'|'pane'
    （判定逻辑独立出来供单测, 组件只调它）
  ```
- **涉及文件**：CanvasBoard.vue、CanvasView.vue（onQuickAdd 支持伪 id source）、utils/groupEdges.ts、其 test
- **依赖**：A2
- **验证**：decideDropTarget 单测（本体两方向/落自身/落组空白/落组头/落 handle 双发防护）；组件测试建边+去重。

### A4 数据流接入与排除口径

- **目标**：上游解析/生成输入吃到广播+聚合；布局/复制粘贴/副本不带出组边。
- **动作**（伪代码）：
  ```
  CanvasView.vue selectedAncestors / 上游面板 / 生成取输入:
    读边处统一改 resolveEdgesForFlow(getEdges()+groupEdges, groups)
  related 辉光: applyVisualClasses 扩展——选中组边 → 组成员+对端节点加 related class
  utils/autoLayout.ts: dagre 输入边过滤 isGroupEndpoint（§4 ⑧口径）
  utils/nodeClone.ts / VII 剪贴板: 克隆/粘贴边过滤 isGroupEndpoint（显式兜底）
  ```
- **涉及文件**：CanvasView.vue、utils/autoLayout.ts、utils/nodeClone.ts、CanvasBoard.vue
- **依赖**：A3
- **验证**：单测（展开后 ancestors 含组全员；布局/克隆/粘贴边集不含伪 id）；vitest 全量回归（VII 用例不红）。

### A5 A 轮收口测试

- **动作**：vitest 全量 + vue-tsc；组件测试补「快照保存/恢复含组边往返」「删对端节点组边级联」「Ctrl+Z 撤回解散组→组边随快照恢复」。
- **验证**：全绿 + 新增用例清单入测试方案文档（C 块）。

---

## Chunk B · 传输纵深（VIII-3/4/5，P0+P1）

### B1 WS 后端首消息鉴权

- **目标**：token 出 URL；握手只验 Origin；业务前必须 auth。
- **动作**（伪代码）：
  ```
  WebSocketAuthInterceptor: 删 query/header 取 token 逻辑 → 仅 Origin 白名单校验
  新 WebSocketAuthChannelInterceptor(ChannelInterceptor):
    首条 CLIENT 消息 {type:'auth', token} → JWT 校验:
      通过 → session attrs 标记 authenticated + 回 {type:'auth_ok'} + 放行后续
      失败/非 auth 首消息/5s 超时(定时器) → close(4401)
    未认证期间其他业务消息 → close(4401)（不排队）
  WebSocketConfig: 注册拦截器; permitAll 保持
  ```
- **涉及文件**：chat/config/WebSocketAuthInterceptor.java、新 WebSocketAuthChannelInterceptor.java、WebSocketConfig.java
- **依赖**：无
- **验证**：Spring WS 测试切片——无 token 握手成功但业务被拒；正确 token 放行；错 token/超时 close 4401；Origin 非法拒。

### B2 WS 前端两 store

- **目标**：两处 WS 去 URL token、先 auth 后业务、断线重连重走鉴权。
- **动作**（伪代码）：
  ```
  chat.ts connectWS(577): url 去 ?token=; onopen → send({type:'auth', token})
    onmessage: 'auth_ok' → authenticated=true（此前 CHUNK 等帧丢弃/缓存至 auth 后）
    onclose(code=4401) → 单飞 refreshAccessToken 一次→重连; 再失败 redirectToLogin
    其余 close → 维持现有断线语义（chat 现状无自动重连则不新增，保持口径）
  projectGroup.ts connectEvents(125)/eventsUrl(106): 同款去 token + 首消息 auth
    onopen 补拉(wasReconnect loadWallet/loadGroups) 移到 auth_ok 之后
    scheduleEventsReconnect(114) 退避链保留; 4401 同 chat 处理
  ```
- **涉及文件**：stores/chat.ts、stores/projectGroup.ts
- **依赖**：B1
- **验证**：vitest store 测试（auth 前 send 排队、auth_ok 后放行、4401→刷新→重连、token 清空自终止）；本机起后端手测 2 页面 WS 连通。

### B3 验证码日志脱敏

- **动作**：`SmsService.java:413` `code={..}` → `code=******`；grep 后端 `log.*code` 全扫，EmailService 验证码/邮件通道日志同脱敏（排查⑦收口）。
- **涉及文件**：SmsService.java、EmailService.java（如有命中）
- **依赖**：无
- **验证**：单测 list-appender 断言日志无明文码（构造 6 位码上下文）。

### B4 LLM 密钥导出 POST + 密码确认

- **动作**（伪代码）：
  ```
  后端 LlmController: GET /llm/providers/export → POST, @RequestBody{password}
    校验当前用户密码（复用注销接口同款校验, AuthController.java:127-137 一带的 service 方法）
    错 → BusinessException；@AuditLog 保留, 失败尝试也落审计
  前端 api/llm.ts:140 exportProviders(password) → request.post('/llm/providers/export', {password}, {responseType:'blob'})
  ProviderManageTab.vue:440 导出按钮 → NModal 密码框(NInput type=password, 回车提交, 错误可重试)
  ```
- **涉及文件**：LlmController.java、（密码校验 service 定位实现轮）、api/llm.ts、components/settings/ProviderManageTab.vue
- **依赖**：无
- **验证**：后端切片测试（无密码 400/错密码拒/对密码出全量条目且含明文 key/审计两条）；前端组件测试弹窗流；手测下载文件内容完好。

### B5 B 轮收口测试

- **动作**：后端 mvn test 全量 + 前端 vitest 全量 + vue-tsc；nginx access.log 手动 grep `token=` 归零（本机/部署机各验一次）。
- **验证**：全绿 + access.log 验证记录入变更记录。

---

## Chunk C · 文档收尾（依赖 A5+B5）

- feature-map 增补：无限画布创作页（组端口/组边/本体直连）、认证系统增强（传输纵深+备案指引）。
- user-ops 增补：组拉线操作口径（含「广播/聚合放大消耗」提示口径）、WS 无感变化说明、导出需输密码。
- 测试方案文档：`docs/测试方案/人工测试遗留问题修复VIII测试方案.md`（新，按 A5/B5 用例+9 项人工测试标记展开 J/K/M/N 系列编号）。
- 问题单：2x 末 2 项、12x 末 2 项改挂「已实现，待人工验证（修复VIII）」+commit 号。
- 部署手册终.md：补一句「WS 鉴权协议变更须前后端同版发布」。

---

## 技术坑点预判

| # | 坑 | 规避 |
|---|---|---|
| 1 | 组边误进 VueFlow v-model（伪 id 引用不存在节点）→ 库渲染断裂/告警刷屏 | groupEdges 独立集合，v-model 前零伪 id（A1 拆分函数+组件测试断言） |
| 2 | 库对 handle 命中会连发 connect + connectEnd → 本体直连分支双建边 | justConnected 标志（现有）在 connectEnd 分支前置判断 |
| 3 | 组框体 pointer-events:none → 落点 target 是 pane，closest 判不出「落组内」 | 用 screenToFlowCoordinate 坐标 ∩ 组包围盒判定，不依赖事件 target |
| 4 | 组端口拖线不是 vue-flow 连接 → 无内置临时线/坐标换算 | overlay 自画临时贝塞尔 + pointer capture + pointercancel 兜底清理 |
| 5 | 每帧全量重算组边路径 → 拖动节点掉帧 | 沿用 scheduleGroupBounds 脏标记，bounds/节点未变不重绘；节点端坐标用缓存测量尺寸 |
| 6 | WS auth 前客户端帧（心跳/订阅）被服务端断连误伤 | 前端所有发送统一走「auth_ok 后」门闩（store 内 await authenticated promise） |
| 7 | WS close 4401 与网络断线混看 → 死循环刷新或重连风暴 | 4401 单飞刷新一次，失败才 redirectToLogin；其余 close 走原退避链 |
| 8 | 导出 POST 用 query 带密码（沿用 axios params 惯性）→ 密码进 nginx 日志 | body 传 {password}；代码评审断言 URL 无密码 |
| 9 | BCrypt 校验每次导出 ~100ms | admin 低频操作，接受；不缓存密码比对结果 |
| 10 | 快照撤回链漏组边 → 解散组 Ctrl+Z 组边丢 | pushHistory/undo 快照统一走 merge 口径（A1），测试含撤回恢复用例 |
| 11 | resolveEdgesForFlow 每次生成全量展开 O(边×成员) | 画布规模（≤50/组）可接受；纯函数不缓存，规模上来再议（落边界） |
| 12 | 回滚到旧版后，新版画布快照含组边 → 旧版 vue-flow 收伪 id 渲染异常 | 回滚预案：同 revert 数据清理脚本（删伪 id 边）或接受该画布手修；写进部署手册注记 |

## 安全检查清单（P3 逐项验）

- [ ] WS：URL 无 token；Origin 白名单仍在；未认证业务帧必拒；4401 不泄漏失败原因细节
- [ ] 导出：POST body 密码；`@RequirePermission("llm:config")` 不变；密码错拒且审计含失败；导出文件不进日志
- [ ] 日志：全后端 grep 验证码明文归零（SMS+邮件通道）
- [ ] 画布快照：后端对 edges 仍透传（无新解析面）；伪 id 仅前端语义
- [ ] 无新端点暴露面（export 属改造收紧）；无新依赖

## 功能联动点清单（只列正向，边界含反向/半选/批量）

| # | 触发 | 联动 | 边界 |
|---|---|---|---|
| L1 | 组成员增删 | 包围盒变→端口随动+组边重绘 | 组员全删→组自解散→组边级联删（既有 watch 扩展） |
| L2 | 解散/删除组 | 组边级联删+structure-changed 落库 | 选中态是该组边→清 selectedEdgeId；Ctrl+Z 撤回→组+组边随快照齐恢复 |
| L3 | 删除节点 | 普通边+组边双双级联 | 仅删其名下组边，不影响同组其他边 |
| L4 | 拖动/resize 成员或组员外对端节点 | 组边路径 rAF 重绘 | 脏标记，静止不重绘 |
| L5 | 选中组边 | 画布红粗+组成员及对端 related 辉光 | Esc/点空白清选中（既有链） |
| L6 | 节点 handle 拖线落点 | 落组空白→组边；落成员节点→成员边；落组外空白→quick-add（三路互斥） | 落组头（可点元素）按坐标归组边 |
| L7 | 组端口拖线 | 落节点→组边；落另组→组→组；落空白→quick-add+组→新节点 | 拖线取消（Esc/pointercancel）零残留 |
| L8 | WS 断线重连 | 重走 auth_ok → projectGroup 补拉 wallet/groups | token 清空→重连循环自终止（既有）；4401→单飞刷新一次 |
| L9 | 导出密码错 | 报错不下载、弹窗保留可重试 | 连续错不锁定（审计留痕兜底），口径同注销密码确认 |
| L10 | quick-add 建出的新节点（含组端口发起） | 新节点不入组、正常保存链 | 与 VII 粘贴「不入组」口径一致，互不影响 |

## 运维考量清单

| 类 | 结论 | 落字 |
|---|---|---|
| 可观测性 | **做** | WS 鉴权失败/超时 warn 日志（不含 token 内容）；导出成功+失败审计；/nginx access.log token= 归零验证步骤入 B5 |
| 配置开关 | **不做** | WS 协议同仓同版直接切，无灰度面；require-https 属备案项 |
| 可回滚 | **做预案** | 代码 revert 即回；组边数据回滚风险见坑 12（清理脚本注记入部署手册） |
| 限流/熔断 | **不做** | WS auth 5s 超时自保护；导出 admin 低频不加限流（既有接口级防护不变） |
| 运维入口 | **做** | 无新后台页；验证码真码调试口径=查 Redis（写 user-ops） |
| 告警阈值 | **后续再说** | 无监控接入面；WS 4401 频次告警留待安全体系 S5 检测引擎扩展 |
| 容量/性能 | **不做** | 组边数量不设上限（成员数 50 已限基数；spec §8 用户自担），画布快照既有大小约束兜底 |

## 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-08-29 | 建立 plan（A/B/C 三 chunk，A1-A5/B1-B5 步） | 修复VIII 规格过审进入 P2 |
| 2026-08-29 | P3 全 chunk 完成（A1-A5/B1-B5/C 全做）：A `4cd830eb`、B `cf1327df`；双轮独立 review 修 6 处（见规格变更记录）；B 偏差 4 条已回写规格勘误 | 用户拍板「A+B 全量开」；测试方案 M/N 系列与文档收尾（C）同日完成，挂待人工验证 |

## 术语表

| 术语 | 大白话 | 案例 |
|---|---|---|
| 首消息鉴权 | 连上 WebSocket 先发一条「我是谁」的令牌消息，验过才让发正事 | URL 不带 token，日志干净 |
| 门闩 | 前端发送函数里的「鉴权完成才放行」开关 | auth_ok 前发的消息先扣住 |
| 伪 id | `group:xxx` 这种「这端是组」的标记串 | 组边的 source/target 用 |
| 脏标记 | 「有变化才重算」的开关位，静止不耗性能 | 节点不动就不重画组边 |
| 4401 | WebSocket 关闭码，这里表示「鉴权失败」 | 前端见 4401 就去刷新令牌 |
| 单飞 | 并发时只放一个请求出去，其余等它结果 | 刷新 token 不并发轰炸 |
| 快照合并-拆分往返 | 保存时合并、加载时拆开，两边数量须一致 | 组边不丢不重 |
