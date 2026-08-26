# File Structure · Computer Use Skill

> Phase 1 产物 · 2026-08-24 · Phase 4 校订（2026-08-26，与代码现状对齐）· 给 AI 的目录地图

```
computer-use-skill/
├── src/
│   ├── index.ts                # stdio 启动 + registerTools
│   ├── tools/index.ts          # 11 个 MCP 工具集中注册（schema+双闸+两层调度）
│   ├── safety/                 # config(白名单配置)/whitelist/blacklist/audit(JSONL+脱敏)
│   └── driver/
│       ├── types.ts            # DriverError(9 错误码)/UiNode/ActionResult
│       ├── PlatformDriver.ts   # 平台抽象接口（⚠ Phase 4 审查：tools 层未接线，见漂移清单）
│       ├── mock.ts             # Mock 驱动（单测用）
│       ├── snapshot.ts         # 树快照 60s TTL + index 定位
│       └── win/                # Windows 实现（FFI 只允许出现在这里）
│           ├── ffi.ts          # koffi 加载 user32/gdi32/gdiplus（类型先注册后声明）
│           ├── capture.ts      # PrintWindow→GDI+ PNG（最小化自动还原）
│           ├── window.ts       # 窗口枚举/查找 + foregroundProcessName（前台闸用）
│           ├── uia.ts          # PS 常驻进程管理 + tree/act 调用
│           ├── uia.ps1         # PowerShell UIA 工作进程（UTF-8 BOM 必需）
│           ├── uiaActions.ts   # UIA 直控动作 + NeedsFallback 判定
│           ├── input.ts        # SendInput 鼠标键盘/剪贴板中文通道/组合键/滚动/拖拽
│           └── keymap.ts       # 组合键解析（ctrl+shift+a → VK 序列）
├── skill/SKILL.md              # Claude Skill 包装层（工具速查+安全规范）
├── scripts/                    # check_all 质量门 + integ_notepad_run/phase4_smoke 真机脚本
├── test/                       # vitest（29 用例；capture.integ 需 CU_INTEG=1）
└── workflow_output/            # 文档产物（specs/adr/api/测试方案证据/开发进度/项目规范约束）
```

规则：FFI/Windows API 调用**只准出现**在 `src/driver/win/`；`src/tools` 与 `src/safety` 必须可 mock 纯测。
