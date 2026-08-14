# Architecture · DevPilot 系统架构

> 上级：[PRD.md](PRD.md)。关键技术决策回链 ADR（[adr/README.md](../adr/README.md)）。
> last_updated: 2026-08-13

## 1. 总体架构

```
┌─ 桌面客户端（Tauri 2：Rust 内核 + 系统 Webview 前端）─────────┐
│  React 19 UI 层：三栏工作台 / 管道条 / 卡片流 / 驾驶舱 HUD      │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Rust 内核（本地服务层，crates 划分见 §3）                 │ │
│  │  状态机引擎 · 任务编排器 · 本地沙箱执行器 · Token 计量器   │ │
│  │  Skills 引擎 · MCP 客户端 + MCP Server · CLI/深链入口     │ │
│  └────────────────────────────────────────────────────────┘ │
│  执行目标：用户本机项目目录（git + workflow_output/）           │
└──────────────┬────────────────────────────────────────────────┘
               │ HTTPS（仅：鉴权 / Token 计费 / 模型转发）
┌──────────────▼────────────────────────────────────────────────┐
│ 轻量云端（NestJS）：账号 · 余额/账本/充值 · 模型网关（多供应商     │
│ 路由/重试/服务端权威计量）· 技能市场（二期）                     │
│ PostgreSQL + Redis                                             │
└───────────────────────────────────────────────────────────────┘
```

设计要点：**执行全部在本地，云端零算力**——与 Token 转售商业模式咬合（ADR-002）。

## 2. 技术栈落点

| 层 | 选型 | 理由（详见 ADR） |
|---|---|---|
| 桌面框架 | Tauri 2（Rust + 系统 Webview） | 包体小、内存省、Rust 承接系统能力（ADR-001） |
| 前端 | React 19 + TypeScript + Vite + Tailwind/Radix | 重度定制 UI；AI 生成质量与招聘友好 |
| 本地内核 | Rust workspace（多 crate） | 状态机/沙箱/CLI 需系统级能力 |
| 本地存储 | SQLite（rusqlite）+ 项目目录 workflow_output/ 文件 | 状态机与产物落盘，断点续开（FR-048） |
| 云端后端 | NestJS + PostgreSQL + Redis | 云端极薄：账号/计费/网关 |
| 模型接入 | 模型网关统一转发（Anthropic/OpenAI/DeepSeek/Qwen），按难度路由 | 计量服务端权威（ADR-003）；路由省成本 |
| 联网搜索 | **模型原生搜索为主力**（对齐 Codex/Claude Code：Anthropic `web_search` / OpenAI `web_search` / Kimi `$web_search`（按新 Formula 机制 `moonshot/web-search` 接入）服务端工具，用户零配置、费用随账单）；无原生搜索的国产模型走**博查/智谱**；**SearXNG 自托管**兜底；深读层 Firecrawl/Jina Reader 转 Markdown。网关层**缓存搜索结果**（同任务内相同查询不重复调用不重复扣费——规避 Claude Code 已暴露的结果注入重复计费坑） | 与 ADR-003 同源：Key 不下发、服务端计量（FR-050）。选型依据：2026-08 调研（Anthropic/OpenAI/Kimi 原生搜索文档 / Claude Code WebFetch 管线 / 国内 SearXNG+博查实践） |
| 支付 | 微信支付 + 支付宝；Stripe 备选 | 中文市场必备 |
| 打包分发 | Tauri Bundler + GitHub Actions matrix（Win/Mac）+ Tauri Updater 自动更新 | 双端签名/公证前置 |
| 预览与冒烟 | 用户预览=内嵌 Webview 指向本地 dev server；agent 冒烟=**Playwright 捆绑 Chromium**（截图/操作留证） | FR-052：预览窗格与验收自动化（AC-036/058）的执行底座 |
| 文件树 | 内核提供目录遍历/文件读取 IPC（只读优先，受目录白名单约束） | FR-051 |

## 3. 客户端模块划分（Rust workspace crates）

| crate | 职责 | 对应 FR |
|---|---|---|
| `core-state` | 阶段状态机（YAML 驱动）+ 项目/任务/轮次持久化 | FR-029/046/047/048 |
| `core-orchestrator` | chunk→任务编排、subagent 分派、产物生成器（PRD/plan/AGENTS.md） | FR-003/030~035 |
| `core-sandbox` | 目录白名单、命令审批、macOS Seatbelt / Windows Job Object | FR-001/006/009 |
| `core-exec` | 进程执行、测试/lint 探测、环境画像、环境一键安装 | FR-003/004/005 |
| `core-meter` | Token 预估/实扣本地镜像、余额同步、超限暂停 | FR-041 |
| `core-skills` | Skills 解析/生成/注册/斜杠调用 | FR-025 |
| `core-mcp` | MCP 客户端 + MCP Server（对外委派接口） | FR-010/026/027 |
| `core-cli` | CLI 二进制与 devpilot:// 深链参数路由 | FR-021/028 |
| `core-i18n`（预留） | 文案资源外置 | 二期 i18n |

