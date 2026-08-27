# 人工测试遗留问题修复V · 实现计划

> 规格：[specs/人工测试遗留问题修复V设计.md](../specs/人工测试遗留问题修复V设计.md)。2026-08-27。
> 上游：修复IV 已完结（P4 冒烟+回归修复全绿）。本轮=2x 未解决 2 项 + 17x 未解决 1 项，4 项用户决策已锁（规格 §2）。
> 本轮仅 3 chunk 两域，**单册不分册**（III/IV 分册因 6-7 chunk 跨域；V 规模小，master 内联步骤即可）。

## chunk 索引与依赖

| chunk | 内容 | 依赖 |
|---|---|---|
| A | 画布两项：上游视频首帧（2x-1）+ 副本重进恢复（2x-2） | — |
| B | 组池流水筛选+CSV 导出（17x-1）：后端扩参 → 导出端点 → 前端筛选行 | — |
| C | 文档收尾：2x/17x 勾销 + feature-map/user-ops「修复V 增补」+ 测试方案 | A、B |

A/B 不同文件域可并行，实施序建议 **A→B→C** 串行（B 前后端同 chunk 内先落后端；C 收尾统一勾销）。每 chunk 绿即 commit（显式 add，防 WIP 混提）。

---

## Chunk A · 画布两项（纯前端）

### A1 上游视频首帧（2x-1）

- **目标**：上游面板视频卡缩略图显视频首帧，替「视」字占位。
- **动作**（伪代码）：
  ```
  PropertyPanel.vue 上游卡缩略图位（:40-49 现状 img v-if / span 占位）:
    upThumb 为 falsy 且 node.type==='video' 且 upMediaSrc(u) 非空
      → 渲染 <video :src="upMediaSrc(u)" preload="metadata" muted playsinline />
    （video 无 controls、pointer-events:none——点击冒泡到外层按钮开 Lightbox，手势不变）
  CSS: .prop-panel__up-video { width/height 100%, object-fit cover }（与 img 同规格）
  upThumbSrc（:964-969）不动——coverPreviewUrl 语义保持导演台专用
  ```
- **文件**：PropertyPanel.vue（1 个）
- **依赖**：无
- **验证**：vitest PropertyPanel——video 上游项渲染出 `<video preload="metadata">`；无媒体源仍「视」占位；image 上游仍 `<img>`；单击 Lightbox 开（pointer-events 回归）。

### A2 副本重进恢复（2x-2）

- **目标**：视频副本（taskId 已被 clone 清空）重进画布按 fileId 恢复预览；任务已删的存量视频节点同修。
- **动作**（伪代码）：
  ```
  CanvasView.vue hydrateVideoPreviews（:2274）加 fileId 兜底腿:
    现状: 仅 taskId+mediaStatus==='SUCCEEDED' 分支
    新增: type==='video' && 无 taskId（或 taskId 任务查不到）&& data.fileId 非空
      → previewUrl = await fetchCanvasPreview(fileId)  // 既有 API（:391 import）
      → updateNodeData(id, { previewUrl, status: 'success' })
      → 失败 catch 静默（口径同 hydratePreviews :2268，不阻断加载）
    taskId 腿优先（信息全：能补 mediaStatus/resultFileId），兜底腿不与并行跑
  ```
- **文件**：CanvasView.vue（1 个）
- **依赖**：无（与 A1 不同文件）
- **验证**：Playwright 全链：视频节点→创建副本（产物即显）→退出画布→重进→副本预览在、status success；图片副本同链路验证（现状 fileId 链理论可恢复，实测确认，若丢另查快照序列化）；vitest nodeClone 既有用例不回归（产物四件保留锁）。

