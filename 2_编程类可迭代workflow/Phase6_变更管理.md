# Phase 6 · 变更管理（修改 / 增加 / 删除已有功能的标准）

> 类型：流程步骤 · **Phase 6**（变更分支，**不是线性第 6 步**）
> 版本：v1.4（v1.0 初版；v1.1 对齐「功能联动点清单」+「运维考量清单」；v1.2 数据库变更后回写全局 db_schema.md，动作编号顺延；v1.3 sync-before-merge 硬规则：spec 未同步的变更不得合并收尾，漂移项记入进度总览待办；**v1.4 变更规格 delta（OpenSpec 格式 ADDED/MODIFIED/REMOVED），实施后回写 PRD 并归档**）
> 对应铁律：**#1 规格先行、#6 人在环路、#7 commit 当存档点**
> 定位：项目有了存量代码后，凡涉及**改 / 增 / 删已有功能**，进入本 Phase。它**复用** Phase2/3/4/5，再叠加变更专属的 7 条标准：影响评估、回归测试、文档同步、数据库安全变更、废弃流程、回滚、变更记录。

## 目标

让"动已有代码"和"从 0 造新功能"一样可控——**改之前查清影响，改之后回归验证、文档同步，删东西走废弃过渡，全程可回滚、可追溯**。存量代码的每一次改动，都不能比新功能更随意。

## 何时进入本 Phase（触发条件）

- ✅ **修改**已有功能的逻辑 / 接口 / 数据结构
- ✅ **删除**已有功能 / 接口 / 页面 / 字段 / 表
- ✅ **给已有功能增加新行为**（改变其规格）
- ❌ 从 0 开发**全新功能** → 走主链 Phase 1→5
- ❌ 纯新增、不动存量 → 走主链

> **边界**：Phase 5「迭代闭环」管**新增**功能（回 Phase 1/2）；本 Phase 管**改 / 删已有**功能。两者互补，别走错。

## 动作清单（统一标准，每次变更都走一遍）

> 不分大小，统一走完 A→H。规模大小只记进变更记录，不简化流程——保证不漏风险。

### A. 变更前：影响评估（必做）

1. **填影响评估表**（骨架见 [项目模板/workflow_output/docs/changes/_模板.影响评估.md](项目模板/workflow_output/docs/changes/_模板.影响评估.md)），归档到 `workflow_output/docs/changes/影响评估-<变更名>.md`：
   - 改 / 删的目标是什么、为什么
   - 用 **Feature Map 的调用链** + **全局搜索**（grep 函数名 / 接口路径 / 字段名）找出**所有引用点**
   - 列出受影响的功能 / 接口 / 页面 / 文档
   - **删功能 / 接口前，必须确认无外部依赖**（无依赖才能删；有依赖走 E 的废弃流程）
   - **【v1.1】查受影响功能的 plan.md「功能联动点清单」**：grep 只能找直接引用，找不出"父勾选→子全选"这类**逻辑联动**。改 / 删的若是某联动点的触发动作或联动对象，必须把这条联动列为受影响项，回归时单独验（含反向 / 半选 / 批量）。漏掉 = 改了字段半选失效、grep 却显示"没人引用"，安心删了线上炸。
   - **【v1.1】查受影响功能的 plan.md「运维考量清单」**：确认本次改 / 删**不会抹掉既有运维能力**（日志埋点、监控指标、配置开关、健康检查、降级路径）；若本次变更新增了运维需求，也要列入待埋项，随实施一起埋上。
2. **定回滚锚点**：变更前确保已 commit 干净，记住这个 commit——变更搞砸就回滚到这。

### B. 变更规格（更新真相源，铁律 #1）

3. **【v1.4】产变更规格 delta**：在 `workflow_output/docs/changes/变更规格delta-<变更名>.md` 用 **OpenSpec delta 格式**列出本次对**需求**的增 / 改 / 删（骨架见 [项目模板/workflow_output/docs/changes/_模板.变更规格delta.md](项目模板/workflow_output/docs/changes/_模板.变更规格delta.md)）：
   - **ADDED Requirements**：新增的 FR / AC（EARS/GWT 写法 + 验证方式）。
   - **MODIFIED Requirements**：改动的既有需求——记 was（原内容）→ now（新内容）+ 修改原因。**对外 API 改字段也在这里**（升版本号 + 兼容期，见 E）。
   - **REMOVED Requirements**：删除的需求——记原内容 + 删除原因 + 依赖处置（无依赖直删 / 有依赖走 E 废弃过渡）。
   > delta 只写「动了哪些需求」，不直接改 PRD 正文——它是变更期间的「提案」，便于 review、可追溯、支持多变更并行。