前端层（`src-ui/`）：`views/`（驾驶舱/想法/需求/计划/建造/验收/部署七视图）、`components/`（管道条/卡片/气泡/点选层）、`stores/`（Zustand）、`lib/`（IPC 封装）。

## 4. 接口设计

### 4.1 内部接口简表（客户端 ⇄ 云端，HTTPS/JSON，鉴权 Bearer JWT）

| 路径 | 方法 | 入参 | 出参 | 错误码 | 回链 FR |
|---|---|---|---|---|---|
| `/api/v1/auth/register` | POST | {phone, code, password?} | {token, refresh_token, user} | 400 参数错 / 409 已注册 / 429 验证码频繁 | 门户账号体系 |
| `/api/v1/auth/login` | POST | {phone, code\|password} | {token, refresh_token} | 401 凭证无效 | 同上 |
| `/api/v1/balance` | GET | — | {balance_cents, gift_cents, month_spent_cents} | 401 | FR-041 |
| `/api/v1/balance/transactions` | GET | {page, size, month?} | {list:[{id, task_id, model, tokens_in, tokens_out, amount_cents, created_at}], total} | 401 | FR-041 |
| `/api/v1/payments/recharge` | POST | {pack_id, channel: wechat\|alipay} | {order_id, pay_params} | 400 / 402 支付失败 | FR-041 |
| `/api/v1/payments/webhook` | POST | 支付平台回调 | {ok} | 400 验签失败 | FR-041 |
| `/api/v1/gateway/chat` | POST | {model, messages, task_id, client_nonce} | SSE 流式 tokens + 末帧 {usage} | 401 / 402 余额不足 / 429 限流 / 502 上游失败 | FR-003 等全部 LLM 调用 |
| `/api/v1/gateway/estimate` | POST | {model, prompt_tokens_est} | {cost_cents_est} | 400 | FR-041 |
| `/api/v1/gateway/search` | POST | {query, intent: web\|deep_read, url?} | {results:[{title,url,snippet}]\|{markdown}} + {usage} | 402 余额不足 / 502 全供应商失败 | FR-050 |
| `/api/v1/skills/market` | GET | {page, tag?} | {list:[{id, name, desc, version, downloads}]} | — | FR-045（二期） |
| `/api/v1/devices/pair` | POST | {pair_code} | {device_token} | 404 码过期 | FR-019（二期） |

约定：金额一律 `*_cents` 整数；所有写接口幂等（`client_nonce` 去重）；网关错误码 402 触发客户端「余额不足」引导充值流程。

### 4.2 对外开放接口（二期分流 docs/api/）

CLI（FR-021）、深链（FR-028）、MCP Server（FR-027）、SDK（FR-023）四者共用同一委派内核：`delegate(task_desc, project_path, options) → task_id` + `poll(task_id) → result`。二期按 `_模板.API文档.md` 单独立档，变更走 Phase 6。

## 5. 关键技术决策

| 决策 | 结论 | ADR |
|---|---|---|
| 桌面框架 | Tauri 2 而非 Electron | [ADR-001](../adr/ADR-001-桌面框架选型Tauri.md) |
| 执行位置 | 本地执行而非云端沙箱 | [ADR-002](../adr/ADR-002-本地执行而非云沙箱.md) |
| Token 计量 | 服务端权威计量 + 网关统一转发 | [ADR-003](../adr/ADR-003-Token计量服务端权威.md) |

## 6. 部署形态

- 客户端：安装包分发（Win: NSIS/msi；Mac: dmg 公证），内置 Tauri Updater 自动更新。
- 云端：单机 Docker Compose 起步（NestJS + PG + Redis），域名 + HTTPS；流量增长后网关独立扩容。

## 7. 术语表

| 术语 | 大白话 | 简单案例 |
|---|---|---|
| crate | Rust 的代码模块单位，一个 crate 管一件事 | core-sandbox 只管沙箱 |
| SSE | 服务器单向推送数据的通道，适合流式输出 | AI 回答逐字显示 |
| 幂等 | 同一请求发多次只生效一次，防止重复扣费 | 网络重试不重复充值 |
| 公证（notarization） | 苹果对 Mac 软件的安全盖章，没章会被系统拦 | 安装包能直接打开 |
| IPC | 前端网页和 Rust 内核之间的通话管道 | 点按钮触发本地命令 |