**A 坑点**：
- `<video preload="metadata">` 首帧依赖浏览器拉 metadata range 请求——`fetchCanvasPreview` 返回的 objectURL 是整段本地 blob，无网络 range 问题；但若上游 src 走直链需确认 CORS/缓存（实测档）。
- 兜底腿勿对**生成中/失败**节点误置 success：只在 `data.previewUrl` 空且 `fileId` 非空时跑；`status==='running'` 节点跳过（resumePendingTasks 管）。
- hydrateVideoPreviews 被两处调用（:2252 加载链 / :2468 版本回滚链）——兜底腿两处自然同生效，勿只改一处调用点。

**A 完成标准**：vitest（PropertyPanel/nodeClone）全绿 + vue-tsc 0 → commit `fix(canvas): 修复V A 上游视频首帧直出+副本重进fileId兜底恢复（2x#1/2）`。（Playwright A1/A2 手动档**后置 P4 统一冒烟**——IV 轮同节奏，避免 P3 重复搭实测环境）

---

## Chunk B · 组池流水筛选+导出（17x-1）

### B1 后端：overview 扩筛选参数

- **目标**：流水可按 keyword/type/操作人/时间筛（组长/MANAGER/admin；MEMBER 忽略筛选维持 self）。
- **动作**（伪代码）：
  ```
  ProjectGroupController.overview（:367-374）:
    加 @RequestParam(required=false) keyword/type/actorUserId/from/to
    → queryService.overview(id, uid, admin, keyword, type, actorUserId, parseTime(from), parseTime(to), page, size)
    （parseTime 复用 outputs 同款 :396）
  ProjectGroupQueryService.overview（:75）:
    type 非空 → 白名单校验（14 枚举 Set），非法 BusinessException(400)
    managerView 时组装筛选（MEMBER 路径忽略——现状 :94-96 self 分支保持在前）:
      actorUserId 非空 → lw.eq(actorUserId)
      from/to 非空 → lw.ge/le(createdAt)
      keyword 非空 →
        lw.and(w -> w.like(remark, esc)
          .or().inSql(actorUserId, "SELECT id FROM users WHERE username LIKE ... OR name LIKE ... OR remark LIKE ..."))
        （inSql 子查询三字段各 ESCAPE '\\'，escapeLikeKeyword 先转义——AssetMemberService:233 / UserPointsBalanceMapper:65 先例；
         users 量级小全扫，E 轮同口径）
  ```
- **文件**：ProjectGroupController.java、ProjectGroupQueryService.java（2 个）
- **依赖**：无
- **验证**：单测 ProjectGroupQueryServiceTest——type 非法 400 / MEMBER 传筛选参数被忽略（仍 self）/ 筛选条件进 wrapper（mock mapper 捕获）；IT 见 B3 后。

### B2 后端：CSV 导出端点

- **目标**：`GET /{id}/ledger/export` 同筛选参数导出全量 CSV（上限 5 万）。
- **动作**（伪代码）：
  ```
  ProjectGroupQueryService 新方法 exportLedger(groupId, uid, admin, 筛选...):
    权限: MEMBER（非组长/管理/admin）→ BusinessException(403)（比 overview 更紧——决策 4）
    复用 B1 wrapper 组装（抽私有方法 buildLedgerFilter，overview/export 共用，防双处漂移）
    selectList(wrapper.orderByDesc(id).last("LIMIT 50001"))
    rows.size > 50000 → 取前 5 万 + truncated=true
    CSV 拼装（StringBuffer → byte[]，UTF-8 + 头三字节 BOM EF BB BF）:
      表头: 时间,类型,操作人,变动积分,变动后组池余额,关联,备注
      类型列: 后端 TYPE_LABEL map（14 枚举→中文，与前端 LEDGER_TYPE :992 对齐）
      操作人列: 账号（姓名）·备注 —— names 批量补齐复用 usernameMap + userMapper 查 name/remark
      数值列: deltaPoints/balanceAfter 去 尾零（stripTrailingZeros().toPlainString()）
      单元格含 , " \n → RFC4180 转义（双引号包裹+内部双引号翻倍）
      truncated → 尾行追加 "# 截断：共命中 {total} 行，仅导出前 50000 行"
  Controller:
    @GetMapping("/{id}/ledger/export") @RequirePermission("project-group:manage")
    @AuditLog(module="project-group", action="ledger_export", targetType="project_group")
    → ResponseEntity<byte[]>: Content-Type text/csv; charset=UTF-8
      Content-Disposition attachment; filename=group-{id}-ledger-{ts}.csv（价表 /pricing/export 先例）
    log.info 导出行数/gid/操作人（运维）
  EXPORT_ROW_LIMIT 常量一处（50000，改值一处）
  ```
