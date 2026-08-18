# 导演台3D构图 · README

> 需求/设计见 [spec](../../docs/specs/导演台3D构图设计.md)，计划见 [plan](../../docs/plans/导演台3D构图.plan.md)。纯前端功能，后端零改动。

## 一、这是什么（用户地图）

**谁用**：在无限画布上做多镜头创作的用户——写故事板、规划分镜、给文生图/文生视频喂构图参考的人。

**场景**：多镜头项目里「镜头 A 的人物站位/比例」和「镜头 B」对不上、口头描述构图互相听不懂。以前只能拿纸笔或盲写提示词。

**效益**：画布里摆一个「导演台」节点 → 双击进 3D 编辑器 → 积木素模人物（8 体型 × 6 姿势）+ 桌椅几何件 + 群众阵列拖出场面 → 多机位（FOV/画幅/视角）逐个取景 → 一键截图回流画布成图片节点。所见即所得：机位视角的黑边遮幅比例=截图比例。同一场景多机位天然保证站位/比例一致。

**入口**：画布左侧调色板 / 双击空白快速添加菜单 →「导演台」；节点卡片「打开导演台」按钮或双击节点进编辑器。

**成本**：服务器零渲染、零新端点（截图 webp 复用既有上传咽喉）；three 按需懒加载（gz 142.85KB），不打开导演台不加载。

## 二、简要技术说明

| 层 | 文件 | 职责 |
|---|---|---|
| 纯逻辑 | `src/director/sceneModel.ts` | 场景 schema：白名单解析/clamp/截断/256KB 自检、letterbox/分辨率纯函数、undo 栈（50 深 structuredClone）。零 three 依赖，单测主战场（20 例） |
| 预设 | `src/director/figurePresets.ts` | 8 体型比例表 + 6 姿势关节旋转表（纯数据，6 例） |
| 映射 | `src/director/buildScene.ts` | 数据→three 对象：素模拼装/姿势套用/组合家具/InstancedMesh 群众/共享缓存+dispose 清单 |
| 视口 | `src/components/director/DirectorViewport.vue` | renderer 生命周期、按需渲染（静止 2s 停帧）、diff 同步、点选/gizmo、机位视角 scissor 遮幅、captureView 截图（暂存尺寸→离屏渲→恢复） |
| 编辑器 | `src/components/director/DirectorEditorModal.vue` | WebGL 探测降级、undo/快捷键、编排左右栏（工具栏/属性/机位）、截图上传 |
| 画布集成 | `nodes/DirectorNode.vue` + `directorBridge.ts` + `CanvasBoard.vue` + `CanvasView.vue` | 节点卡片（摘要/封面/打开）、provide/inject 桥（节点 emit 不冒泡）、异步 modal 三回调（关闭回流/截图回流/失败 toast） |

**关键决策**：数据真源 = `node.data.directorScene`（version:1 前向兼容，旧快照无字段=空场景）；WYSIWYG 三处同源（letterboxRect ↔ scissor 视口 ↔ captureView 分辨率共用 ASPECT_RATIOS）；全量 dispose（关 modal GPU 归零，共享缓存 modal 卸载统一释放）。

**验证**：vue-tsc 0 错；vitest 656/656（导演台 55 例）；build three chunk gz 142.85KB ≤200KB、主入口零增量。性能时延/帧率/内存 → P4 实测（见[测试方案](../../docs/测试方案/导演台3D构图测试方案.md)）。