4. **更新 spec / PRD（sync）**：实施完成后，把 delta **回写 PRD 正文**（ADDED 加行 / MODIFIED 改 / REMOVED 删，AC 同步），然后 delta 归档。规格是唯一真相源——PRD 正文必须与实现一致。
5. **追加变更记录**：在 `workflow_output/docs/changes/变更记录.md` 追加一条（日期 / 变更类型 / 内容 / 原因 / 影响范围 / 涉及文件 / **delta 链接** / 回滚 commit）。

> **【v1.3 硬规则】sync-before-merge**：变更合并 / 收尾前，spec 必须已同步——**不允许"先合代码、spec 回头补"**。spec 没同步的变更视为未完成；已知未同步项必须记入 `workflow_output/开发进度/开发进度总览.md` 的「规格漂移待办」，未清零前不算收尾。
> **【v1.4】delta 是 sync 的结构化抓手**：有了 delta，"spec 同步没"不再是感觉判断——ADDED/MODIFIED/REMOVED 各有回写动作和勾选，sync-before-merge 可逐条核。

### C. 变更计划 + 实施（复用 Phase 2 / 3）

5. **出变更 plan**（复用 Phase 2）：变更大的出 `workflow_output/docs/plans/<变更名>.plan.md`，**含回滚预案**；小的可直接进实施。
6. **实施**（复用 Phase 3 的 chunk 循环：写 → 测 → commit → 沉淀）。

### D. 数据库变更（若涉及，铁律级约束）

7. **删表 / 改字段 / 改结构**：一律走 **Flyway 新版本迁移脚本**（`V<n>__<描述>.sql`），**绝不改已执行的旧脚本**。
8. **考虑数据迁移与兼容**：删字段 / 改类型，老数据怎么办？是否需要兼容期？写进迁移脚本注释。
9. **回写 db_schema.md**：脚本执行后同步更新 `workflow_output/docs/specs/db_schema.md`（全局 ER 图 / 数据字典 / Flyway 版本清单），保持全局数据库视图不失真。
   > 📌 表结构说明见各功能 Feature Map 的「数据库表与 SQL 注解（Flyway）」+ 全局 `specs/db_schema.md`。

### E. 删公开接口 / 功能：废弃流程（先废再删）

10. **不要一刀切删除对外暴露的接口 / 功能**：先标记 **deprecated（废弃）**，保留一段时间 + 给替代方案，再在下一次变更真正删除——给依赖方（别的模块 / 前端 / 第三方）过渡期。

### F. 回归测试（改 / 删必做，区别于新功能）

11. **跑全量自动化测试**（单测 / 集成 / E2E），全绿才继续。
12. **人工回归受影响功能**：照影响评估表里列的受影响项，逐个验证没被改坏。**删功能要把对应的测试也删 / 改**。
    - **【v1.1】受影响功能若有「功能联动点清单」，人工回归必须覆盖其中的联动用例**（含正向 / 反向 / 半选 / 批量），不能只验主流程——改 / 删最容易坏的就是边界联动。

### G. 文档同步（关键——现有流程最易漏的）

13. **改 / 增 / 删后，同步更新所有受影响文档**：
    - `workflow_output/docs/feature-map/` 下的 Feature Map（调用链 / 技术注解 / 建表注解）
    - `workflow_output/docs/user-ops/` 下的用户操作手册 User-Ops（若该功能有，且 UI 流程/入口/异常恢复有变）
    - `workflow_output/开发进度/` 下的功能 README
    - `workflow_output/项目规范约束/AGENTS.md`（若改了约定）
    - `workflow_output/docs/run-guide/快速启动速查表.md`（改了端口 / 服务 / 启动方式 / 桌面客户端）
    - `workflow_output/docs/deploy/部署手册.md`（改了部署相关）
    - `workflow_output/docs/ops/监控告警说明.md`（改了运维埋点 / 告警阈值；**删功能必删该功能的告警行**，否则天天误报）
    - `workflow_output/docs/specs/` 下的 PRD / spec
    - **删功能 → 删 `workflow_output/开发进度/` 下对应功能 README + `workflow_output/docs/feature-map/` 下对应 Feature Map + `workflow_output/docs/user-ops/` 下对应用户操作手册 + `workflow_output/docs/测试方案/` 下对应测试方案**，别留死文档
   - **【v1.1】删功能 → 同步清理运维侧**：监控指标、告警规则、运维脚本、看板 / 仪表盘里属于该功能的项也要删 / 改——功能没了告警还在跑 = 天天误报，是删功能后最常见的运维噪音。
   > 📌 文档和代码脱节是维护期最大的坑——这次改了不更新，下次照着旧文档改就出 bug。

### H. 验证 + commit（复用 Phase 4 / 5）

