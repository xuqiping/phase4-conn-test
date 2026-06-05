# Plan 5: 工作流编辑器

## 项目背景

多Agent智能体平台前端。Plan 3已完成Vue3+TS+Vite+主题系统+布局+路由。Plan 2完成后端Workflow API。

后端API端点：
- GET /api/workflows — 列表
- POST /api/workflows — 创建
- GET /api/workflows/{id} — 详情（含nodes+edges）
- PUT /api/workflows/{id} — 更新
- DELETE /api/workflows/{id} — 删除
- POST /api/workflows/{id}/duplicate — 复制
- GET /api/workflows/{id}/export — 导出JSON
- POST /api/workflows/import — 导入JSON
- GET /api/agents — 获取所有Agent及其skills
- GET /api/skills/{id} — 获取技能详情

前端现有结构在 `agent-platform/frontend/src/`，路由 `/workflow` 和 `/workflow/:id` 当前指向占位视图。

## 目标

实现完整工作流编辑器：Vue Flow画布、组件面板、属性面板。

## 文件结构

```
frontend/src/
├── api/
│   └── workflow.ts                 # Workflow API封装
├── types/
│   └── workflow.ts                 # 类型定义
├── views/
│   ├── WorkflowListView.vue        # 替换占位 - 工作流列表
│   └── WorkflowEditorView.vue      # 替换占位 - 编辑器
├── components/
│   └── workflow/
│       ├── WorkflowCard.vue        # 工作流卡片
│       ├── ComponentPalette.vue    # 左侧组件面板
│       ├── FlowCanvas.vue          # Vue Flow画布
│       ├── PropertyPanel.vue       # 右侧属性面板
│       ├── SkillNode.vue           # 自定义技能节点
│       ├── StartNode.vue           # 开始节点
│       ├── EndNode.vue             # 结束节点
│       └── CanvasToolbar.vue       # 底部工具栏
```

## Task列表

### Task 1: Workflow API + 类型定义
- `api/workflow.ts`：8个API函数（list/create/getDetail/update/delete/duplicate/export/import）
- `types/workflow.ts`：WorkflowNode/WorkflowEdge/Workflow接口

### Task 2: Vue Flow基础配置
- 更新package.json添加 `@vue-flow/core` 和 `@vue-flow/background`

### Task 3: 自定义节点组件
- `StartNode.vue`：绿色圆形"开始"
- `EndNode.vue`：红色圆形"结束"
- `SkillNode.vue`：圆角卡片+技能名+Agent主题色图标

### Task 4: 组件面板（ComponentPalette.vue）
- 左侧280px面板
- 搜索框+按Agent分组的skills列表
- 拖拽到画布（HTML5 drag）
- 流程控制节点区域（开始/结束）

### Task 5: Vue Flow画布（FlowCanvas.vue）
- Vue Flow核心画布+深色网格背景
- 缩放/平移
- 接收拖入节点
- 贝塞尔曲线连线
- 右键菜单删除

### Task 6: 属性面板（PropertyPanel.vue）
- 右侧300px
- 选中节点时显示属性编辑
- 未选中显示提示文字

### Task 7: 工具栏+编辑器集成（CanvasToolbar.vue + WorkflowEditorView.vue）
- 工具栏：缩放/撤销/导出
- WorkflowEditorView：三栏布局+顶部保存/运行按钮
- 新建工作流自动创建Start+End节点
- 保存：画布数据映射到API DTO

### Task 8: 工作流列表页（WorkflowListView.vue）
- 替换占位
- 卡片网格+新建/编辑/复制/删除/导出操作

### Task 9: 最终提交

## 代码规范
- 全部中文注释
- Naive UI组件
- CSS变量适配3套主题
- TypeScript严格类型
- 无placeholder代码
