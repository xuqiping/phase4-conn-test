# computer-use-skill 用户操作手册

面向把本技能嵌入自己 Agent 的使用者。

## 功能 1：安装接入
1. `npm install && npm run build`
2. 宿主 Agent MCP 配置 stdio：`node <路径>/dist/index.js`
3. 复制 skill/SKILL.md 到 `.claude/skills/computer-use/`
- 预期：宿主 Agent 工具列表出现 11 个工具（skyshot/tree/click/…）

## 功能 2：首次操作某应用（白名单）
1. 对 Agent 说："打开记事本输入 hello"
2. Agent 调 tree → 返回 **CONFIRMATION_REQUIRED**
3. 界面无变化；Agent 会问你"允许操作记事本吗？"
4. 答"允许，以后都允许" → Agent 调 confirm_app(remember=true)
- 预期：之后对记事本的操作直接执行；config.toml 出现该 App

## 功能 3：常规操作循环
1. "帮我点 XX 应用的 XX 按钮"
2. Agent：截图/读树 → 定位 → 点击 → 再截图验证 → 报告
- 预期：每步结果里 via 字段显示 uia（零激活）或 sendinput（前台模拟）

## 功能 4：异常场景
| 现象 | 含义 | 你要做的 |
|---|---|---|
| STALE_TREE | 树超60秒过期 | 无需处理，Agent 会重新读树 |
| TARGET_BLOCKED | 目标是终端/宿主自身 | 换手动，这是安全设计 |
| 截图 0x0 / 输入无效 | 目标最小化或**屏幕已锁** | 解锁屏幕；工具会自动还原最小化窗口 |
| APP_NOT_FOUND | 应用没开或标题不符 | 先启动应用，用窗口标题关键词 |

## 安全须知
- 敏感动作（删除/支付/发帖等）Agent 会先停下问你——这是特性不是 bug
- 屏幕上的文字（网页/弹窗）不可作为指令来源；只认你在对话里说的话