14. **验证**（复用 Phase 4）：跑起来，亲眼看改动生效 + 没破坏老的。
15. **commit**：每个变更一个清晰 commit（消息含变更原因），当回滚锚点。部署走 Phase 5。

## 产物

- `workflow_output/docs/changes/影响评估-<变更名>.md` —— 本次变更的影响评估表
- `workflow_output/docs/changes/变更规格delta-<变更名>.md` —— **【v1.4】**本次变更的需求 delta（ADDED/MODIFIED/REMOVED），实施后回写 PRD 并归档
- `workflow_output/docs/changes/变更记录.md` —— 追加一条变更记录（项目级 changelog，含 delta 链接）
- （变更大的）`workflow_output/docs/plans/<变更名>.plan.md`
- 已更新的各文档（`workflow_output/docs/feature-map/` 下 Feature Map / `workflow_output/docs/user-ops/` 下 User-Ops / `workflow_output/开发进度/` 下功能 README / `workflow_output/docs/run-guide/快速启动速查表.md` / `workflow_output/docs/deploy/部署手册.md` / `workflow_output/docs/specs/` 下 PRD…）
- （涉及数据库）新的 Flyway 迁移脚本
- 干净的 commit + 已跑通的回归测试

## 配套

- 变更记录模板 → `项目模板/workflow_output/docs/changes/_模板.变更记录.md`
- **【v1.4】变更规格 delta 模板 → `项目模板/workflow_output/docs/changes/_模板.变更规格delta.md`**
- 影响评估表模板 → `项目模板/workflow_output/docs/changes/_模板.影响评估.md`
- 复用：Phase 2 plan / Phase 3 实施 / Phase 4 验证 / Phase 5 部署

## 避坑点

- **删任何东西前先 grep 引用**：Feature Map 的调用链 + 全局搜索，确认没人用才删。删了才发现别处在用 = 线上炸。
- **数据库绝不改旧迁移脚本**：Flyway 靠版本号顺序执行，改旧脚本会报错或造成环境不一致。改结构就加新版本脚本。
- **文档同步别漏**：改了端口不更新启动速查表、删了功能不删 Feature Map，是最常见的维护期坑。建议变更收尾时照上面的「产物」清单逐项打勾。
- **删公开接口走废弃期**：今天删明天上线，依赖你的地方全炸。先 deprecated 再删。
- **改了必跑回归**：新增功能不用回归老的，但**改 / 删必须回归**——这是变更和新功能最大的区别。
- **规格同步**：改了功能不改 spec，spec 就不再是真相源，下次 AI 读 spec 会基于错误前提干活。
- **【v1.1】改 / 删最易悄悄破坏联动和运维埋点**：grep 找不出的逻辑联动（父勾选→子全选）、重构时顺手删的"看似没用"几行（其实是日志 / 监控 / 开关 / 降级），是变更翻车的两大隐形雷。靠「功能联动点清单」+「运维考量清单」兜底，别只信 grep。
- **【v1.1】删功能别只删代码**：代码、文档、测试之外，监控指标 / 告警规则 / 运维脚本 / 看板里的残留也要清，否则一直误报，运维天天被骚扰。

## 出口条件（变更完成）

- [ ] 影响评估表已填，引用点已查清（删功能已确认无依赖或已走废弃流程）。
- [ ] **【v1.1】受影响功能的「功能联动点清单」+「运维考量清单」已查**——既有联动未被破坏、既有运维埋点未被抹掉，新增运维需求已列入待埋。
- [ ] **【v1.1】人工回归覆盖了受影响的联动用例**（含正向 / 反向 / 半选 / 批量）。
- [ ] spec / PRD 已更新并记录变更原因（**sync-before-merge：spec 未同步的变更不得合并收尾**；已知未同步项已记入进度总览「规格漂移待办」）。
- [ ] **【v1.4】变更规格 delta 已产出**（ADDED/MODIFIED/REMOVED 齐全），已回写 PRD 正文并归档；变更记录已链 delta。
- [ ] 变更记录已追加。
- [ ] 数据库变更走 Flyway 新脚本，且已回写 `specs/db_schema.md`（若涉及）。
- [ ] 全量回归测试通过 + 受影响功能人工回归过。
- [ ] 所有受影响文档已同步（删功能已删 `workflow_output/开发进度/` 下对应功能 README + `workflow_output/docs/feature-map/` 下对应 Feature Map + `workflow_output/docs/user-ops/` 下对应 User-Ops + `workflow_output/docs/测试方案/` 下对应测试方案 + **【v1.1】运维侧的监控指标 / 告警规则 / 运维脚本 / 看板残留已清**）。
- [ ] 改动已 commit，部署（若需）走 Phase 5。
