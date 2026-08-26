# Chunk C · 画布七小件（2x-1~2x-7）

> 规格 §9.1-§9.7。⚠️ CanvasBoard.vue / CanvasView.vue 工作区有用户 WIP——基于现状续改，提交前逐文件过 diff，不回退不混提。

**✅ 已完成**（2026-08-26，commit 5453e59b，21 文件 +496；后端 2544+前端 806 全绿+vue-tsc 0，见 [开发进度4](../../开发进度/人工测试遗留问题修复III/开发进度4.md)）。**要点**：C1 复验发现 resize 无落库链，补 provide('canvasNodeResized')；C2 默认标记走媒体列表接口（llm-model-defaults 是 llm:config 权限普通用户不可达）；C5 定型收口 updateNodeData 一处；C6 复用 D1 Lightbox；C3 比例独占整行；CanvasBoard 吸收用户 WIP 六轮#2 对账兜底（34 行，先例口径）。

## C1. 复验 2x-1 节点拉伸（已落 S2 管道）

- 动作：逐节点类型（文本/图片/视频/转绘）核对 NodeResizer 挂载与 dimensions→undo→落库链路；个别类型缺挂则补 `:resizable` 与 handle 样式。
- 文件：`components/canvas/CanvasBoard.vue`（+节点子组件如独立文件）
- 验证：手工四类型各拉一次→undo→redo→刷新画布尺寸保持；通过则 2x 文件勾销该项。

## C2. 默认图片模型（2x-2）

- 文件：后端 `system/service/SystemSettingService.java`（键 `media.default.image-model` + getDefaultImageModel 读宽容）、设置页 `views/SettingsView.vue`（模型配置区加「默认图片模型」下拉，候选=启用 IMAGE 模型，来源复用 UserLlmController available-models 接口口径）、前端 `components/canvas/*图片节点*`、`views/ImageGenView.vue`（默认选中+localStorage 记住手选）。
- 伪代码：
  ```
  getDefaultImageModel(): 配置值 ∈ 启用IMAGE模型 ? 值 : 启用IMAGE模型[0] ?: null   -- 读宽容
  图片节点/图片页初始 model = localStorage手选 || getDefaultImageModel()
  ```
- 验证：单测读宽容三态（配置空/配置失效/正常）；手工=管理端配置→画布图片节点默认即该模型。

## C3. 视频节点比例下拉加宽（2x-3，纯 CSS/交互）

- 文件：`components/canvas/CanvasBoard.vue`（视频节点面板）
- 动作：比例 select 改 n-select（teleport 弹层）或 popover 面板，min-width 140px、选项 16:9/9:16/1:1 不截断。
- 验证：节点宽 200 内点开完整可读；键盘可操作（a11y：focus ring、Esc 关）。

## C4. 节点创建副本（2x-4）

- 文件：`components/canvas/CanvasBoard.vue`（节点工具条 + clone 函数）、`views/CanvasView.vue`（落库联动）
- 伪代码：
  ```
  cloneNode(id): newNode={id:newId, pos:+40/+40, data:{…params/prompt 深拷贝, 清 result/status→idle}}
              pushHistory('duplicate'); addNodes; emit structure-changed
  ```
- 边界：媒体节点副本不带生成结果与旧任务 ref；分组节点副本含子节点整体偏移（若现分组结构支持，否则仅平节点并 WARN 注释）。
- 验证：vitest clone 纯函数；手工副本→改参数→提交互不影响。

## C5. 媒体结果节点统一尺寸盒（2x-5）

- 文件：`components/canvas/CanvasBoard.vue`（结果节点样式与生成完成定型逻辑）
- 伪代码：
  ```
  媒体结果容器: 固定 320×320, 媒体 object-fit:contain 居中
  生成完成回调: 若 data.height 未被用户手拉 → 定型盒尺寸（手拉过则尊重）
  文本/输入节点: 维持宽 200 现口径
  ```
- 验证：16:9 与 9:16 各生成一次→两节点同盒；手拉后重新生成不覆盖手拉尺寸。

## C6. 单击媒体节点→Lightbox 预览（2x-6）

- 文件：`components/canvas/CanvasBoard.vue`、`components/canvas/Lightbox.vue`（D 新建，C 先留接口：emit('preview', media) 由父层接；若 D 未开工则 C 内置极简版后续被 D 替换——**顺序建议 C 在 D 后实施**，或合并实施）
- 动作：单击媒体节点（非拖动位移）打开预览；双击保留 vfFitView 聚焦。
- 判定单击 vs 拖动：pointerdown/up 位移阈值 <4px 视为点击（vue-flow drag 误触规避）。
- 验证：单击开预览/Esc 关；拖动不开；双击聚焦不冲突。

## C7. 自动保存状态徽标 + 离开确认（2x-7）

- 文件：`views/CanvasView.vue`（scheduleSave 现有防抖）、顶栏组件
- 伪代码：
  ```
  保存状态机: dirty→saving→saved(HH:mm:ss)/failed(重试按钮)
  beforeunload: dirty 时弹浏览器离开确认
  失败: toast + 手动重存；HML/开发重挂载触发的假 dirty 维持现有丢弃口径
  ```
- 验证：拖节点 2s 后徽标变已保存；断网保存→失败态+重试恢复；关标签页弹确认。

## 验证收口

- `vue-tsc` 0 + vitess 新增单测绿；手工走查表（七项各 1-2 用例）记入测试方案（P3 收尾统一产）。
