# .claude · Claude Code 配置骨架

> 把「文档规则靠自觉」变成「机器自动校验 + 重复流程固化成技能 + 专职 agent」——对齐 2026 年 Claude Code 主力载体（Skills / Subagents / Hooks）。
> 拷贝项目模板后，用 Claude Code 开发则保留本目录生效；不用 Claude Code 可删除（`scripts/check_docs.py`、`scripts/check_all` 仍可手动跑，`.github/prompts/` 仍可用于 Copilot 等其他工具）。

## 目录结构

```
.claude/
├── settings.json              # hooks（机器自动校验）
├── skills/                    # 重复流程固化成技能（一条龙）
│   └── feature-wrapup/SKILL.md   # 功能收尾：README+FeatureMap+UserOps+进度+check_all+commit
└── agents/                    # 专职子 agent
    └── code-reviewer.md          # Phase 4 第二个 AI 审查者
```

## settings.json 的 hooks

| Hook | 触发时机 | 动作 | 效果 |
|---|---|---|---|
| `PostToolUse`（Write/Edit/MultiEdit） | AI 每次写/改文件后 | 跑 `python scripts/check_docs.py --quiet` | 文档超 4000 tokens 预警、超 5000 或失效链接立即反馈给 AI 修 |

按需扩展：commit 拦截（git pre-commit，骨架见 `scripts/pre-commit.sample`：check_all + gitleaks）、格式化（PostToolUse 挂 prettier/eslint --fix）。更多见 https://code.claude.com/docs/en/hooks

## skills/（重复流程 → 一条龙技能）

把多步、易漏的固定流程封装成 Skill，AI 按需调用，标准统一不漂移。

| 技能 | 作用 | 对应 Phase |
|---|---|---|
| `feature-wrapup` | 功能收尾一条龙（功能 README + Feature Map + User-Ops + 进度 + check_all + commit） | Phase 3 C 收尾 |

新增技能建议：任何「每次都要照同样步骤走、又常漏步」的流程（如「新功能起手式」「变更影响评估」）都适合固化成 Skill。

## agents/（专职子 agent）

把「换视角、隔离上下文」的任务交给子 agent，主会话只收结论（上下文工程 Isolate 原则）。

| Agent | 作用 | 对应 Phase |
|---|---|---|
| `code-reviewer` | 第二个 AI 审查者，按结构化清单 + 对抗式验证挑刺 | Phase 4 |

## 同源政策（防 .github/prompts/ 与 .claude/ 漂移）

**两处载体，内容单一真相源，绝不复制**：

| 载体 | 角色 | 谁读 |
|---|---|---|
| `.github/prompts/*.prompt.md` | **权威内容源**（tool-agnostic，Copilot/其他工具通用） | 所有工具 |
| `.claude/agents/*.md` / `.claude/skills/*/SKILL.md` | **Claude Code 包装层**——指向上述 prompt，不复制其内容 | Claude Code |

例：`code-reviewer.md` 不重写审查清单，而是「加载 `.github/prompts/4-review.prompt.md` 并按其执行」。改审查标准只改 prompt 一处，agent 自动跟随。技能同理：步骤细节多的，在 SKILL.md 里引用对应 Phase 文件而非复述。

## 注意

- hooks 命令以**项目根目录**为工作目录执行，路径用相对路径。
- 不希望每次编辑都跑检查时，把 matcher 收窄（如只对 `.md`）或整段删除。
- skills/agents 是 Claude Code 专有；团队用其他 AI 工具时，`.github/prompts/` 仍是通用入口。
