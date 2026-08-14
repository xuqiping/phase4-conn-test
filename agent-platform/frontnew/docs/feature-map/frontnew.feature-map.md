# Feature Map — frontnew

> 功能→代码速查 + 技术原理大白话。改代码前先查这里。

## 文件清单与作用

| 文件 | 作用 |
|------|------|
| index.html | 入口；内联脚本首帧前恢复主题（防 FOUC） |
| src/main.ts | 装配 pinia/router/主题 css/global.scss |
| src/App.vue | n-config-provider 绑定 naiveOverrides |
| src/router/index.ts | 5 页路由，挂 MainLayout 壳，默认 /canvas |
| src/stores/theme.ts | 主题 state + setTheme/init（URL 参数 > localStorage > 默认） |
| src/theme/tokens/*.css | 4 主题 CSS 变量（视觉真相源）+ base.css 共享骨架 |
| src/theme/naive.ts | CSS_MIRROR 镜像 → naive overrides；与 css 双源，测试防漂移 |
| src/theme/themes.ts | 主题元信息（切换器展示） |
| src/theme/useTheme.ts | 组合式函数：current/naiveOverrides/setTheme |
| src/styles/global.scss | reset/滚动条/焦点环/reduced-motion/3 个 keyframes |
| src/mocks/*.ts | 全部假数据 + genStressNodes 压测生成器 |
| src/layouts/MainLayout.vue | 侧栏+顶栏+内容区骨架（flex 高度链） |
| src/components/app/* | Sidebar（折叠）、TopBar（面包屑+切换器）、ThemeSwitcher |
| src/components/canvas/NodeCardBase.vue | 节点卡片基座（全部状态/类型视觉在此） |
| src/components/canvas/nodes/*.vue | 6 类节点：只写内容区，皮肤全靠基座 |
| src/components/canvas/CanvasBoard.vue | VueFlow 容器：nodeTypes 注册、选中跟踪、小地图颜色同步 |
| src/components/canvas/CanvasToolbar.vue | 缩放/适应视图/节点计数 |
| src/components/canvas/edges/FlowEdge.vue | 贝塞尔连线 + 选中中点删除钮 |
| src/components/chat/* | MessageList/MessageBubble/ChatInput（流式 mock） |
| src/components/agent/AgentCard.vue | 大厅卡片 |
| src/components/workflow/WorkflowStatusTag.vue | 状态徽标 |
| src/views/*.vue | 5 页面装配 |
| tests/*.spec.ts | 39 例：主题/双源一致/节点/ mocks/路由 |

## 关键调用链

- **主题切换**：ThemeSwitcher → themeStore.setTheme → html[data-theme] 换 attr → tokens 变量全体换装；同时 App.vue 的 naiveOverrides computed 换 naive 皮肤；CanvasBoard watch current → 重算小地图色
- **画布选中**：VueFlow node-click → CanvasBoard.selectedIds → emit select-nodes → CanvasView 右面板渲染；边选中色 = 边上挂的 `edge-kind-{type}` class + CSS
- **流式对话**：ChatInput emit send → ChatView push 用户消息 + 空 AI 消息 → setInterval 逐字填充（reduced-motion 直出）→ ChatInput.finish() 解禁输入

## 技术原理大白话注解

| 技术 | 一句话原理 | 大白话 | 踩坑批注 |
|------|-----------|--------|---------|
| CSS 变量主题 | 换 html 属性=换一组变量值 | 像换灯罩：灯（组件）不动，光（颜色）全变 | 组件写死色值=灯罩白换；tokens.spec.ts 会抓 |
| backdrop-filter | 模糊元素背后的内容 | 磨砂玻璃贴纸 | 100 个节点都贴=GPU 冒烟；只给侧栏/弹层用 |
| conic-gradient+rotate | 渐变静止、伪元素旋转 | 转的是彩灯转盘不是颜色本身 | 直接动渐变角度要 @property，旧浏览器不认 |
| vue-flow only-render-visible | 只渲染可视区节点 | 舞台只点亮看得见的演员 | 100 节点 60fps 的功臣 |
| markRaw 包组件 | 不让 Vue 给组件对象套响应式 | 别把菜谱当菜炒 | 不包会有性能警告/莫名 bug |
| getComputedStyle 读变量 | JS 问浏览器「现在这变量是啥色」 | SVG 属性不认 var()，只能查好再喂 | 主题切换后要 requestAnimationFrame 重读 |
| index.html 内联脚本 | JS 挂载前先设好 data-theme | 进门前先开对灯，不闪白 | 异步恢复=FOUC 闪一下 |

## 无数据库

纯前端 mock，无 Flyway/表。
