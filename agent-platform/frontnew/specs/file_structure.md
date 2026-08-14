# 目录结构规划 — frontnew

> Context Engineering 核心产物：告诉 AI 每个目录干嘛，新文件该放哪。

```
agent-platform/frontnew/
├── specs/                      # 规格文档（唯一真相源，先读再写码）
│   ├── PRD.md                  # 需求/边界/非功能/安全
│   ├── design_system.md        # 4 主题 + 节点卡片视觉规格
│   ├── architecture.md         # 技术栈/分层/合回路径
│   ├── testing_strategy.md     # 测试策略 + 人工走查清单
│   └── file_structure.md       # 本文件
├── index.html
├── package.json                # 依赖对齐 frontend 版本区间
├── vite.config.ts
├── tsconfig.json
├── public/
│   └── favicon.svg
└── src/
    ├── main.ts                 # 入口：挂载 pinia/router/naive，注入主题
    ├── App.vue                 # 根组件：n-config-provider 绑定当前主题 overrides
    ├── router/
    │   └── index.ts            # 5 核心页路由，无守卫，默认进 /canvas
    ├── stores/
    │   └── theme.ts            # 当前主题名 + 切换 action + localStorage
    ├── theme/                  # 【核心资产】主题系统
    │   ├── tokens/
    │   │   ├── base.css        # 共享变量：圆角/时长/间距/字号/缓动
    │   │   ├── neon-pulse.css  # T1 霓虹 AI
    │   │   ├── calm-slate.css  # T2 冷静极简
    │   │   ├── hybrid-glow.css # T3 混合
    │   │   └── cineon.css      # T4 影像工坊
    │   ├── naive.ts            # 4 主题 → Naive UI themeOverrides 映射
    │   ├── themes.ts           # 主题元信息（名/预览色/描述）
    │   └── useTheme.ts         # 组合式函数：切换/持久化/恢复
    ├── styles/
    │   └── global.scss         # 全局重置、滚动条、selection、工具类
    ├── mocks/                  # 全部假数据，类型化常量
    │   ├── canvas.ts           # 演示工作流（6 类型×各状态，≥10 节点）
    │   ├── chat.ts             # 2 会话消息流
    │   ├── agents.ts           # 12 智能体
    │   ├── workflows.ts        # 10 工作流
    │   └── types.ts            # mock 数据的 TS 类型
    ├── layouts/
    │   └── MainLayout.vue      # 侧边栏+顶栏+内容区骨架
    ├── components/
    │   ├── app/
    │   │   ├── Sidebar.vue         # 导航：折叠态/激活态
    │   │   ├── TopBar.vue          # 面包屑+主题切换器+头像+铃铛
    │   │   └── ThemeSwitcher.vue   # 4 主题预览色点下拉
    │   ├── canvas/
    │   │   ├── CanvasBoard.vue     # Vue Flow 容器：缩放/平移/小地图/工具栏
    │   │   ├── NodeCardBase.vue    # 节点卡片基座：头部/内容槽/底部/连接桩/状态
    │   │   ├── CanvasToolbar.vue   # 缩放控制+适应视图+布局切换（mock）
    │   │   ├── nodes/
    │   │   │   ├── TextNode.vue    # 文本：摘要 2 行
    │   │   │   ├── ImageNode.vue   # 图像：16:9 缩略图占位
    │   │   │   ├── VideoNode.vue   # 视频：1.85:1 缩略图+时长徽标
    │   │   │   ├── AudioNode.vue   # 音频：波形占位
    │   │   │   ├── ScriptNode.vue  # 脚本：行数徽标
    │   │   │   └── StoryboardNode.vue # 分镜：镜头数+2×2 图阵
    │   │   └── edges/
    │   │       └── FlowEdge.vue    # 贝塞尔连线+选中删除钮
    │   ├── chat/
    │   │   ├── MessageList.vue     # 消息流（用户右/AI 左）
    │   │   ├── MessageBubble.vue   # 气泡+引用卡+代码块样式
    │   │   └── ChatInput.vue       # 输入区+发送钮+附件占位
    │   ├── agent/
    │   │   └── AgentCard.vue       # 大厅卡片：头像/标签/用量
    │   └── workflow/
    │       └── WorkflowStatusTag.vue # 状态徽标（草稿/运行/完成/失败）
    └── views/
        ├── CanvasView.vue      # 无限画布（默认首页）
        ├── ChatView.vue        # 对话
        ├── AgentHallView.vue   # 智能体大厅
        ├── WorkflowListView.vue# 工作流列表
        └── NotFoundView.vue    # 404 占位
```

## 放置规则

- 新颜色/阴影/圆角 → 只许进 `theme/tokens/`，组件内出现硬编码色值 = 违规；
- 新假数据 → `mocks/`，禁止散在组件里；
- 新页面 → `views/` + 注册路由 + 进测试策略 M1 走查矩阵；
- 通用小组件先问「是否可复用」：是 → `components/` 对应域目录；否 → 留在使用它的页面/组件内联。

## 骨架状态

当前已建空目录骨架（见下），文件在 Phase 3 实现时填充。
