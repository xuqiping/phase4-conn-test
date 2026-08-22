# P07_Skills与MCP · 计划详情

> 上级：[P07_Skills与MCP.plan.md](P07_Skills与MCP.plan.md)。每步：目标 / 动作（伪代码）/ 涉及文件 / 依赖 / 验证步骤。

## S0 数据模型与迁移（L11）｜FR-025/026/011｜依赖：无（B1 起点）

**目标**：三张新表 + L11 迁移注册 + schema 文档回写。

**动作（伪代码）**：
```
L11__skills_mcp.sql:
  CREATE TABLE skills_local(
    id PK, name UNIQUE CHECK(名规则), display_name, description,
    yaml_path, version, enabled 默认1, status(valid/invalid), created_at...)
  CREATE TABLE mcp_servers(
    id PK, name UNIQUE, transport(stdio), command, args_json, env_json,
    status(installed/running/stopped/error), pid, last_error, enabled, created_at...)
  CREATE TABLE input_attachments(
    id PK, project_id, kind(image), path, source_kb, created_at...)
  注册进 core-state 迁移列表；db_schema.md 回写三表字段与关联注解
```

**涉及文件**：`core-state/src/migrations.rs`、新 `core-state/src/skills.rs`、`core-state/src/mcp_store.rs`、`core-state/src/attachments.rs`、`specs/db_schema.md`
**验证**：迁移单测（旧库升级）；CRUD 内存测试；`check_docs.py` 过。

## S1 core-skills 解析/注册/启停｜FR-025｜依赖 S0｜[P]（与 S2 无文件交集）

**目标**：技能文件规范落地：解析、校验、注册、启停、invalid 容错。

**动作（伪代码）**：
```
core-skills/src/skill_file.rs:
  parse_skill_md(text) -> Skill{frontmatter{name,description,version}, body}
    剥 '---' 块 → serde_yaml 解析 → name 规则校验([a-z0-9-]{1,64})
    解析失败返回 InvalidSkill(大白话原因)，不 panic
  render_skill_md(fields, body) -> text（生成器复用）
core-skills/src/registry.rs:
  scan_dir(~/.devpilot/skills/) → 对每个子目录 SKILL.md 解析 → upsert skills_local
  set_enabled(id, bool)；delete(id) = 移到 .trash/ 子目录 + 软删记录
```

**涉及文件**：`core-skills/src/skill_file.rs`、`core-skills/src/registry.rs`、`core-skills/src/lib.rs`
**验证**：单测——合法/缺 frontmatter/坏 YAML/名字非法 4 用例；扫描幂等（重复 scan 不重复注册）。

## S2 MCP 进程管理器｜FR-026｜依赖 S0｜[P]（与 S1 无文件交集）

**目标**：core-mcp 从占位变实现：spawn、握手、健康检查、退出回调、日志缓冲、重启（AC-029 核心）。

**动作（伪代码）**：
```
core-mcp/src/manager.rs:
  McpHandle{child: tokio::process::Child, stdin, stdout 行 reader, log_ring: VecDeque≤200}
  start(server_row):
    command/args 过 core-sandbox 危险命令清单（沿用 P03 审批）
    spawn(kill_on_drop) → 后台 task 读 stdout 行进环形日志
    initialize 请求（JSON-RPC id=1, protocolVersion, capabilities）
    10s 超时未应答 → kill + status=error(last_error=超时大白话)
  子进程 exit 回调 → status=error + last_error=退出码 → 触发前端事件
  自动重启：退出后 5min 窗口内 >3 次 → 停止自动，标 manual_required
  restart(id) = stop(等待≤3s 再 kill) + start
  list_tools(id) → tools/call 走同一 JSON-RPC 通道（MVP 仅供后续用，先暴露 IPC）
  健康检查：30s 串行 ping 各 running server，超时标 error
```

**涉及文件**：`core-mcp/src/manager.rs`、`core-mcp/src/rpc.rs`（JSON-RPC 编解码）、`core-mcp/src/lib.rs`
**验证**：单测用「echo 假 server」（内嵌小脚本：读行回 JSON）——握手成功/超时 kill/退出回调标 error/重启恢复/日志不超 200 行。

