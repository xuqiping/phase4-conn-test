---
description: "逐步实现计划（Implement 步）。一次一步，每步勾选 done，跑测试，频繁提交。"
---

# 2 · Implement —— 逐步实现计划

你的任务：**一次实现计划中的一个步骤**。

## 规则
- 计划只是建议、指引方向，**不合理时可以偏离**，但要同步我。
- **完成一步、在 plan.md 勾选 done 之后，再进下一步**。
- 参照 [file_structure.md](/workflow_output/docs/file_structure.md) 和工作区已有实现，保持风格一致。
- **不要改动**只作为参考的文件。
- 实现并运行计划中描述的单元测试。
- 每完成一个 chunk（测试通过），**建议我提交一次 commit**（commit 当存档点）。
- 看不懂自己写的代码，先加注释或简化——**never commit code you can't explain**。
- 判断本步是否产出通用能力/约定，是则提示我沉淀进 [AGENTS.md](/workflow_output/项目规范约束/AGENTS.md)。
- **每一轮对话结束时，必须更新开发进度**：在 `workflow_output/开发进度/<功能名>/开发进度n.md` 记录本轮内容。文档/理论类写清步骤、产出文件、被谁引用；代码类写清实现功能、对应 plan/PRD 编号、涉及文件、关键代码位置、测试结果、commit SHA。
- **所有文档守 5000 tokens 上限**：写到 4000 tokens 左右就预警，超限时拆分为新文件或建 `总路由.md` 索引。

## 执行
- 按 plan.md 当前步骤实现。
- 跑测试，失败就 debug，循环到通过。
- 自审实现是否满足需求。
- **与我迭代直到我满意**。
- **本轮对话结束前**：
  - 更新 `workflow_output/开发进度/<功能名>/开发进度n.md`（若文件接近 5000 tokens，则新建 `开发进度<n+1>.md` 或 `总路由.md`）。
  - 若本步产出通用约定，提示我更新 `workflow_output/项目规范约束/AGENTS.md`。
- **功能全部 chunk 完成后**：
  - 产出/更新 `workflow_output/docs/feature-map/<功能名>.feature-map.md`（粗略代码位置 + 技术原理注解 + 建表注解）；
  - 若该功能是 B/C 类用户可见功能，产出/更新 `workflow_output/docs/user-ops/<功能名>用户操作手册.md`（细化到每一步的傻瓜式操作文档）。
