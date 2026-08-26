# Chunk D · 上游节点面板 + Lightbox + @光标锚定（2x-8/2x-9）

> 规格 §9.8/§9.6（Lightbox 共用）。⚠️ MentionTextarea.vue / mentionLogic.ts 有用户 WIP。

## D1. Lightbox.vue 统一预览组件（新建）

- 文件：`frontend/src/components/canvas/Lightbox.vue`（新）
- 伪代码：
  ```
  props: {open, kind:'image'|'video', src, poster?}
  全屏遮罩(focus-trap + Esc 关 + 点遮罩关)；图片: 滚轮缩放 0.2–5x(wheel deltaY)、拖拽平移、双击复位；
  视频: 原生 controls autoplay 关；a11y: role=dialog aria-modal、工具条缩放按钮(±/复位)键盘可达
  ```
- 验证：vitest 缩放边界/复位/Esc；手工滚轮+拖拽流畅度。

## D2. 上游节点面板（PropertyPanel「上游」区）

- 文件：`components/canvas/PropertyPanel.vue`、`components/canvas/upstream.ts`（新，纯函数）、`CanvasBoard.vue`（选中节点+边数据传入）
- 伪代码：
  ```
  collectUpstream(nodeId, nodes, edges): BFS 沿入边递归 → [{node, depth}]
  分层渲染: depth=1 直接上游大卡（媒体预览+提示词两行截断+节点名+类型角标）
            depth>1 折叠区「更上游 N」小卡（缩略图+名）
  卡片交互: hover 媒体 scale(1.5) 预览(纯文本提示词，禁 v-html)；单击媒体→Lightbox；
            双击媒体卡→emit('mention-insert', {nodeId}) 冒泡至画布层
  空态: 无上游显示「无上游节点」
  ```
- 性能：一次 O(V+E) 遍历，memoize 按选中节点 id；深链上游>50 节点截断+提示（防巨型图卡死）。
- 验证：vitest collectUpstream（菱形依赖去重/环安全/深度分层）；手工走查布局不挤（卡间距 12、分层缩进）。

## D3. 双击上游卡 → @ 引用插入输入框末尾

- 文件：`CanvasBoard.vue`（接收 mention-insert → 调 MentionTextarea 暴露方法）、`components/canvas/MentionTextarea.vue`（WIP 区，暴露 appendMention(ref)）
- 伪代码：
  ```
  appendMention(ref): 序列化=mentionLogic 既有格式；追加到 value 末尾（末尾无空格补一空格）；
                      focus + 光标落引用后；输入框禁用态(生成中)时忽略并 toast 提示
  ```
- 验证：vitest appendMention 末尾拼接/禁用态；手工双击→输入框聚焦含 @引用。

## D4. @ 候选弹层光标锚定（2x-9）

- 文件：`components/canvas/MentionTextarea.vue`（WIP 区）、`components/canvas/caret.ts`（新，镜像 div 坐标计算）
- 伪代码：
  ```
  onMentionStart(query): pos = caretPosition(textarea, atIndex)   -- 镜像 div 复刻样式量宽高
    弹层定位: 锚点=pos，上方优先；越上界→翻转下方；左右夹在容器内；宽 240、最多 8 行滚动
    选中/键盘导航/中文 IME 组合态逻辑不动（mentionLogic 现口径，WIP 中改动保留）
  ```
- 坑：镜像 div 须复刻 font/line-height/padding/word-break 与 wrap，否则坐标漂移；resize/zoom 时重算。
- 验证：vitest caretPosition（多行/中文/末行）；手工：多行中间 @、视口边缘翻转、IME 不闪。

## 验证收口

- vue-tsc 0；vitest（caret/upstream/appendMention/Lightbox）绿；七项手工走查表并入测试方案。
