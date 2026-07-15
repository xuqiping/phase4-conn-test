---
description: "生成实现计划（Plan 步）。让 AI 深度思考后产出逐步骤计划，未经许可不实现。"
---

# 1 · Plan —— 生成实现计划

你的目标：基于规格文档，生成一份**AI 编码助手可照着执行的实现计划**。
计划写到新文件 `workflow_output/docs/plans/<需求名>.plan.md`。需求名自行命名（如「用户认证」→ `user_authentication.plan.md`）。

## 规则
- 保持简单，**不要过度架构**。
- **不生成真代码**，伪代码可以。
- 每步包含：目标、动作、涉及文件（≤20个，尽量更少）、必要伪代码、依赖、需人工介入的点、验证步骤。
- **把无障碍性（accessibility）融入每一步**，不单列一步。
- 参照 [file_structure.md](/workflow_output/docs/file_structure.md) 和 [AGENTS.md](/workflow_output/项目规范约束/AGENTS.md) 的规范。

## 执行步骤
### 1. 首先
- 读规格文档（`workflow_output/docs/specs/PRD.md` 及相关），理解需求与目标。
- 必要时用 Context7 拉技术栈最新文档。

### 2. 然后
- 生成结构化实现计划，覆盖达成规格目标所需的全部步骤。
- 加 frontmatter（见 `workflow_output/docs/plans/_模板.plan.md`）。
- 计划里**始终包含验证步骤**，确保实现满足需求。
- 按模板格式输出为 Markdown。

### 3. 接着
- 自审计划，确认它满足需求、可执行。
- **与我迭代直到我满意**。

### 4. 最后
- **未获我明确许可，不要开始实现。**