- **文件**：ProjectGroupQueryService.java、ProjectGroupController.java（2 个，同 B1）
- **依赖**：B1
- **验证**：单测——CSV 拼 BOM/转义（逗号引号换行单元格）/截断注记/type 标签映射全覆盖 14 种/MEMBER 403；真 PG IT（B3 后统一跑）。

### B3 前端：流水筛选行+导出按钮 + 真 PG IT

- **目标**：流水 tab 管理视角加筛选行；导出按钮带当前筛选下载。
- **动作**（伪代码）：
  ```
  ProjectGroupsView.vue 流水 tab（:169-176 NTabPane name="ledger"）:
    v-if="canManage" 筛选行（复用产出 tab .pg-outputs__filters 样式 :246/:1587）:
      NInput 关键词（回车/清空触发）+ NSelect 类型（options=LEDGER_TYPE 14 项转 label）
      + NSelect 操作人（memberFilterOptions 复用 :964）+ NDatePicker range（产出 tab :266 同款）
      + NButton「导出 CSV」
    状态: ledgerKeyword/ledgerType/ledgerActor/ledgerRange refs
    loadOverview（:867-873）: overview 调用带筛选参数（api 层 overview 签名扩可选参数）
    筛选变更 → ledgerPage=1 → loadOverview()（分页重置联动）
    导出: window.open / a[href] = `/api/project-groups/{id}/ledger/export?{同参数序列化}`
      （带 token 走 blob: request.get blob → objectURL → a.click，与既有下载先例一致；403 报错话术）
  api/projectGroup.ts: overview 签名扩参 + exportLedger 函数
  成员视角: 筛选行与导出按钮均不渲染（tab 现状零变化——L3 联动锁）
  ```
- **文件**：ProjectGroupsView.vue、api/projectGroup.ts（2 个）
- **依赖**：B1/B2 后端就绪
- **验证**：vue-tsc 0；vitest（筛选行渲染 canManage 分流/筛选变更重置分页）；**真 PG IT**（新 ProjectGroupLedgerFilterIT，@Tag integration @ActiveProfiles it，参照 AssetMediaBridgeExistsIT 脚手架）：造 ALLOCATE/CONSUME/BACKSTOP+备注+两 actor 流水 → keyword（流水备注/操作人名）命中与排除 / type 筛选 / actor 筛选 / 时间范围 / MEMBER overview 忽略筛选仍 self / MEMBER export 403 / 导出 CSV BOM+列内容+转义断言 / 超 5 万截断。
  > **P3 偏离注记（2026-08-27）**：截断用例原计划反射改 EXPORT_ROW_LIMIT 造超限——实施发现 `static final int` 被 javac **常量内联**进调用点，反射改静态字段对已编译引用无效 → 改回真造 50001 行（jdbc 批插 ~3s 可接受），断言注记行真值。IT 6/6 绿。