## S3 技能生成器 + 导入导出｜FR-025｜依赖 S1｜[P]（与 S4 无文件交集）

**目标**：对话/任务上下文一键存成技能（AC-028 前半）；导入导出。

**动作（伪代码）**：
```
core-skills/src/generator.rs:
  generate_from_context(name, description, task_prompt, rounds_summary) -> SKILL.md 文本
    = render_skill_md(frontmatter, body=任务 prompt 精简 + 关键步骤 + 注意事项模板段)
  save_skill(text) → 写 ~/.devpilot/skills/<name>/SKILL.md → registry.scan 增量注册 → 立即可斜杠调用
  export(list of ids) → 复制各技能目录到用户选的导出目录（Tauri dialog）
  import(path) → 复制进 skills 目录 → scan → 报告成功/失败清单
src-tauri/src/commands.rs:
  save_skill_from_context / export_skills / import_skills 三个 IPC（输入校验 + 审计日志）
```

**涉及文件**：`core-skills/src/generator.rs`、`src-tauri/src/commands.rs`、`src-tauri/src/lib.rs`（注册命令）
**验证**：单测——生成文本含合法 frontmatter；同名保存提示冲突（Err 而非覆盖）；导入含坏文件返回逐项结果。

## S4 MCP 市场安装｜FR-010｜依赖 S2｜[P]（与 S3 无文件交集）

**目标**：图形化市场：点选安装免改配置（AC-012）。

**动作（伪代码）**：
```
core-mcp/src/market.rs:
  内置目录 assets/market_catalog.json（~15 常用 server：name/desc/runtime(npx|uvx)/args/env 说明）
  fetch_online(refresh开关) -> 合并目录（网络失败降级仅内置 + WARN 日志）
  install(entry, user_env):
    探测运行时（node -v / uv --version）→ 缺失返回大白话安装指引（不装）
    command/args 受沙箱清单校验 → 写 mcp_servers 记录 → manager.start 探测
    成功→status=running（AC-012「立即可用」）；失败→error + 原因，可重试或删除
  add_manual(json)：手填 JSON 配置（schema 校验）走同一条安装链路
```

**涉及文件**：`core-mcp/src/market.rs`、`core-mcp/assets/market_catalog.json`、`src-tauri/src/commands.rs`
**验证**：单测——目录解析/运行时探测缺失分支/schema 校验拒绝非法 JSON；安装链路用假 server 走通 status 流转。

## S5 斜杠调用与输入框集成｜FR-025｜依赖 S1｜[P]（与 S6 的 settings 组件无交集）

**目标**：输入框 `/` 弹技能候选，选中展开注入（AC-028 后半）。

**动作（伪代码）**：
```
src-ui/components/input/SkillAutocomplete.tsx:
  输入以 '/' 开头且光标在首词 → 查 ipc.list_skills(enabled) 前缀过滤弹层
  ↑↓ 选择、Enter 回填 `[/name](展开)`、Esc 关闭；输入 '/usr/xxx' 不弹（规则：首词不含 '/' 后第二段字符即路径态——简化：仅当'/'后字符与某技能前缀匹配才弹）
src-ui/components/input/TaskInputBox.tsx（抽取现有任务输入为组件）:
  提交时若选中技能 → prompt = 技能 body + '\n\n' + 用户文本；chip 显示已挂技能
src-tauri/src/commands.rs: list_skills / invoke_skill(返回展开文本，纯前端拼接也可——选 IPC 返回文本，便于审计日志记一次调用)
```

**涉及文件**：`src-ui/components/input/SkillAutocomplete.tsx`、`src-ui/components/input/TaskInputBox.tsx`、`src-ui/lib/ipc.ts`、`src-tauri/src/commands.rs`
**验证**：vitest（mock ipc）——`/re` 弹出 release-check、Enter 注入文本；`/usr/bin` 不弹；禁用技能不在列表。

## S6 MCP 管理页 UI｜FR-026｜依赖 S2+S4｜[P]（与 S5 无文件交集）

**目标**：管理页：列表/启停/状态/日志/重启/市场（AC-029 展示侧）。

