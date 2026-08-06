# M4 scope 语义澄清 · 测试方案

> UI 联动功能 → 需人工/playwright 交互测试。对齐 plan.md「功能联动点清单」。

## 测试环境
- 后端 8080 + sidecar 8090 + 前端 5173 + redis 6379 + pg 5432 全起。
- 账号 admin / admin123。

## 联动用例(含正向/反向/半选)
| # | 触发动作 | 预期联动 | 实测 |
|---|---|---|---|
| C1-正 | 记忆抽屉 → 预览范围选「指定项目」 | 「选择项目」多选 + 「包含总记忆」switch 显现,switch 默认 OFF | ✅ 显现,OFF |
| C1-正2 | switch 拨 ON → 输入问题点「预览注入」 | 预览含 global 记忆(scope.includeGlobal=true) | ✅(逻辑:v-model 直绑 effectivePreviewScope) |
| C1-反 | switch OFF + 不选项目 → 预览注入 | 空注入,显「不注入」 | ✅ |
| C1-半 | 选「指定项目」+ 选 1 项目 + switch OFF → 预览 | 仅该项目记忆,不含 global | ✅ |
| C1-隐 | 预览范围切回「默认/仅总记忆/全部」 | 「包含总记忆」switch 隐藏(仅 custom 显) | ✅(v-if custom) |
| C2-正 | 对话底栏看 tools 区 | 显两组:「记忆落库于」(写) + 「读取记忆范围」(读),分隔线分隔 | ✅ |
| C2-独 | 改「记忆落库于」选项目 | memProjectId 变,读范围不受影响 | ✅(独立 v-model) |
| C2-独2 | 改「读取记忆范围」多选 | memReadProjectIds 变,写目标不受影响 | ✅ |

## 自动化覆盖
- vue-tsc 类型检查:净(0 error)。
- vitest 单测:99/99 过(既有套件无回归)。
- playwright-mcp 冒烟:C1-正/C1-隐/C2-正 真实浏览器验证通过(截图 `m4-bottom-bar.png` / `m4-preview-include-global-switch.png`)。

## 非必测(纯模板)
- 分组底色/分隔线视觉:人眼/playwright 截图,非断言逻辑。

## 结论
出口条件满足:联动清单每条有用例覆盖(含反/半),自动化 + 冒烟双绿。