**B 坑点**：
- `.apply`/`inSql` 里 LIKE 值必须走 `#{}` 参数化——inSql 子查询字符串是**静态 SQL 框架**，三处 LIKE 值全部参数化拼接（照 UserPointsBalanceMapper XML 写法，勿字符串拼用户输入）。
- `LIMIT 50001` 用 `.last()`——与既有 `.last("LIMIT 1")` 先例同款；`.last` 后不得再跟 orderBy（wrapper 先 orderBy 后 last，顺序别反）。
- 导出走 axios blob 下载时**响应拦截器**对非 JSON Content-Type 直放（request.ts 既有 blob 口径，确认勿被 R<T> 解析吞）。
- CSV 时间列统一 `yyyy-MM-dd HH:mm:ss`（系统时区，与前端展示口径一致）；换行统一 `\r\n`（Excel 友好）。
- usernameMap 只补 username——导出还需 name/remark：一次性 `userMapper.selectBatchIds` 补全，勿循环单查（N+1）。
- **mgr 视角判定（overview :79-88 的组长/管理/admin 三查）同样抽私有方法共用**——export 的 403 判定与 overview 的裁剪判定必须同源，复制一份必漂移（III 坑 7「裁剪单点」同款纪律）。
- 前端 LEDGER_TYPE 与后端 TYPE_LABEL 两份映射**本轮不合并**（分属两端无共享层），在两处注释互指对方，改动需同步——记入开发进度。

**B 完成标准**：`mvn test` 全量不红 + 新 IT 绿 + vue-tsc 0 + vitest 绿 → commit `feat(project-group): 修复V B 组池流水筛选+CSV导出（17x#1）`。

---

## Chunk C · 文档收尾勾销（参照 IV F）

- **动作**：
  ```
  2x. 资产库和无限画布.md: 未解决 2 项 → 已解决（修复V §3，commit SHA）
  17x_项目组.md: 组池流水筛选导出 → 已解决（修复V §4）
  feature-map/user-ops 增补「2026-08-27 增补（修复V）」节:
    无限画布创作页 + 项目资产库（2x 两项——上游首帧/副本重进）
    项目组与积分划拨（17x——流水筛选/导出操作步骤含权限口径）
  docs/测试方案/: 修复V 冒烟方案（三项 UI 全链，P4 用）
  开发进度/: 修复V 进度文档（A/B/C 各轮）
  ```
- **文件**：上述 6-8 个文档
- **依赖**：A、B 全 commit 后
- **验证**：文档内容与实测行为一致；commit `docs: 修复V 勾销2x/17x+增补feature-map/user-ops+测试方案`。

---

## 功能联动点清单（L1-L6）

| # | 触发→联动 | 关键边界（反向/清空/批量） |
|---|---|---|
| L1 | 筛选任一变更→流水表重查 | 分页重置 1；清空筛选=回全量；翻页保留筛选；MEMBER 无筛选行（tab 现状锁） |
| L2 | 导出按钮→带当前筛选下载 | 空筛选=全量（≤5 万）；筛选后导出与表格所见同集；下载中按钮 loading；403 报错话术 |
| L3 | MEMBER 打开流水 tab | 零变化——无筛选行/无导出/仅本人行/余额空（IV D3 口径不回归）；MEMBER export 403 |
| L4 | 重进画布→视频副本恢复 | taskId 腿优先（能补全信息）；兜底腿只补 previewUrl+status，不写 mediaStatus/taskId；running 节点跳过交 resumePendingTasks；fileId 失效静默空壳（现状口径） |
| L5 | 上游视频卡渲染 | 有 src 显首帧/无 src 显「视」占位；单击开 Lightbox 手势不变（pointer-events:none）；图片/音频/文本上游不回归 |
| L6 | 版本回滚链 hydrate（:2468） | 兜底腿同生效（两调用点共 hydrate 函数）；回滚到无产物版本仍按占位渲染 |

## 安全检查清单（P3 逐项验证）

- [x] type 白名单 400（B1）
- [x] keyword LIKE 转义 + 参数化（B1/B2，inSql 子查询三 LIKE 全 `#{}`）
- [x] export MEMBER 403 + @AuditLog + 权限注解（B2）
- [x] MEMBER 筛选参数后端忽略（不信前端，B1）
- [x] CSV 注入（单元格以 = + - @ 开头）：备注是内部字段，**不做**公式前缀转义——落字「内部导出不外发，后续再说」

