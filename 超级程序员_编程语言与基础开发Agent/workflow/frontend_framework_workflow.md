# Frontend Framework Workflow

## Purpose

基于前端编程语言与框架知识体系，为用户提供前端技术选型、学习路径、框架对比、跨端方案或具体前端开发问题的深度解答。覆盖JavaScript/TypeScript、Vue/React/Angular三大框架及uni-app、小程序等跨端方案。

## Prerequisites

- 用户已明确前端技术场景或问题
- 知识库文件 `01_编程语言与核心技能.md` 及子目录文件可访问

## Steps

### Step 1: 识别用户前端需求场景

**Goal**: 明确用户的前端需求类型和约束条件
**Completion criterion**: 已确定场景标签和至少2项关键约束

1. 读取用户消息，提取以下信息：
   - 场景类型：技术选型 / 学习路径 / 问题排查 / 跨端方案 / 性能优化
   - 目标框架（如用户已指定）：Vue / React / Angular / uni-app / 原生小程序
   - 应用类型：中后台管理系统 / 大型Web应用 / 企业级系统 / 跨端App / 微信小程序
   - 目标市场：中国市场 / 海外市场 / 全栈统一
   - 团队规模与经验：前端团队人数、是否有全栈开发者
   - 特殊要求：SSR/SSG需求、严格类型安全、规范合规（金融/政府）

2. 对照知识库中的选型决策矩阵初步判断候选方案：
   - 中国市场+中后台管理 → Vue全家桶
   - 海外市场+大型Web应用 → React全家桶
   - 企业级大型系统+严格规范 → Angular
   - SSR/SSG全栈 → Next.js(React) / Nuxt.js(Vue)
   - 一套代码覆盖App+小程序+H5 → uni-app
   - 只做微信生态 → 原生小程序(Skyline引擎)

3. 如有信息缺失，向用户追问不超过2个澄清问题。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/01_编程语言与核心技能.md > 二、前端编程语言与框架 > 选型决策]

### Step 2: 输出前端技术方案

**Goal**: 产出针对性的前端技术建议或学习路径
**Completion criterion**: 输出包含推荐框架/技术栈、版本选择、关键学习节点、生态工具链

根据Step 1确定的场景，按以下分支处理：

**分支A — 技术选型**：
1. 从候选方案中筛选最优框架。
2. 输出选型决策矩阵，对比维度至少覆盖：
   - 生态成熟度（第三方组件库、工具链、社区支持）
   - 性能（运行时效率、包体积、渲染性能——Vue 3.5响应式内存降56%、Vapor Mode编译时优化、React 19 RSC稳定化等）
   - 学习曲线（入门难度、文档质量、TypeScript支持度）
   - 团队匹配度（现有技术栈、开发者经验、招聘难度）
   - 长期趋势（框架发展方向、大厂采用情况、关键趋势如React Compiler自动记忆化、Angular Signals全面稳定、Skyline引擎替代WebView等）
3. 给出最终推荐并附决策理由。

**分支B — 学习路径**：
1. 输出阶段性学习计划（基础→框架→工程化→实战→性能优化）。
2. 每个阶段附：
   - 关键知识点（与知识库L3模块对齐）
   - 推荐搭配（如Vue: Vue 3 + Pinia + Element Plus；React: Next.js + Zustand + Shadcn UI；Angular: Angular Material + Nx + RxJS）
   - 实践项目建议
   - 验证标准
3. 标注关键趋势点（React 19/ Vue 3.5/ Angular 19/ Skyline/ TypeScript标配）。

**分支C — 跨端方案**：
1. 评估uni-app vs 原生小程序 vs Flutter vs React Native 的适用性。
2. 输出跨端架构建议（代码共享比例、平台差异处理、性能权衡）。
3. 给出各端（iOS/Android/小程序/H5）的技术栈映射。

**分支D — 问题排查**：
1. 定位问题到具体框架层面（Vue响应式/ React渲染/ Angular变更检测）。
2. 提取知识库中的故障模式与解决方案。
3. 输出排查步骤和修复代码示例。

将结果保存到 `output/frontend_advice.md` 或直接在对话中呈现。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/01_编程语言与核心技能.md > 二、前端编程语言与框架 > 全景地图]
- [参考: Agents知识库/0_超级编程行业知识库/01_编程语言与核心技能.md > 二、前端编程语言与框架 > 关键趋势]

### Step 3: 验证与交付

**Goal**: 确保输出内容准确、可操作、与知识库一致
**Completion criterion**: 已通过 checklist 逐项核对

1. 读取 `checklist/frontend_framework_workflow_checklist.md`。
2. 逐项核对输出是否覆盖要求的知识点。
3. 确认所有关键论断均能在知识库中找到支撑。
4. 如有遗漏或偏差，补充修正。
5. 向用户交付最终答案。

## Post-Workflow

1. 记录完成状态到 `task/current_task.md`。
2. 如需深入某一框架生态，在当前 Agent 内继续执行对应工作流。
