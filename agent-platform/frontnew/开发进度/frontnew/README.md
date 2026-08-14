# frontnew — 功能 README

## 用户地图（给谁用/什么场景/什么效益）

| 谁 | 场景 | 效益 |
|----|------|------|
| 产品决策者（本项目用户） | 对比 4 套主题在 5 个核心页面的实际观感，选定合回 frontend 的目标风格 | 不用改一行 frontend 代码就能「试穿」4 套皮肤 |
| 前端开发者 | 合回时按 architecture.md §6 拆包：tokens → 节点样式 → 布局 → naive overrides | 每步独立可验证，回滚容易 |
| 设计走查者 | 按 docs/测试方案 逐条过联动用例与视觉矩阵 | 防止「截图好看、交互翻车」 |

**一句话**：frontnew 是 agent-platform 前端的「样式实验室」——纯前端 mock，无后端依赖，`pnpm dev` 即开即看。

## 技术说明

### 是什么

Vue 3 + TS + Vite 5 + Naive UI 的独立样式验证工程，位于 `agent-platform/frontnew`，与 `frontend` 零代码共享、零运行时依赖。所有数据来自 `src/mocks/` 类型化常量。

### 核心机制

1. **主题系统**（src/theme/）：
   - 4 套主题的 CSS 变量文件（tokens/*.css）挂 `:root[data-theme='x']`；
   - 切换 = 改 `<html data-theme>` + localStorage 持久化；index.html 内联脚本首帧前恢复，防闪烁；
   - Naive UI 通过 `theme/naive.ts` 的 CSS_MIRROR 镜像吃同源色值；tests/tokens.spec.ts 逐键比对 css 与镜像，防双源漂移。

2. **画布**（src/components/canvas/）：
   - NodeCardBase 统一 6 类节点的头部/内容/底部/连接桩/状态类名；类型色经 `--node-kind` CSS 变量注入；
   - 状态视觉按主题分流：T1/T3 conic 流光描边、T2 状态点呼吸、T4 时间轴播放头；
   - `?nodes=N` 压测开关（≤500）；`only-render-visible-elements` 保 60fps。

3. **皮肤纪律**：组件禁止硬编码色值，一律 `var(--token)`——4 主题一键切换与合回 frontend 的前提。

### 怎么跑

```bash
cd agent-platform/frontnew
pnpm install
pnpm dev        # http://localhost:5173
pnpm test       # 39 例
pnpm build      # 产物 dist/
```

### 规格与计划

- specs/PRD.md — 需求边界；specs/design_system.md — 4 主题与节点卡片视觉真相源
- plans/frontnew-redesign.plan.md — 8 chunk 计划与坑点表
- docs/测试方案/frontnew测试方案.md — 人工走查清单