## 运维考量清单（7 类落字）

| 类 | 决策 | 说明 |
|---|---|---|
| 可观测性 | **做** | export INFO 日志（gid/操作人/行数/是否截断/参数摘要）；overview 筛选不加日志（读路径高频，靠既有访问日志） |
| 配置开关 | **不做** | 纯读增强无高危路径；导出上限是常量非开关 |
| 可回滚 | **做** | 零迁移零订正；前端筛选行整块 v-if 可整体摘除；导出端点独立新增删掉即回滚 |
| 限流/熔断/降级 | **不做**（导出上限即降级） | 5 万行硬顶+截断注记=自带护栏；无第三方依赖新增 |
| 运维入口 | **做** | CSV 本身=对账运维产物；截断注记明示丢行；EXPORT_ROW_LIMIT 常量一处可调 |
| 告警阈值 | **不做**（无告警系统） | 截断走 INFO+审计，接告警后续再说 |
| 容量/性能 | **后续再说** | keyword users 子查询全扫（<10⁴ 行量级）；流水超 10 万行再议归档/分区（V133 append-only 设计已留） |

## 技术坑点预判（全轮通用）

1. **MyBatis-Plus `.last()` 注入面**：LIMIT 值必须是代码常量，勿拼变量；`.last` 会整体拼在 SQL 尾部，wrapper 链上 orderBy 必须在 last 之前声明。
2. **`.apply` vs XML**：ledger 筛选走 LambdaQueryWrapper（无 join），keyword 跨表用 inSql 子查询——**不要**为 keyword 引入 XML join（改动面大）；users 侧无索引全扫在当前量级可接受。
3. **前端 WIP 混提**：工作区有用户未提交改动（ProjectGroupsView.vue 是 B 轮主战场且**不在** WIP 清单——开工前 `git diff --stat` 复核；CanvasView/PropertyPanel 同样逐文件过目）。
4. **objectURL 泄漏**：hydrate 兜底腿创建的 objectURL 与既有 hydratePreviews 同生命周期（画布卸载统一 revoke 链），勿额外 revoke/勿漏 revoke——照抄既有 :2266 处理方式。
5. **video preload 兼容**：Firefox `preload="metadata"` 偶不渲染首帧——加 `playsinline muted` 已是标准组合；实测档确认三浏览器口径（P4 只测 Chrome，Firefox 落「已知边界」记档即可）。
6. **axios blob 下载**：responseType:'blob' 时响应拦截器勿再 JSON.parse——request.ts 若无 blob 分支需加（按 Content-Type 判断），否则 CSV 下载变乱码报错。

## 验证总闸

- 后端：`mvn test` 全量基线 2566+ 不红；IT 单独 `mvn "-Dsurefire.excludedGroups=" "-Dtest=ProjectGroupLedgerFilterIT" test`（agent_platform_it 库）
- 前端：vue-tsc 0 + vitest 基线 846 不红（PropertyPanel/ProjectGroupsView/nodeClone 增补用例）
- 实测：Playwright 三项 UI 全链（A1 上游首帧 / A2 副本重进 / B 筛选+导出）；账号 smk4_leader（管理侧）/smk4_mem（成员锁 L3）
- 每 chunk 绿即 commit（显式 add 指定文件）

## 术语表

- BOM（字节顺序标记）：文件头三字节，告诉 Excel「这是 UTF-8」，双击打开不乱码。
- preload="metadata"：只加载视频元数据不下载全片，浏览器取首帧当封面。
- 兜底腿（fallback path）：主路径（taskId）走不通时的备用路径（fileId）。
- inSql 子查询：主查询条件里嵌一条 SELECT（这里用来「操作人账号/姓名/备注匹配关键词」）。
- RFC4180：CSV 标准——单元格含逗号/引号/换行时用双引号包裹，内部引号翻倍。
- append-only：只追加不修改的表（流水账设计，保证审计不可篡改）。
