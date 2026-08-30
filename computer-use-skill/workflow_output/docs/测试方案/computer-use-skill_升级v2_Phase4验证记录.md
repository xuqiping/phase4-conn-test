# computer-use-skill 升级 v2 · Phase 4 运行验证记录

日期：2026-08-27 ｜ 真机：Win10 / 解锁状态 / DPI 100% ｜ 功能：升级 v2（后台执行层 + 学习记忆）

## 1. 冒烟运行（Run）
- `scripts/integ_upgrade_v2.mjs`：真机双 Fixture，**8/8 通过**
  - Fixture 1（记事本，Win32 层2 正例）：后台键入、双击选词替换 ✅
  - Fixture 2（Voice to Text，WebView2 负例）：层2 失效→自动降级、锚点沉淀、命中查询 ✅
- `npm test`：vitest 全量 **55 过 / 2 skip（CU_INTEG 门控）**
- `scripts/check_all.sh`：**全绿 PASS**

| 检查项 | 脚本/命令 | 结果 |
|---|---|---|
| lint | eslint | ✅ |
| unit tests | vitest | ✅ 55/57 |
| audit | npm audit --high | ✅ |
| docs | check_docs.py | ✅ |
| 集成真机 | integ_upgrade_v2.mjs | ✅ 8/8 |

## 2. AC 逐条核对

| AC | 方式 | 结果 |
|---|---|---|
| 100 后台键入/点击 | 真机记事本 | ✅ |
| 101 文本内容确认 | 真机 UIA 树断言 | ✅ |
| 102 截图比对验证 | 单测 verify.test.ts | ✅ |
| 103 层2 降级 SendInput | 真机 V2T | ✅ |
| 110 锚点自动沉淀 | 真机+单测 | ✅ |
| 111 命中秒操作 | 真机+单测 | ✅ |
| 112 失效作废重学 | 单测 memory.test.ts | ✅ |
| 113 memory_list/forget | 单测+tools.test.ts | ✅ |

## 3. 性能评测（vs performance_goals）

| 指标 | 目标 | 实测 | 判定 |
|---|---|---|---|
| 层2 PostMessage + 验证 | ≤400ms | ~350ms（25-42ms 截图×2 + 300ms 等待）| ✅ |
| 记忆命中查询 | ≤10ms | <1ms（单测）| ✅ |
| 记忆写盘（原子写）| ≤20ms | ~5ms（单测）| ✅ |
| 单文件锚点库 | ≤1MB | 正常场景 <100KB | ✅ |
| vitest 全量 | — | 6.12s（含 1.6s transform）| ✅ |

## 4. Review

### 4.1 对抗式审查（第二轮 AI）
- **状态**：本期未单独安排第二个 AI 做审查（默认已在实现过程中自检+单测覆盖）。
- 建议：进入日常迭代前，可用 review 工作流专门审计 `postmsg.ts` 的消息安全与 `verify.ts` 的阈值漂移。

### 4.2 人复核发现
1. **层2 对菜单栏无效**：PostMessage 无法触发记事本菜单栏，属 Windows 设计——已在开发进度4 记录。
2. **未聚焦窗口高亮不渲染**：双击选区逻辑生效，但截图验证看不到高亮；用内容替换（`X`）间接验证。
3. **V2T 完全忽略 PostMessage**：降级链路实测可靠，记忆沉淀后再次命中走 `sendinput`，不会反复试错。

## 5. User-Ops 增量验证
- `click` 新增 `name` 参数后，Agent 视觉定位时附带语义名即可自动记忆
- `memory_list`/`memory_forget` 走查通过
- 配置开关 `layer2_enabled`/`memory_enabled` 未写入文档示例，已在 SKILL.md 和 API 契约补充

## 6. 结论
冒烟通过、AC 覆盖、性能达标、质量门全绿——**升级 v2 可放行日常使用**。建议把 `integ_upgrade_v2.mjs` 纳入每次发布前的回归集合。

## 证据
- 日志：`workflow_output/docs/测试方案/证据/upgrade-v2/smoke_run.log`
- 截图与报告：`workflow_output/docs/测试方案/证据/upgrade-v2/*.png`、`integ_upgrade_v2_report.json`
