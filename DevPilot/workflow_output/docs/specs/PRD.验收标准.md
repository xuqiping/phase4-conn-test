# PRD.验收标准 · AC 全表

> 上级：[PRD.md](PRD.md)。写法统一 **EARS**（WHEN/WHILE/IF-THEN/WHERE），中文表述。
> 每条 AC 可独立测试；验证方式 ∈ {自动化测试, 人工验收, 混合}。闭环要求：每条 AC 在 [testing_strategy.md](testing_strategy.md) 的 AC 映射表中有唯一落点。

## §1 G1 本地执行

| AC 编号 | 对应 FR | 验收标准（EARS） | 验证方式 |
|---|---|---|---|
| AC-001 | FR-001 | WHEN agent 执行任务需读写项目目录之外的文件, THEN THE SYSTEM SHALL 暂停并向用户弹出审批，未批准不得执行 | 自动化+人工 |
| AC-002 | FR-001 | WHILE 任务执行中, THE SYSTEM SHALL 将全部文件改动记录到任务日志，供事后审计 | 自动化 |
| AC-003 | FR-002 | WHEN 用户同时启动 ≥2 个任务, THE SYSTEM SHALL 为每个任务创建独立 git worktree，任务间文件互不可见 | 自动化 |
| AC-004 | FR-003 | WHEN chunk 代码生成完成, THE SYSTEM SHALL 自动运行项目测试与 lint，失败时自动修复重试，达到上限后向用户报告失败原因（大白话） | 自动化 |
| AC-005 | FR-004 | WHEN 同一项目第二次及以后执行任务, THE SYSTEM SHALL 复用环境画像，环境就绪耗时 ≤10 秒 | 自动化 |
| AC-006 | FR-005 | IF 检测到缺失运行时（如未装 Node）, THEN THE SYSTEM SHALL 展示一键安装向导而非报错终止 | 人工验收 |
| AC-007 | FR-006 | WHEN 任务请求访问白名单外域名, THE SYSTEM SHALL 阻断请求并提示用户选择放行/拒绝 | 自动化 |
| AC-008 | FR-007 | IF 任务因关机或崩溃中断, THEN WHEN 客户端重启后 THE SYSTEM SHALL 提示从最近存档点恢复，恢复后任务状态与中断前一致 | 人工验收 |

## §2 G2 配置与记忆

| AC 编号 | 对应 FR | 验收标准（EARS） | 验证方式 |
|---|---|---|---|
| AC-009 | FR-008 | WHEN 用户在大白话表单修改项目约定, THE SYSTEM SHALL 同步重写项目根 AGENTS.md，且全程不要求用户接触该文件 | 自动化 |
| AC-010 | FR-009 | WHERE 用户选择「每一步都问我」, THE SYSTEM SHALL 在每次文件修改和命令执行前请求批准 | 自动化 |
| AC-011 | FR-009 | WHERE 用户选择「自动走，关键点叫我」, THE SYSTEM SHALL 仅在卡点（开工/上线/变更/安全未过）请求决策 | 人工验收 |
| AC-012 | FR-010 | WHEN 用户在 MCP 市场点击安装, THE SYSTEM SHALL 完成下载配置并使工具立即可用，全程无需用户编辑配置文件 | 人工验收 |
| AC-013 | FR-011 | WHEN 用户拖入截图或按住空格语音输入, THE SYSTEM SHALL 将图像/转写文本纳入当前任务输入并可见可编辑 | 人工验收 |
| AC-014 | FR-012 | IF 任何输出（对话/日志/代码/截图说明）包含已配置密钥, THEN THE SYSTEM SHALL 以 `***` 脱敏后呈现与落盘 | 自动化 |

## §3 G3 审阅与交付

| AC 编号 | 对应 FR | 验收标准（EARS） | 验证方式 |
|---|---|---|---|
| AC-015 | FR-013 | WHEN 任务产出 diff, THE SYSTEM SHALL 默认展示大白话摘要（改了什么/为什么/影响哪里），并可一键切换代码原文 | 人工验收 |
| AC-016 | FR-014 | WHEN 同一任务存在多个版本, THE SYSTEM SHALL 提供并排 diff 对比视图，并支持选定任一版本应用 | 人工验收 |
| AC-017 | FR-015 | WHEN 任务完成后用户追加指令, THE SYSTEM SHALL 沿用同一项目环境与上下文续跑，不新建环境 | 自动化 |
| AC-018 | FR-016 | WHEN 用户确认交付, THE SYSTEM SHALL 可向 GitHub 或 Gitee 创建 PR，PR 描述含大白话变更说明 | 人工验收 |
| AC-019 | FR-017 | WHEN 用户以自然语言提问代码库, THE SYSTEM SHALL 返回带文件路径引用的答案及大白话解释 | 人工验收 |

