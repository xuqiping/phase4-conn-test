# computer-use-skill · Phase 4 运行验证记录

日期：2026-08-26 ｜ 真机：Win10 / 解锁状态 / DPI 100%

## 1. 冒烟运行（Run）
- `scripts/phase4_smoke.mjs`：MCP Client 真 stdio 全链路，**14/14 通过**（报告：docs/测试方案/证据/phase4/phase4_smoke_report.json）
- 启动→tools/list 可用：702ms（目标 ≤2s ✅）

## 2. AC 逐条核对（PRD 23 条）
| AC | 方式 | 结果 |
|---|---|---|
| 001/002/003/008/014/015/017/018/019/020/021 | 自动化 | ✅（vitest + phase4_smoke）|
| 004 | 自动化 | ✅（⚠ MockDriver 语义与真实差一级，记档）|
| 005/006 | 自动化 | ⚠ 仅 MockDriver 路径，产品层 by_name 未接线 → 漂移清单 |
| 007/009/012/013/022/023 | 人工 | ✅（dogfood 证据 6 张 + 记事本；009/013 集成脚本记档后续）|
| 010 | 真机 | ✅ hello123 落入记事本（integ_notepad_after_type.png）|
| 011 | 真机 | ✅ ctrl+a 替换后仅剩 replaced-text（phase4/ac011_after_replace.png）|
| 016 | — | ⚠ 前台不变断言未做 → 漂移清单 |

## 3. 性能评测（vs performance_goals）
| 指标 | 目标 | 实测 | 判定 |
|---|---|---|---|
| screenshot | ≤300ms | 25-42ms | ✅ |
| tree maxDepth=4 | ≤800ms(上限2s) | 1134-1371ms | ⚠ 超目标在上限内（webview 树偏重，原生 App 更快；记档）|
| type | ≤500ms(上限1.5s) | 18-128ms | ✅ |
| 冷启动 tools/list | ≤2s | ~700ms | ✅ |
| PNG 单张 | ≤2MB | 10-31KB | ✅ |
| 并发 | 串行排队 | stdio 天然串行 | ✅ |

## 4. Review（第二个 AI 对抗式审查 + 人复核）
8 维度清单全量产出。**实锤缺陷 4 处已修复并真机回归**（commit 见 git log "Phase4 交叉审查"）：
1. UIA SetValue 写错值（PS 读 value_set）
2. 大写字母 VK+0x20 变数字键盘（"Hello"→"9ello"）→ 真机 "Hello World. 123" ✅
3. 剪贴板通道 PowerShell 命令注入（单引号逃逸）→ Base64 传输，payload 原样入剪贴板 ✅
4. 无 locator type 正则反转（含任意 ASCII 即全走键盘通道，混合中文必炸）+ key/drag/无locator type **无安全闸**（FR-014 红线）→ typeText 自动降级剪贴板 + foregroundProcessName 前台黑名单闸

未处置项 → 开发进度总览「规格漂移待办」7 条闭环管理。

## 5. User-Ops 手册全量验证
功能 1（安装接入）/2（白名单流）/3（操作循环）/4（异常场景）逐项走查通过；"自动重读"措辞已修正为"Agent 会重新读树"。

## 6. 结论
冒烟通过、性能达标（tree 一项在上限内超目标已记档）、审查实锤全修、漂移已记档 —— **放行 Phase 5**。
