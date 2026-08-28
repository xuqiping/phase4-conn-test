# IV-B · 画布交互四项（C-1 两段式 / C-2 上游直显 / C-3 手柄热区 / C-6 面板拖宽）

> 规格 §5.1-§5.3、§5.6。同文件集中：CanvasBoard.vue / CanvasNodeBase.vue / ImageNode / VideoNode / PropertyPanel.vue / Lightbox 不动。

## 步骤

### B1 两段式点击（C-1）
- **目标**：单击节点只选中；已选中后点媒体本体才开 Lightbox（严格两段式，决策 6）。
- **动作**（伪代码）：
  ```
  CanvasBoard.onNodeClick（:681-687）:
    删去「type image/video 且 previewUrl → emit preview-media」分支，只保留选中逻辑
  CanvasBoard provide 'canvasMediaPreview' (nodeId) => mediaPreview 置位   // 照 resize provide :523 先例
  CanvasNodeBase / ImageNode / VideoNode:
    媒体区（缩略图/视频）@click.stop:
      if (!props.selected) → 仅通知选中（复用选中链）
      else → inject('canvasMediaPreview')(node.id)
  ```
- **文件**：CanvasBoard.vue、nodes/CanvasNodeBase.vue、nodes/ImageNode.vue、nodes/VideoNode.vue（4 个）
- **依赖**：无
- **验证**：vitest CanvasBoard.test.ts 补用例（未选中点媒体不开/选中后开）；手动 L6 六档（未选中点媒体→选中不开、已选中点媒体→开、点非媒体区→保持、空白点→取消选中、取消后再点媒体→仅选中、Lightbox Esc 关）。

### B2 上游直显+类型配色（C-2）
- **目标**：depth>1 不折叠直接展示；类型徽标配色+图标。
- **动作**（伪代码）：
  ```
  PropertyPanel: 删 showFarUpstream 折叠态（:45-49、:934、watch :936）
  更上游区与直接上游同区渲染（小卡），保留 ·depth 层级号与 50 截断提示
  KIND_BADGE → {label, colorCssVar, icon}；徽标/缩略占位按类型着色（CSS 变量驱动）
  ```
- **文件**：PropertyPanel.vue（1 个）
- **依赖**：无
- **验证**：vitest PropertyPanel.test.ts（上游直显断言）；手动 L7：多级上游全显、切节点不残留、音频/文本占位图标正确。

### B3 resize 手柄热区+四边（C-3）
- **目标**：四角手柄 8px 视觉+20px 热区；四边可拖单轴；连线 Handle 命中优先。
- **动作**（伪代码）：
  ```
  组件 CSS（scoped 或全局覆盖，高特异性）:
    .vue-flow__resize-control.handle { width/height 8px; border 主题色 }
    ::before { position:absolute; inset:-6px; 内容透明; pointer-events:auto }
  CanvasNodeBase: RESIZE_CORNERS 之外新增 4 个 NodeResizeControl variant="Line"
    （top/bottom/left/right），CSS 热区=边缘内侧 8px 窄带，z-index 低于连线 Handle
  实测点不中连线 → fallback: 边线 Line 仅 hover 节点且鼠标距边 <12px 时渐显（规格 §5.3）
  ```
- **文件**：CanvasNodeBase.vue + 组件样式（1-2 个）
- **依赖**：无
- **验证**：手动 L8 全档：未选中无手柄、选中 8 手柄、拖角=宽高同变、拖边=单轴、min 160/64 卡住、拖后刷新尺寸保持（落库链不回归）、连线拖拽仍可从边缘中点发起。**此步人工验证为主**（命中区域手感自动化测不了）。

### B4 面板拖宽（C-6）
- **目标**：属性面板左缘拖拽调宽 260-560，localStorage 持久化。
- **动作**（伪代码）：
  ```
  aside.prop-panel 根加左缘拖拽条 div（6px，hover 高亮，cursor: col-resize）
  mousedown → document mousemove: width = clamp(260, start + dx, 560)，user-select:none
  mouseup → localStorage['canvas.propPanel.width'] = width
  初始化: parseInt 存储值，NaN/越界 → 260
  拖拽期间 pointer-events 阻断画布（拖拽条自身 stopPropagation）
  ```
- **文件**：PropertyPanel.vue（1 个）
- **依赖**：A5 已 commit（同文件）
- **验证**：vitest 补拖宽 clamp 用例；手动 L9：拖动实时变、刷新恢复、非法存储值回落 260、拖拽中画布无框选。

## 联动边界（对照 master L6-L9）

见各步骤验证栏；B1 与既有双击 fitView（CanvasBoard:563-584）不冲突——双击空白/节点行为不动，右键存资产库不动。

## 坑点

- B1 的 `@click.stop` 会吞掉 VueFlow 节点拖拽启动吗——click 在拖拽后不触发（VueFlow 拖拽抑制 click），先手测再定；若冲突改用 pointerdown+位移阈值判「点击 vs 拖拽」。
- B3 库样式覆盖放组件内 `<style>`（非 scoped）或全局——scoped 会因子组件结构选择器不命中。
- B4 拖宽后 B2 上游小卡换行布局需在 260 与 560 两档下目检。

## 完成标准

vitest（CanvasBoard/PropertyPanel）全绿 + vue-tsc 0 + 手动 L6-L9 过 → commit `fix: 修复IV B 画布交互四项——两段式点击/上游直显/手柄热区四边/面板拖宽`。
