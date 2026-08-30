# computer-use-skill Feature Map（功能-代码速查）

| 功能 | FR | 代码位置 | 技术原理大白话 |
|---|---|---|---|
| 窗口截图 | 001 | src/driver/win/capture.ts | 让窗口自己把自己画到一张画布上（PrintWindow），再存成 PNG——比"拍屏幕"准，还能拍到被遮挡的窗口 |
| FFI 基建 | — | src/driver/win/ffi.ts | koffi 库把 Windows 系统函数（user32/gdi32）"翻译"给 Node 调用；类型必须先注册再声明函数 |
| 窗口枚举/查找 | 003 | src/driver/win/window.ts | 遍历所有顶层窗口，按标题/进程名匹配目标 |
| UIA 元素树 | 002/003 | uia.ps1 + uia.ts | PowerShell 常驻进程跑 .NET UIAutomation，把界面控件树转 JSON 吐给 Node（ stdin/stdout 各一行一条） |
| 树快照/定位 | 003 | src/driver/snapshot.ts | 树读回来存 60 秒，动作用 index 引用；过期报 STALE_TREE 让你重读 |
| UIA 直控 | 004/005/006/012 | uiaActions.ts | 能 Invoke 的按钮直接"程序化按下"，不用抢鼠标键盘 |
| SendInput 前台层 | 004~011 | input.ts + keymap.ts | 模拟真人：移鼠标/按键/滚轮；中文走剪贴板粘贴（键盘模拟不了中文输入法） |
| 11 个 MCP 工具 | 017 | src/tools/index.ts | Agent 看到的工具面板；zod 校验参数 |
| 白名单 | 013 | safety/whitelist.ts | 首次操作某 App 要用户点头（CONFIRMATION_REQUIRED），remember=true 写入 config.toml |
| 黑名单 | 014 | safety/blacklist.ts | 终端类应用 + 宿主 Agent 自身永远拒绝（防"AI 自动化 AI"失控） |
| 审计+脱敏 | 015/016 | safety/audit.ts | 每个动作记 JSONL 日志（10MB×3 轮转）；密码类字段打码，截图绝不入日志 |
| SKILL.md | 018 | skill/SKILL.md | 教 Agent 怎么用这套工具 + 安全规范（提示注入防御等） |
| 层2 后台执行 | 100~103（升级v2） | src/driver/win/postmsg.ts | 给窗口"寄信"（PostMessage 投递鼠标/键盘消息），窗口在后台也收到——对老 Win32 软件真实有效；Chromium 系忽略，靠截图验证发现无效后自动退回前台 SendInput |
| 截图验证 | 102（升级v2） | src/driver/win/verify.ts | 执行前后各拍一张，逐像素比差异（阈值0.2%防光标闪烁误报）——判定层2是否真的生效 |
| 学习记忆 | 110~113（升级v2） | src/memory/anchors.ts | 给控件办"记忆卡"（语义名+比例坐标+成功方式），操作成功自动记；下次点名直接秒执行；连续2次失效自动作废重学 |

## 验证锚点
test/（29 用例：AC-005/006 断的是 MockDriver 路径；AC-001~004 真机断言在 capture.integ，需 CU_INTEG=1）｜scripts/integ_notepad_run.mjs + phase4_smoke.mjs（真机）｜docs/测试方案/证据/（截图，含 phase4/）