## §4 G4 客户端形态与集成

| AC 编号 | 对应 FR | 验收标准（EARS） | 验证方式 |
|---|---|---|---|
| AC-020 | FR-018 | THE SYSTEM SHALL 在 Windows 10+ 与 macOS 13+（含 Apple Silicon）完成安装并走通「想法→建造」全流程 | 人工验收 |
| AC-021 | FR-018 | WHEN 用户按下全局快捷键（默认 Ctrl/Cmd+Shift+D）, THE SYSTEM SHALL 唤起迷你派单输入条（客户端已运行时） | 人工验收 |
| AC-022 | FR-019 | WHEN 任务需审批或完成, THE SYSTEM SHALL 向已配对移动端推送通知，移动端可完成审批 | 人工验收 |
| AC-023 | FR-020 | WHEN 用户在 VS Code 打开同一项目, THE SYSTEM SHALL（插件）展示与工作台一致的任务与阶段状态 | 人工验收 |
| AC-024 | FR-021 | WHEN 外部执行 `devpilot run "任务" --project <路径>`, THE SYSTEM SHALL 创建任务并以 JSON 输出任务 ID；任务完成后输出结果 JSON | 自动化 |
| AC-025 | FR-022 | WHEN 用户在 IM 中 @机器人 派单, THE SYSTEM SHALL 创建任务并在完成后回执结果摘要 | 人工验收 |
| AC-026 | FR-023 | THE SYSTEM SHALL 提供 TypeScript SDK，支持以 ≤10 行代码编程式委派任务并取回结果 | 自动化 |
| AC-027 | FR-024 | THE SYSTEM SHALL（Web 门户）提供登录/充值/余额明细/技能市场，且不出现任何开发操作入口 | 人工验收 |

## §5 G5 能力开放

| AC 编号 | 对应 FR | 验收标准（EARS） | 验证方式 |
|---|---|---|---|
| AC-028 | FR-025 | WHEN 用户在对话中点「存成技能」, THE SYSTEM SHALL 生成符合 Skills 规范的 YAML 技能文件，保存后可立即以斜杠命令调用 | 自动化 |
| AC-029 | FR-026 | IF MCP server 异常退出, THEN THE SYSTEM SHALL 在管理页标记异常状态并提供一键重启 | 自动化 |
| AC-030 | FR-027 | WHEN 外部应用经 MCP 协议委派任务, THE SYSTEM SHALL 在任务流创建带来源标识的任务，完成后经 MCP 返回结果 | 自动化 |
| AC-031 | FR-028 | WHEN 系统浏览器打开 `devpilot://run?task=...&project=...`, THE SYSTEM SHALL 唤起客户端并预填任务参数 | 人工验收 |

## §6 G6 工作流引擎与体验层

