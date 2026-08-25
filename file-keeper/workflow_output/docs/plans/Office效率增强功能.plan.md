# Office 效率增强功能实现总计划

> **For agentic workers:** Phase 3 必须使用 `phase3-implement`，推荐按分计划逐 Chunk 执行并在每个检查点审查。本文只含伪代码，不授权写码。

**目标：** 建成安全、可恢复的 Office 本地批处理工作台，并提供 Office Pro 大批量权益和受计量的 AI 安全助手。

**架构：** Vue 单页工作台调用 Rust 任务调度器；标准 OOXML 与 Windows Office COM 分别由隔离 Worker 执行；服务端只处理 Office Pro、AI 积分和脱敏后的 AI 请求。

**技术栈：** Vue 3/TS/Tailwind、Tauri 2/Rust/rusqlite、OOXML ZIP/XML、Windows Office COM Worker、Spring Boot/PostgreSQL/Redis、Vitest/cargo test/JUnit。

---

## 1. 分计划与依赖顺序

| 顺序 | 分计划 | 可独立交付结果 | 依赖 |
|---:|---|---|---|
| 0 | 技术尖峰与样本矩阵 | 确认引擎路由和支持等级 | 无 |
| 1 | [统一安全底座](Office统一安全底座.plan.md) | 可创建、预检、模拟执行、取消和恢复任务 | 0 |
| 2 | [Office Pro 与 AI 积分](OfficePro与AI积分.plan.md) | 免费额度、Pro 实时校验、管理员授予、积分账本 | 1 可并行后半段 |
| 3 | [Excel 处理](OfficeExcel处理.plan.md) | 拆分、字段映射合并、公式与宏策略 | 1、2 |
| 4 | [Word 批量替换](OfficeWord批量替换.plan.md) | 指定范围的跨文件替换 | 1、2 |
| 5 | [PowerPoint 处理](OfficePowerPoint处理.plan.md) | 合并、主题策略、外链换源、宏预检 | 1、2 |
| 6 | [AI 安全助手](OfficeAI安全助手.plan.md) | 规则生成、字段映射、质量体检、脱敏 | 1、2、3 |

## 2. Chunk 0：技术尖峰与兼容矩阵

- [ ] **目标：验证高保真路线，禁止凭库文档直接承诺兼容。**
  - 动作：建立最小样本集；分别验证 OOXML 透传与 Office Worker；记录文件前后结构、宏、公式、母版和链接差异。
  - 涉及文件（≤20）：`workflow_output/docs/测试方案/Office兼容样本矩阵.md`、`src-tauri/tests/fixtures/office/README.md`、`tools/office-worker-windows/README.md`、`src-tauri/src/bin/office_ooxml_worker.rs`（尖峰分支）。
  - 依赖：测试机具备至少两代 Microsoft Office；准备无敏感内容样本。
  - 伪代码：`for sample -> scan -> process copy -> reopen -> compare invariant set -> classify SAFE/HIGH_FIDELITY/BLOCKED`。
  - 验证：`.xlsm/.pptm` 宏部件可保留；复杂文件未达到标准时自动路由或阻止；源哈希不变。

- [ ] **检查点 0：用户确认支持矩阵后，才进入生产代码。**

## 3. 集成 Chunk

- [ ] **目标：把五个子系统接入同一 Office 工作台。**
  - 动作：接入 Tab、收藏分组入口、统一向导、任务历史、套餐状态和 AI 入口；全流程中英双语、全键盘、焦点可见和屏幕阅读器标签。
  - 涉及文件：`src/App.vue`、`src/components/office/OfficeWorkspace.vue`、`src/stores/officeTaskStore.ts`、`src/locales/zh-CN.ts`、`src/locales/en.ts`、`src/types/office.ts`。
  - 依赖：分计划 1–6 完成。
  - 验证：从收藏多选进入时输入自动带入；反向取消选择同步更新；切换任务类型不遗留旧规则；超额/AI 状态提示准确。

- [ ] **目标：完成跨模块回归与文档交付。**
  - 动作：运行全部测试；产出 Feature Map、用户手册、人工测试方案、开发进度 README；同步 AGENTS 和 file_structure 的最终文件名。
  - 涉及文件：`workflow_output/docs/feature-map/Office效率增强.feature-map.md`、`workflow_output/docs/user-ops/Office效率增强用户操作手册.md`、`workflow_output/docs/测试方案/Office效率增强测试方案.md`、`workflow_output/开发进度/Office效率增强/README.md`。
  - 依赖：所有分计划。
  - 验证：四套测试命令全绿；人工兼容矩阵通过；无高危安全问题；文档单文件不超过 5000 tokens。

