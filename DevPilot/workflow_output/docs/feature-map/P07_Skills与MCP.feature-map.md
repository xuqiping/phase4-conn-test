# P07_Skills与MCP Feature Map

功能 → 代码速查（含技术原理大白话注解、三张新表注解）。

## 功能速查表

| 功能 | 后端 | 前端 | 测试 |
|------|------|------|------|
| SKILL.md 解析/渲染 | `core-skills/src/skill_file.rs` | — | skill_file 6 单测 |
| 技能目录注册/软删 | `core-skills/src/registry.rs` | — | registry 4 单测 |
| 上下文生成技能+导入导出 | `core-skills/src/generator.rs` + `commands.rs` save_skill_from_context/export_skill/import_skills | —（S5 输入框间接用） | generator 6 单测（含 AC-028 e2e） |
| 斜杠候选/展开 | `commands.rs` list_skills/invoke_skill | `components/input/SkillAutocomplete.tsx` `TaskInputBox.tsx` | TaskInputBox.test.tsx 6 例 |
| MCP 进程生命周期 | `core-mcp/src/manager.rs` `rpc.rs` | — | manager_process.rs 5 例（真进程） |
| MCP 市场目录/安装 | `core-mcp/src/market.rs` + `assets/market_catalog.json` + `commands.rs` list_mcp_market/install_mcp_server/add_mcp_manual | `components/settings/McpMarket.tsx` | market 6 单测 + market_install.rs 3 例 |
| MCP 管理页 | `commands.rs` list_mcp_servers/mcp_start/stop/restart/uninstall/logs | `components/settings/McpPanel.tsx` `views/Mcp.tsx` | McpPanel.test.tsx 4 例 |
| 图片附件（压缩≤1MB JPEG） | `commands.rs` save_attachment/delete_attachment（image crate） | `components/input/AttachmentChips.tsx`（TaskInputBox 挂载） | Attachments.test.tsx 6 例 |
| 语音听写（降级隐藏） | `commands.rs` voice_probe/voice_transcribe（占位 false） | `components/input/VoiceDictation.tsx` | 降级隐藏已测；真听写二期 |

## 表注解（L11 迁移）

- **skills_local**：技能注册表（源=文件系统扫描）。name 唯一（=斜杠命令名，GLOB `[a-z0-9-]*` 限长 64）；status valid/invalid 由解析结果定；enabled 是斜杠候选开关；删除=文件进 `.trash/` 软删，注册表行随扫描消失。
- **mcp_servers**：server 配置+运行状态。args_json/env_json 存 JSON（env 可能含 key，前端展示脱敏）；status 状态机 installed→running⇄stopped→error→manual_required；restart_count 供「5 分钟窗口超 3 次转人工」判定（MVP 用累计值，5 分钟窗口二期）。
- **input_attachments**：项目级图片附件登记。project_id 外键 CASCADE（项目删附件跟着删）；kind CHECK image；path 指向 `<项目>/attachments/img-*.jpg`；source_kb 记原图大小供 chip 展示。

关联：`projects ||--o{ input_attachments`（skills_local/mcp_servers 是全局的，不挂项目）。

## 技术原理大白话

- **技能=文件夹**：`~/.devpilot/skills/<name>/SKILL.md`（frontmatter+正文）。DB 只是扫描结果的镜像，文件才是真相——所以导入导出就是复制目录，不发明新格式。
- **斜杠展开**：输入 `/name` 选中后不立即注入正文，先挂 chip；提交时才把「正文+用户文本」拼成一个 prompt，审计记一次调用。
- **MCP stdio**：起子进程后按「一行一个 JSON」对话：initialize 握手（超时 kill）→ initialized 通知 → tools/list。危险启动命令过 P03 沙箱审批门，非 Allow 一律拒（后台进程没法弹窗问人）。
- **异常退出判定**：`stop()` 先把句柄移出 map 再杀进程；退出监听发现「进程死了但还在 map」= 异常退出 → 标 error → 自动重启（>3 次转 manual_required）。
- **死锁坑**：监听不能 hold 子进程锁调 `wait().await`——`stop()` 的 kill 拿不到锁会互相等死。改成 `try_wait` 轮询（每轮放锁）。
- **图片压缩**：质量 85→25 逐档试编码 JPEG，≤1MB 落盘；源图 >10MB 直接拒；解码带限额（≤8000px、≤64MB 分配）防解压炸弹（P4 修复）。存 `~/.devpilot/projects/<id>/attachments/`，不写用户项目目录（P4 修复：避免污染用户 git status）。附件路径提交时拼进 prompt（`[附图输入]` 段，AC-013 闭环）。
- **P4 审查修复要点**：env_json 注入子进程（此前 token 落库但进程拿不到）；探测只对 npx/uvx 等白名单运行时执行（防审批门前执行任意命令）；句柄代次号防 stop→start 竞态误判；MCP 安装/启停/重启/卸载进 audit_log（L12 新表）；frontmatter 结束标记按行匹配（正文 `---` 分隔线不再截断）。
