# workflow_output · SDD 流程入口

> 本目录是「编程类可迭代工作流」（Spec→Plan→Implement→Run）在本项目的落地入口。
> 规范来源：`e:\workspace\2_编程类可迭代workflow\`（0_工作流总览 / Phase0-6 / 项目模板）。
> 建立日期：2026-07-17。

## 两套文档体系（重要）
| 目录 | 角色 |
|---|---|
| `项目工程文档/`（既有，保留） | 内容真相源（PRD/设计/ADR/计划/速查表/数据库设计/进度） |
| `workflow_output/`（本目录，新增） | SDD 流程导航 + 模板 + AI 指令 |

原则：workflow_output/ 优先做**导航索引**，内容已存在的用链接指向，不重复抄。

## 目录与 Phase 对应
| 路径 | Phase | 作用 |
|---|---|---|
| [项目规范约束/AGENTS.md](项目规范约束/AGENTS.md) | 0 起 | AI 指令 + 文档规范（**开工必读**） |
| [项目规范约束/通用约束.md](项目规范约束/通用约束.md) | 0 起 | 跨模块编码规范 |
| [docs/项目分析/项目分析报告.md](docs/项目分析/项目分析报告.md) | 0 | 商业前置 |
| [docs/specs/PRD.md](docs/specs/PRD.md) | 1 | 规格 + 术语表 |
| [docs/file_structure.md](docs/file_structure.md) | 1 | 目录导航 |
| [docs/plans/总路由.md](docs/plans/总路由.md) | 2 | 实现计划（→既有计划1-11） |
| [docs/测试方案/](docs/测试方案/) | 3 | 人工测试方案（按需） |
| [docs/feature-map/总路由.md](docs/feature-map/总路由.md) | 3 | 代码速查表（→既有速查表01-23） |
| [docs/user-ops/总路由.md](docs/user-ops/总路由.md) | 3 | 用户操作手册（待补） |
| [docs/run-guide/快速启动速查表.md](docs/run-guide/快速启动速查表.md) | 4 | 快速启动 |
| [docs/deploy/部署手册.md](docs/deploy/部署手册.md) | 5 | 部署 |
| [docs/changes/变更记录.md](docs/changes/变更记录.md) | 6 | 变更记录 + 影响评估 |
| [开发进度/开发进度总览.md](开发进度/开发进度总览.md) | 3 | 进度跟踪 |

## 配套
- [`.github/prompts/`](../.github/prompts/) —— 1-plan / 2-implement / 3-run / 4-review 提示词（路径已校准到 `agent-platform/workflow_output/`）。
- 每个子目录均保留 `_模板*.md` 空白模板，新功能开发时复制填写。

## 主链（按顺序）
Phase 0 商业前置 → Phase 1 规格 → Phase 2 计划 → Phase 3 实现（每轮对话更新开发进度）→ Phase 4 运行验证 → Phase 5 发布 →（改/增/删已有功能）Phase 6 变更管理。
