# computer-use-skill —— Windows 本机 Computer Use 技能包

**受众 C（技术+使用者）**。让任意智能体（Claude Code / 自研 Agent）能操作 Windows 图形界面：看（截图/元素树）→ 做（点击/输入/滚动/拖拽）→ 验证（再截图）。

## 用户地图
1. **安装**：`npm install && npm run build`
2. **接入 MCP**：宿主 Agent 以 stdio 启动 `node dist/index.js`
3. **装技能**：把 `skill/SKILL.md` 放入 `.claude/skills/computer-use/`
4. **用**：对话里让 Agent"打开 XX 应用做 XX"即可；首次操作某 App 会请求白名单确认

## 技术说明（一句话版）
- **双通道感知**：UIA 元素树（结构化，零激活）+ PrintWindow 截图（自绘/Web 内容兜底）
- **两层执行**：UIA 直控（Invoke/SetValue，不抢前台）→ 不可达时降级 SendInput 前台注入
- **安全三闸**：App 白名单（confirm_app）+ 终端/宿主黑名单 + JSONL 审计脱敏
- **实现**：TypeScript + koffi(FFI) + PowerShell 常驻进程(UIA) + MCP SDK；详见 docs/adr/

## 关键指标（真机实测）
截图 25-44ms（目标≤300ms）｜UIA 树 1.1-1.4s｜记事本/voice-to-text 双靶子全链路通过

## 文档索引
specs/（PRD 19FR/23AC）｜adr/（3 篇）｜api/mcp-tools.md｜测试方案（docs/测试方案/）｜进度（../../开发进度/computer-use-skill/）
