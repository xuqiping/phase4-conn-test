# AGENTS.md · 项目级 AI 指令

> 这是 Context Engineering 的核心产物。AI agent 每次开工前必读，定义它的「行为准则」。
> 等价于 CLAUDE.md（Claude）/ GEMINI.md（Gemini）/ copilot-instructions.md（Copilot），用 AGENTS.md 通用命名。
> Phase 0 建初版，Phase 3 每完成一个通用能力就织入更新。

## 通用规则（CORE RULES）

### 代码风格
- 语言/框架：
- 缩进、命名、范式偏好（如：函数式优先 / 避免 OOP 滥用）：
- 必须通过的 lint / 格式化工具：

### 禁忌（不要做）
- 不使用：<某些函数/库/模式>
- 不引入：<某些依赖>

### 偏好（优先这么做）
- 遇到 X 优先用 Y 方式
- 注释风格：<如：修 bug 时在注释里简述理由>

## 反幻觉条款（硬性）
- 不确定或缺少上下文时，**先问，不要编**。
- 不要引用不存在的函数/库/API。
- 修 bug 时说明理由（注释或对话）。

## 工作流约束
- **specs before code**：开工前先读 workflow_output/docs/specs/PRD.md。
- **plan before implement**：按 workflow_output/docs/plans/<功能>.plan.md 走，逐步骤勾选。
- **commit 当存档点**：每完成一个 chunk（测试通过）立即建议提交。
- **人工测试方案（按需）**：每开发一个功能，判断是否需要人工交互测试（UI / 主观体验 / 真实第三方 / 需人工确认）；需要则在 `workflow_output/docs/测试方案/<功能名>测试方案.md` 产出测试方案，不需要则跳过、不产文件。
- **never commit code you can't explain**：看不懂的代码先加注释或简化。

## 文档写作规范
- **单文件 5000 tokens 上限**：所有 workflow_output/ 下的文档（开发进度、Feature Map、User-Ops、功能 README、测试方案、PRD、AGENTS.md 等）不得超过 5000 tokens。接近 4000 时预警，超限时拆分为子文件 + 总路由索引，禁止无限膨胀。
- **功能 README（每个功能完成时）**：在 `workflow_output/开发进度/<功能名>/README.md` 产出，先判定受众：**A 技术类**只写技术说明；**B 用户类**写用户地图（谁用 / 什么场景 / 什么效益）+ 简要技术说明；**C 两者**都写。
- **开发进度（每一轮对话结束）**：在 `workflow_output/开发进度/<功能名>/开发进度n.md` 记录，文档类写清步骤/产出文件/被谁引用；代码类写清实现功能/对应 plan/涉及文件/关键代码位置/测试/commit。
- **专业术语批注（specs / plans）**：术语首次出现**行内括注一句大白话**，并在**文档底部维护术语表**（术语 \| 大白话 \| 简单案例）。主文专业度不变，批注只作辅助。

## 模块级约束（按需新增 XX约束.md 并在此索引）
- [通用约束.md](通用约束.md) —— 跨所有模块
- [鉴权约束.md] —— 商业授权/登录体系（示例，建立后所有模块基于它）
- [i18n约束.md] —— 中英双语规范（示例）

## 参考文档
- 项目结构 → [workflow_output/docs/file_structure.md](../docs/file_structure.md)
- 需求规格 → [workflow_output/docs/specs/PRD.md](../docs/specs/PRD.md)