## 4. 功能联动总清单

| 触发动作 | 联动对象 | 预期变化 | 反向/批量边界 |
|---|---|---|---|
| 收藏多选 → Office 处理 | 输入清单 | 带入支持文件，标出不支持项 | 取消收藏选择不删除已确认任务；用户决定同步 |
| 文件增删 | 额度与风险 | 重新计算数量/体积/风险 | 从超额降回免费时清除在线票据 |
| 选择仅同名列 | 字段映射 | 不同名列变为排除 | 改为追加列时恢复候选顺序 |
| 用户解决宏冲突 | 开始按钮 | 全部阻断项解决后启用 | 任一映射撤销立即重新阻断 |
| AI 建议接受/拒绝 | 本地规则 | 仅接受项进入执行计划 | 全拒绝不扣第二次积分，不清空手工规则 |
| 任务取消 | Worker/历史/临时目录 | 停止、标记、延迟清理 | 已发布多输出保留，未发布单输出丢弃 |
| Office Pro 被撤销 | 新任务 | 超额任务不可开始 | 正在运行任务不远程杀死，记录审计并禁止续跑 |

## 5. 技术坑点预判

- OOXML 库重新序列化可能丢失未知部件；规避：未编辑部件字节透传，样本比较 ZIP entries、relationships、VBA 和 custom XML。
- Office COM 可能弹窗、卡死或附着用户 Office；规避：独立实例、禁用宏执行/提示、心跳、任务 PID 追踪，绝不按进程名批量 kill。
- VBA 模块合并可能有过程重名、引用和签名失效；规避：静态预检 + 用户映射 + 未解决即阻断，不自动执行宏。
- 大批文件逐个打开会产生 I/O 和 Office 启动瓶颈；规避：Worker 复用受控 Office 实例、单 Office 并发、OOXML 分批并发、进度持久化。
- SQLite 百万明细全量加载会卡 UI；规避：覆盖索引、分页、摘要查询，禁止 N+1（循环逐条查数据库）。
- AI 余额并发可能重复扣费；规避：数据库事务、乐观锁、requestId 唯一、预扣/结算/释放账本。

## 6. 安全检查清单

- [ ] 路径规范化、真实路径、符号链接越界和输出/输入重叠校验。
- [ ] 源文件只读；替换原件二次确认、备份和外部修改检测。
- [ ] 密码仅内存/系统凭据；日志与 SQLite 泄露扫描。
- [ ] Worker 白名单目录、无命令行敏感参数、超时和最小权限。
- [ ] 宏禁止自动执行；密码保护/签名/引用/重名阻断。
- [ ] 超额任务每次在线校验，票据仅绑定单任务和短有效期。
- [ ] 模型 Key 仅服务端；AI 请求预扣、限流、幂等和成本熔断。
- [ ] 文档提示注入无权调用工具；AI 输出 schema 白名单和用户确认。
- [ ] 管理员套餐/积分操作权限与审计。

## 7. 运维考量

| 项目 | 决定 | 开发期动作 |
|---|---|---|
| 可观测性 | 做 | 本地 taskId、服务端 traceId/requestId；阶段耗时、失败码、Worker 健康指标 |
| 配置开关 | 做 | Office Worker、宏合并、PPT 外链、AI 功能和全局成本熔断独立开关 |
| 可回滚 | 做 | Flyway 只新增；套餐/积分变更用补偿账本；本地 DB 版本迁移前备份 |
| 限流/熔断/降级 | 做 | AI 超时/熔断；服务端故障时免费本地任务可用 |
| 运维入口 | 做 | 管理员授予/撤销 Pro、积分调整、按 requestId 查用量；不提供正文查看 |
| 告警 | 做 | AI 成本、失败率、余额异常、Pro 校验异常、Worker 崩溃率 |
| 自动支付 | 本轮不做 | 只保留未来订单系统接口边界 |
| 多租户企业策略 | 后续 | 当前按用户套餐，不引入组织层级 |

## 8. Phase 3 硬闸门

- [ ] 所有分计划经用户批准。
- [ ] 技术尖峰支持矩阵经用户确认。
- [ ] 用户明确说“开始实施”后才允许写代码。

## 术语表

| 术语 | 大白话 | 示例 |
|---|---|---|
| 支持矩阵 | 哪类文件用哪个引擎、支持到什么程度的清单 | 宏文件必须走 Office Worker |
| 检查点 | 做完一段先验收再继续 | 尖峰通过后做安全底座 |
| N+1 | 先查列表再循环查每一条造成大量数据库请求 | 历史详情要批量查询 |
