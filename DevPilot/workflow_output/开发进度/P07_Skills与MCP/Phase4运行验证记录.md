# P07 Phase 4 运行验证记录（2026-08-22）

## 1. Run：全量质量门复跑

| 门 | 结果 |
|---|---|
| cargo test --workspace | ✅ 全绿（含 core-skills 16 / core-mcp 9+5+3 集成 / core-state 38 等） |
| tsc --noEmit | ✅ 0 错 |
| vitest run | ✅ 15 文件 67 例 |
| check_docs.py | ✅ FAIL=0 |

桌面端打包安装（安装→启动→核心功能→卸载）：与 P06 一致归入**真机人工走查**（用户已指示统一稍后做，见总览挂起项），本期以 Rust 命令层 + 组件层自动化代替。

## 2. AC 逐条核对（PRD.验收标准.md）

| AC | 方式 | 结论 |
|---|---|---|
| AC-028 存技能即可斜杠调用（自动化） | `generator.rs::ac028_save_then_list_then_invoke_e2e` + TaskInputBox 6 例 | ✅ 通过 |
| AC-029 MCP 异常标记+一键重启（自动化） | `manager_process.rs` 5 例（silent 超时/die-after-init/危险拒/stop 幂等/崩溃恢复） | ✅ 通过 |
| AC-012 市场点选安装免配置（人工） | 自动化部分：`market_install.rs` 3 例 + McpMarket 3 例 ✅；真机点装 filesystem/fetch 待人工 M1-M3 | ⚠️ 自动化过，人工挂起 |
| AC-013 拖图/语音纳入输入（人工） | 自动化部分：Attachments 6 例 ✅；语音本期 probe=false 诚实降级（二期）；真机拖图/粘贴待人工 A1-A4 | ⚠️ 自动化过，人工挂起 |

规格漂移：**语音听写按 PRD 是 FR-011 一部分，本期降级未实现**——已在 plan/User-Ops/测试方案三处明示为二期，属「少做了（有意、已记档）」，非静默漂移。其余待审查 agent 漂移清单。

## 3. 性能评测（perf_smoke.rs，已并入测试套）

| 指标（plan 运维清单） | 实测 | 目标 | 结论 |
|---|---|---|---|
| 5 个 MCP server 并发启动 | **235ms** | <5s | ✅ 余量 20 倍 |
| 20 技能列表查询 | **0.13ms** | <200ms（瞬时） | ✅ |
| 语音按键→转写 <3s | 本期未接入 | — | 二期再测 |

并发/资源：MCP 为本地单用户桌面场景，manager 单锁串行命令、每 server 日志环形 ≤200 行×500 字，无内存无界增长路径（复查确认）。

## 4. Review（第二个 AI 交叉审查）+ 修复记录

对抗式审查推翻「没问题」假设：8 维度中 5 个 ⚠️，列 3 个最怀疑位置、13 项修复清单。**全部高危/中危已修**（commit 见 git log `fix: P07 P4 交叉审查修复`）：

| # | 严重度 | 问题 | 修复 |
|---|---|---|---|
| 1 | 高 | 附件从未进任务输入（AC-013 断链） | TaskInputBox 提交时拼 `[附图输入]` 路径段 + 新增测试断言 prompt 含路径 |
| 2 | 高 | env_json 不注入子进程（token 全失效） | start_proc 解析 env_json 并 `.envs()` 注入 |
| 3 | 高 | detect_runtime 在审批门前执行任意 command | 只对 npx/node/npm/uvx/uv/python 白名单探测，其余跳过交给审批门 |
| 4 | 高 | 附件写用户项目目录（污染 git status） | 改存 `~/.devpilot/projects/<id>/attachments/` |
| 5 | 中 | 图片解码无上限（解压炸弹 OOM） | ImageReader Limits：≤8000px、≤64MB 分配 |
| 6 | 中 | list_tools 持 map 锁 5s 阻塞全局 | stdin/responses 改 Arc 共享，等待期不持锁 |
| 7 | 中 | stop→start 竞态误判异常退出/并发 start 双进程 | 句柄代次号（gen）比对 + 入 map 前二次查重兜底 |
| 8 | 中 | MCP 安装/启停/重启/卸载无审计 | 新 L12 audit_log 表（source=skills/mcp/multimodal）+ 全命令接入，db_schema 已回写 |
| 9 | 低 | frontmatter `find("\n---")` 被正文分隔线截断 | 按行匹配全横线行 + 新测试 |
| 10 | 低 | registry valid 分支 display_name 填了 description | 改填 name，与 invalid 口径一致 |
| 11 | 低 | 技能列表仅挂载加载一次 | textarea 聚焦时刷新 |
| 12 | 低 | export_skill 可写任意目录 | 限定用户主目录内 |
| 13 | 低 | enabled 启停 IPC 缺失 | **记档**：无技能管理页承载，随二期启停 UI 一起做（见总览规格漂移待办） |

修复后全量门复跑：clippy -D warnings 0 错 / workspace 测试全绿 / tsc 0 错 / vitest **68 例**（新增附件进 prompt 断言）/ check_docs FAIL=0。

**漂移清单处置**：附件存储路径（代码改为符合 plan）、导出 zip（目录复制，已声明简化）、重启 5 分钟窗口（累计值，已声明二期）、语音（有意降级二期）——均已闭环；#13 记档。

## 5. User-Ops 全量验证

`docs/user-ops/P07_Skills与MCP用户操作手册.md` 各步骤依赖真机 GUI（拖图/点装/强杀进程），与 AC 人工项合并到挂起的真机走查轮；自动化已覆盖手册中可断言路径（斜杠候选/校验拒绝/降级隐藏）。
