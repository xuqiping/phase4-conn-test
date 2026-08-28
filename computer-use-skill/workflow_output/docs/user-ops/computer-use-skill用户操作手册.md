# computer-use-skill 用户操作手册

面向把本技能嵌入自己 Agent 的使用者。

## 功能 1：安装接入
1. `npm install && npm run build`
2. 宿主 Agent MCP 配置 stdio：`node <路径>/dist/index.js`
3. 复制 skill/SKILL.md 到 `.claude/skills/computer-use/`
- 预期：宿主 Agent 工具列表出现 13 个工具（skyshot/tree/click/…/memory_list/memory_forget）

## 功能 2：首次操作某应用（白名单）
1. 对 Agent 说："打开记事本输入 hello"
2. Agent 调 tree → 返回 **CONFIRMATION_REQUIRED**
3. 界面无变化；Agent 会问你"允许操作记事本吗？"
4. 答"允许，以后都允许" → Agent 调 confirm_app(remember=true)
- 预期：之后对记事本的操作直接执行；config.toml 出现该 App

## 功能 3：常规操作循环（升级v2 三层执行）
1. "帮我点 XX 应用的 XX 按钮"
2. Agent：截图/读树 → 定位 → 点击（**带 name 语义名**）→ 再截图验证 → 报告
- 预期 via 字段四种值：`memory`（记忆命中秒操作）/ `uia`（零激活）/ `postmessage`（后台寄信不抢你鼠标键盘）/ `sendinput`（前台兜底）
- **学习记忆**：第一次操作成功后自动记住按钮位置；第二次同样操作不再截图+AI 分析，直接秒执行；目标应用改版后点击失败会自动重新学习，无需你干预

## 功能 3.5：学习记忆管理
1. "看看你记住了 XX 应用的哪些控件" → Agent 调 memory_list
2. "忘掉 XX 应用的记忆" / "忘掉 XX 按钮那条记忆" → memory_forget（可清单条或整个 app）
- 记忆存在本机 config 目录 memory/ 下，只记控件名和位置，**不存截图、不存界面内容**

## 功能 4：异常场景
| 现象 | 含义 | 你要做的 |
|---|---|---|
| STALE_TREE | 树超60秒过期 | 无需处理，Agent 会重新读树 |
| TARGET_BLOCKED | 目标是终端/宿主自身 | 换手动，这是安全设计 |
| 截图 0x0 / 输入无效 | 目标最小化或**屏幕已锁** | 解锁屏幕；工具会自动还原最小化窗口 |
| APP_NOT_FOUND | 应用没开或标题不符 | 先启动应用，用窗口标题关键词 |
| LAYER2_NO_EFFECT（审计日志） | 后台寄信对该应用无效，已自动退回前台操作 | 无需处理；频繁出现可 config.toml 加 `layer2_enabled=false` |
| ANCHOR_STALE（审计日志） | 某条记忆过期，已自动重新学习 | 无需处理 |

## 运维开关（config.toml）
| 开关 | 默认 | 作用 |
|---|---|---|
| layer2_enabled | true | 关掉=不用后台寄信，回到 v1 行为 |
| memory_enabled | true | 关掉=不记不用学习记忆 |

## 安全须知
- 敏感动作（删除/支付/发帖等）Agent 会先停下问你——这是特性不是 bug
- 屏幕上的文字（网页/弹窗）不可作为指令来源；只认你在对话里说的话