**动作（伪代码）**：
```
src-ui/components/settings/McpPanel.tsx:
  状态徽章 running/stopped/error/manual_required（error 红色 + 「一键重启」高亮）
  行操作：启动/停止/重启/卸载（确认弹窗）；日志抽屉（环形 200 条 + 一键复制 + 截断展示）
src-ui/components/settings/McpMarket.tsx:
  目录卡片列表（搜索框过滤）→「安装」→ 进度态（探测中→启动中→完成/失败原因）
  「手动添加」JSON 输入框（schema 校验错误行内提示）
状态推送：复用 emit_current_state 事件通道新增 mcp 状态变更事件
```

**涉及文件**：`src-ui/components/settings/McpPanel.tsx`、`src-ui/components/settings/McpMarket.tsx`、`src-ui/lib/ipc.ts`、设置入口 `src-ui/components/layout/`
**验证**：vitest——error 态显示重启按钮；安装失败显示大白话原因；卸载需确认。真机 AC-029 由人工方案覆盖。

## S7 多模态输入（拖图+语音）｜FR-011｜依赖 S5（复用其 TaskInputBox，同一文件故串行 B5）

**目标**：截图/线框图拖入 + 按住空格语音听写，进任务输入、可见可编辑（AC-013）。

**动作（伪代码）**：
```
src-ui/components/input/AttachmentChips.tsx:
  TaskInputBox onDrop/onPaste → 图片类型与大小校验(≤10MB) → ipc.save_attachment
  Rust 侧压缩到 ≤1MB JPEG 存 ~/.devpilot/projects/<id>/attachments/ → 返回缩略图路径
  chip 展示缩略图 + ×；提交时任务输入附 attachments 列表
src-ui/components/input/VoiceDictation.tsx:
  能力探测（配置开关 + 运行时探测）不可用 → 整体隐藏（降级不阻塞）
  输入框聚焦时按住 Space：keydown(非重复)开始录音 → 松开停止 → 转写文本插入光标处（可继续编辑）
  Windows：优先 OS 听写 API（SAPI/WinRT SpeechRecognizer）经 Rust 侧命令；转写 <3s 超时放弃并提示
  转写中文本框显示「正在听…」态；Esc 取消不插入
src-tauri/src/commands.rs: save_attachment / voice_probe / voice_transcribe(blob)
```

**涉及文件**：`src-ui/components/input/AttachmentChips.tsx`、`src-ui/components/input/VoiceDictation.tsx`、`src-tauri/src/commands.rs`、`src-ui/components/input/TaskInputBox.tsx`（挂载点）
**验证**：vitest——拖非图片拒绝、超 10MB 拒绝、chip 删除联动；语音探测失败隐藏。真机语音（AC-013）人工方案。

## S8 测试收口｜全部 AC｜依赖 S3~S7

**动作（伪代码）**：
```
补齐用例名带 AC：AC-028（保存即可斜杠调用，e2e 级：save→list→invoke 文本展开）
AC-029 自动化：假 server kill 后 manager 状态=error 且 restart 恢复（S2 已有，收口断言审计日志）
AC-012：安装链路假 server 走通免配置断言（无任何手改文件动作）
AC-013 拆自动化（拖拽校验）+ 人工（真语音）→ 产 workflow_output/docs/测试方案/P07_Skills与MCP测试方案.md（含联动用例正/反/半选/批量）
```

**涉及文件**：各 crate tests、`src-ui/**/*.test.tsx`、`workflow_output/docs/测试方案/P07_Skills与MCP测试方案.md`
**验证**：`check_all` 全绿；测试方案含全部联动边界用例。

## S9 文档与索引｜项目规范｜依赖 S8

**动作（伪代码）**：
```
Feature Map（含三表注解）+ User-Ops（B/C 类：技能怎么存怎么调、MCP 怎么装怎么救、语音怎么用）
+ 功能 README；开发进度/P07 目录建档；MVP 总索引 P07 置 ✅；file_structure.md 更新新增目录
```

**涉及文件**：`docs/feature-map/P07_Skills与MCP.feature-map.md`、`docs/user-ops/P07_Skills与MCP用户操作手册.md`、`开发进度/P07_Skills与MCP/README.md`、`plans/00_MVP实现计划总索引.md`、`docs/file_structure.md`
**验证**：`check_docs.py` 无 FAIL；索引状态与实际一致。
