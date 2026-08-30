# P01 客户端骨架与状态机引擎 · 功能 README

> 受众：C 类（用户 + 技术）。一句话：DevPilot 的桌面外壳与「工作流发动机」。
> last_updated: 2026-08-14 ｜ 状态：✅ 已交付（签名/公证待证书，见文末）

## 用户视角：这是什么

安装 DevPilot 后你看到的第一个界面就是它：三栏窗口（顶栏管道条 / 左导航 / 中栏视图 / 右栏五 Tab）、新建项目向导（起个名、选规模）、点按钮推进阶段。你不需要懂任何开发概念——**阶段只能一步步走，没确认需求就不许开工**，这条纪律由内核强制执行，谁都绕不过。

- 阶段：想法 → 需求 → 计划 → 建造 → 验收 → 部署（规模 L0/L1 会自动精简）
- 门禁：需求确认 / 开工确认 / 安全检查 / 上线确认，过不了就推进不了
- 断点续开：关掉软件再开，项目停在上次的阶段，一分不差

## 技术视角：怎么实现的

| 层 | 内容 | 代码位置 |
|---|---|---|
| 状态机引擎 | YAML 定义（assets/workflow/default.yaml）→ 校验 → 转移裁决 → 历史落库 | `crates/core-state/src/machine/` |
| 存储 | SQLite（WAL + L 版本迁移 + 写串行队列），本地 7 表 | `crates/core-state/src/db/` + `migrations/` |
| IPC | 5 个 commands + `kernel://state` 事件推送；前端不存业务真相 | `src-tauri/src/commands.rs` |
| 前端 | React19 + Zustand；视图注册表驱动四处联动；HUD + 虚拟列表 | `src-ui/`（lib/stores/components/views） |
| 出包 | Tauri 2 Bundler；Win MSI 已验证；双端 CI 在仓库根 .github | `src-tauri/tauri.conf.json`、`.github/workflows/devpilot-ci.yml` |

关键架构决策：**单一真相源在 Rust**——前端 Zustand 只存「正在看哪个视图」，业务状态全部来自内核快照；命令无状态（DB 即真相），天然支持断点续开。

## 覆盖规格

FR-018（骨架+出包）、FR-029（状态机核心+UI）、FR-039（驾驶舱基础）、FR-042（项目档案基础）、FR-048（持久化）；AC-020（Win 端出包）/ AC-032（越阶段拒绝）/ AC-043（HUD 数据源部分）/ AC-052（状态机层恢复）。

## 测试与质量

- Rust：27 用例（迁移幂等 / 800 并发写 / AC-032 全门禁 / AC-052 恢复 / 坏 YAML 降级…）
- 前端：12 用例（三栏 / 密度 / 联动 LI1 正反向 / 创建向导 / 门禁 toast / 恢复 / 虚拟列表）
- 人工验收：`docs/测试方案/P01客户端骨架与状态机引擎测试方案.md`（TC1~8 待真机走查）

## 遗留待办

- 签名三件套（更新密钥 / Apple 证书 / Win EV）+ 更新服务端点 → CI secrets，配齐即自动签名公证
- 真机冒烟 TC1~8 待走查；Mac 实机出包未验（无 Mac 环境，靠 CI）
- 工作流 YAML 外部覆盖路径的设置页 UI 未做（loader 已支持）