| AC 编号 | 对应 FR | 验收标准（EARS） | 验证方式 |
|---|---|---|---|
| AC-032 | FR-029 | WHILE 项目处于任一阶段, THE SYSTEM SHALL 仅允许该阶段 YAML 定义的动作，越阶段操作被状态机拒绝并提示原因 | 自动化 |
| AC-033 | FR-030 | WHEN 用户完成想法访谈, THE SYSTEM SHALL 生成项目分析报告并给出「值得做/先缩小/再想想」三选一建议 | 人工验收 |
| AC-034 | FR-031 | IF 存在未确认的需求卡, THEN THE SYSTEM SHALL 保持「开工」按钮锁定不可点击 | 自动化 |
| AC-035 | FR-032 | WHILE 用户未点击「开工」, THE SYSTEM SHALL 不创建/修改任何代码文件 | 自动化 |
| AC-036 | FR-033 | WHEN 进入验收阶段, THE SYSTEM SHALL 将测试方案转为步骤化清单，自动项标注结果、人工项给出操作+预期 | 自动化+人工 |
| AC-037 | FR-033 | WHEN 用户在验收项点「有问题」并圈选预览元素, THE SYSTEM SHALL 自动生成定位到该元素的修复任务 | 人工验收 |
| AC-038 | FR-034 | WHEN 用户完成部署三步向导, THE SYSTEM SHALL 完成发布并生成部署手册（含回滚步骤） | 人工验收 |
| AC-039 | FR-035 | WHEN 用户描述变更需求, THE SYSTEM SHALL 先输出影响评估大白话报告（受影响功能/验收项/预估工作量），确认后才执行 | 人工验收 |
| AC-040 | FR-036 | WHEN 展示 PRD/diff/日志/报错, THE SYSTEM SHALL 提供大白话⇄原文切换，专业术语悬停显示大白话解释 | 人工验收 |
| AC-041 | FR-037 | WHEN 用户选择任一存档点回滚, THE SYSTEM SHALL 恢复代码+配置+数据到该点，且回滚操作不扣 Token | 自动化 |
| AC-042 | FR-038 | WHILE 任务执行中, THE SYSTEM SHALL 默认展示「正在做什么」叙事流，终端日志可逐步展开 | 人工验收 |
| AC-043 | FR-039 | WHEN 用户打开项目, THE SYSTEM SHALL 默认进入驾驶舱并展示进度/缺陷/规格覆盖率/累计消耗四项指标 | 自动化 |
| AC-044 | FR-040 | IF L2+ 项目安全扫描存在未通过项, THEN THE SYSTEM SHALL 阻止发布并列出修复清单 | 自动化 |
| AC-045 | FR-041 | WHEN 任务开始执行, THE SYSTEM SHALL 先展示预估 Token 消耗；完成后记录实际消耗并可查明细 | 自动化 |
| AC-046 | FR-042 | WHEN 用户创建项目并选择规模, THE SYSTEM SHALL 按 L0~L3 映射启用对应严格度的工作流与必产文档 | 自动化 |
| AC-047 | FR-043 | WHEN 用户在预览中点选元素并输入修改描述, THE SYSTEM SHALL 生成绑定该元素的选择器与修改任务 | 自动化 |
| AC-048 | FR-044 | IF 需求存在歧义（多解）, THEN THE SYSTEM SHALL 先向用户追问澄清，禁止自行假设后继续 | 人工验收 |
| AC-049 | FR-045 | WHEN 用户从模板市场创建项目, THE SYSTEM SHALL 套用模板的技术栈与工作流配置完成初始化 | 人工验收 |
| AC-050 | FR-046 | WHEN 用户语音描述需求或缺陷, THE SYSTEM SHALL 转写为文字并经用户确认后进入任务流 | 人工验收 |
| AC-051 | FR-047 | WHEN 用户在驾驶舱点「继续下一轮」, THE SYSTEM SHALL 以新里程碑批次复用需求→计划→建造→验收流水线，历史轮次归档可查 | 自动化 |
| AC-052 | FR-048 | WHEN 用户重新打开项目（含隔天/换机拷贝目录）, THE SYSTEM SHALL 恢复到最后状态，AI 凭进度文档与规格摘要续跑，无需历史对话 | 人工验收 |

## §6.1 G7 扩展能力

| AC 编号 | 对应 FR | 验收标准（EARS） | 验证方式 |
|---|---|---|---|
| AC-053 | FR-050 | WHEN agent 需要联网信息, THE SYSTEM SHALL 经统一搜索服务返回带来源 URL 的结果；IF 主供应商失败, THEN 自动降级备用供应商，全程对用户透明 | 自动化 |
| AC-054 | FR-050 | WHEN 发起搜索/网页深读调用, THE SYSTEM SHALL 按次计费并在消耗明细中单列「联网搜索」条目 | 自动化 |
| AC-055 | FR-049 | WHERE 用户安装 Computer Use MCP server, THE SYSTEM SHALL 将其纳入 MCP 管理页统一管理，其动作受命令审批约束（P3 探索） | 人工验收 |
| AC-056 | FR-051 | WHEN 用户打开「文件」页签, THE SYSTEM SHALL 展示项目目录树，点击文件显示内容；只读模式下任何编辑尝试被拒绝 | 人工验收 |
| AC-057 | FR-052 | WHEN 预览目标为本地 dev server, THE SYSTEM SHALL 以内嵌浏览器窗格展示，支持刷新与设备尺寸切换 | 人工验收 |
| AC-058 | FR-052 | WHEN 验收阶段执行自动冒烟, THE SYSTEM SHALL 以捆绑 Chromium（Playwright）自动执行可自动化验收项，并保存截图证据供用户回看 | 自动化 |

## §7 追溯编号体系

| 环节 | 编号用法 |
|---|---|
| PRD | FR-xxx → AC-xxx（本文件唯一定义） |
| plan.md | 每个 Step 标注「对应需求：FR-xxx」 |
| 自动化测试 | 用例名/注释带 AC 号（`// AC-041`） |
| 人工测试方案 | 用例表带「对应 AC」列 |
| commit message | 带 FR 号，如 `feat: FR-031 需求卡确认锁` |
| 开发进度 | 「对应编号」填 plan Step / FR / AC |
