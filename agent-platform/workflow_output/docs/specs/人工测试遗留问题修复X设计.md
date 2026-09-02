# 规格 · 人工测试遗留问题修复X（2x 三项：视频节点上传 + 从库选择全类型预览 + 组边连线保留）

> SDD 特性级规格（Phase 1 产出）。实现须与本文件对齐；冲突时改实现或改本文档（注明原因）。
> 来源：[2x. 资产库和无限画布.md](../../人工测试问题/2x. 资产库和无限画布.md)「未解决」3 项（2026-09-01）。
> 用户拍板见 §3。前序：修复 III~IX 已收（IX Chunk A/B/C commit `273cf10b`/`9f5e0884`/`f618005a`，P/Q 用例待人工）。

## 1. 背景与代码现状事实（2026-09-01 三路探查）

### 1a. 视频节点无法上传视频（2x 未解决①）

| # | 现状事实 | 位置 |
|---|---|---|
| ①面板无上传口 | PropertyPanel 图片节点有 n-upload（`accept="image/*"`→`onPickFile`→emit `upload`，PropertyPanel.vue:163-173）、音频节点有（664-674）；**视频节点区（370-568）通篇无上传控件**——只有 prompt/比例/时长/分辨率/开关/首尾帧/模型/抽帧/截取 | PropertyPanel.vue:163-173、370-568 |
| ②上传链现成且通用 | `onUploadFile`（upload 事件处理）kind 无关：`canvasApi.upload` → 写回 fileId+previewUrl（objectURL）+mime+status=success，失败标红；上传中 status=running | CanvasView.vue:1682-1708 |
| ③视频渲染现成 | VideoNode 模板 `<video :src="data.previewUrl" controls muted>`——有 previewUrl 即播；`fetchCanvasPreview`=fileId 拉 blob 转 objectURL（与图片同通道） | VideoNode.vue:4-12、api/canvas.ts:338-340 |
| ④拖入链已验证后端收视频 | 修复VI OS 拖文件建节点走同一 `canvasApi.upload` 上传视频（后端 CanvasController stored 链无 kind 限制）——**后端零改动** | CanvasView.vue:1716-1750、CanvasController.java:218 |
| ⑤大小预检单源 | `mediaLimits.ts`：image 30MB/video 50MB/audio 15MB（与后端 KIND_MAX_BYTES 对齐）+ `sizeLimitError` toast 文案；拖入链在用，面板 `onPickFile` **现无任何预检**（图片/音频裸传靠后端拦） | mediaLimits.ts:9-27、PropertyPanel.vue:1251-1256 |
| ⑥产物消费链按 fileId 门控 | 下游 @引用、C11 抽帧/C12 截取按钮均以 `data.fileId` 存在为前提——上传写入 fileId 后这些能力自然可用 | PropertyPanel.vue:364、499+、CanvasView.vue:1756+ |

### 1b. 从库选择无预览（2x 未解决②）

| # | 现状事实 | 位置 |
|---|---|---|
| ①行=纯文字+即选 | AssetPicker 行=名称+meta+「选择」按钮；**整行 `@click="onPick(a)"` 直接 resolve 选中**——无缩略图、无预览、点哪都是选 | AssetPicker.vue:64-81、327-345 |
| ②后端 list 已返 fileId | `assembleRoles` 单次 IN 批装配当前版本 fileId（C2 就为卡片缩略设计，防 N+1；size≤100 单页）；AssetVO.fileId 可选字段——**前端没消费是纯缺口，后端零改动**。AssetFilePicker 注释「list 不返 fileId」已过时 | AssetService.java:740-758、types/asset.ts:205 |
| ③懒加载组合式现成 | `useLazyFilePreview`（IntersectionObserver 进视口才拉 + 模块级 LRU objectURL 缓存 + 托管释放）——AssetCard/ReferencePreview/AssetPickerMediaPreview 三处同款 | composables/useLazyFilePreview.ts |
| ④悬浮放大组件现成（仅图） | `HoverPreviewImage`：NPopover 停留 300ms 弹大图（previewSrc 要求传**已加载 objectURL**=悬浮零请求）+ 原图尺寸行；图片专用，视频无分支 | media/HoverPreviewImage.vue |
| ⑤灯箱现成 | 画布 `Lightbox.vue`：kind image（滚轮 0.2-5x 缩放/拖拽/双击复位）/video（原生 controls），Teleport body，`z-index: 2000` | canvas/Lightbox.vue |
| ⑥层级坑 | AssetPicker 是 n-modal（teleport body）；naive-ui modal 栈默认 2000+——Lightbox 2000 在 modal 内打开可能被盖，需抬高 | Lightbox.vue:167 |
| ⑦全类型范式齐 | 图片/视频缩略块（AssetPickerMediaPreview 72×48，视频 preload=metadata 首帧）、音频行内嵌原生 audio 条（ReferencePreview：controls+preload=none+@click.stop）、文本类 textPreview 片段（AssetCard S16：TEXT 类列表态 ≤120 字封面，无片段回落类型字标）——四态各有先例 | AssetPickerMediaPreview.vue、ReferencePreview.vue:26-33、AssetCard.vue:28-34 |
| ⑧上游面板同交互先例 | 「悬浮放大（video hover CSS scale 1.6）+点击开 Lightbox」= 修复III D2/D3 已定交互语言，用户诉求即把它带进选择弹窗 | PropertyPanel.vue:1598-1650 |

### 1c. 连线保留模式下组边不保留（2x 未解决③）

| # | 现状事实 | 位置 |
|---|---|---|
| ①组边独立集合 | 组边（端点含伪 id `group:{id}`）存 `groupEdges` ref（不进 VueFlow v-model），SVG 覆盖层渲染；快照 getSnapshot 合并/save，loadSnapshot 经 splitSnapshotEdges 拆回 | CanvasBoard.vue:303、629+、1535-1560 |
| ②复制链三道闸 | ⅰ `buildCopySet` 只收 `edges.value`（普通边）——**groupEdges 根本没传进**（CanvasBoard.vue:932）；ⅱ crossEdges 过滤显式排除组端点（canvasClipboard.ts:77）；ⅲ 伪 id 不在选中节点集，「恰一端在集」判false 也进不了 | canvasClipboard.ts:70-79 |
| ③粘贴重映射闸 | `remapCrossEdges` alive 校验只认节点 id 集——组伪 id 恒不在 → 即便收集了也会被当悬挂丢 | canvasClipboard.ts:157-180 |
| ④粘贴落点闸 | `pasteSubgraph` 跨集边一律 push `edges.value`（VueFlow 池）——组边必须进 groupEdges 池否则渲染层丢失 | CanvasBoard.vue:993-996 |
| ⑤副本链两道闸 | `cloneEdgesForDuplicate` 显式滤组端点（nodeClone.ts:54）+ `appendEdges` 兜底再滤一次（CanvasBoard.vue:1705） | nodeClone.ts:50-61 |
| ⑥开关治理现状 | ⛓ keepLinksOnCopy 开关已统一管粘贴+副本（IX-2）——组边纳入后**自动同开关治理**，无需新开关 | canvasPrefs.ts、CanvasBoard.vue:993、CanvasView.vue:2643 |
| ⑦组边生命周期 | 解散组/删节点级联 prune groupEdges（存活集合内组边恒指向活组）；几何由 watch(groupEdges) rAF 派生——新增组边自动跟随 | CanvasBoard.vue:500-531、627 |
| ⑧撤回链已覆盖 | pushHistory 快照=getSnapshot（两池合并）、undo 走 loadSnapshot（拆分）——组边进粘贴/副本批次后一步撤回性质自动成立 | CanvasBoard.vue:388、1535+ |
| ⑨组边语义 | 外部→组=广播全员、组→外部=聚合全员产物（resolveEdgesForFlow 展开）；组成员关系只存组侧（memberIds），副本/粘贴体恒「平节点」不入组 | groupEdges.ts:35-78、nodeClone.ts 模块注释 |

## 2. 无外部依赖

三项均纯站内既有能力补口：无新协议/新第三方/新部署项。

## 3. 用户决策（2026-09-01 拍板）

| # | 问题 | 决策 |
|---|---|---|
| Q1 | 视频节点「上传视频」语义 | **上传即节点产物**——与图片节点同款：fileId+预览写入节点，可被下游 @引用/抽帧/截取；重传=覆盖。复用现有 upload 事件链，零后端改动（不做「生成参考视频」通道——那需签名 URL 基建，另案） |
| Q2 | 从库选择预览范围 | **全类型预览**——图片：悬浮放大+点击大图；视频：首帧悬浮放大+点击播放；音频：行内嵌播放条；文本类（提示词/剧本/分镜）：textPreview 片段块 |
| Q3 | 组边保留成什么 | **新节点连原组**——组端点保原 group id，新节点以「外部节点」身份与组相连（广播/聚成语义不变），不入组员（平节点口径维持），改动最小 |

## 4. 功能需求

### X-1 视频节点上传（2x 未解决①）P0

| 子项 | 需求 |
|---|---|
| ①面板上传按钮 | PropertyPanel 视频节点区**顶部**（提示词字段之前，与图片节点同位）加 n-upload：`accept="video/*"`、`:show-file-list="false"`、按钮「上传视频」（CloudUploadOutline 图标，同图片节点 163-173 范式含 loading 态）→ `onPickFile` → emit 既有 `upload` 事件（**零新增事件**） |
| ②前端预检（顺手统一） | `onPickFile` 增 `sizeLimitError(kindFromMime(file.type), file.size, file.name)` 预检：超限 toast 拒不发请求（video 50MB 大文件尤其必要——传完才被后端 400 体验最差）；**三 kind 统一**（图片 30MB/音频 15MB 一并补上，单源 mediaLimits）；MIME 不明（accept 漏网）不动交后端 |
| ③上传链零改动 | `onUploadFile` 现状已 kind 无关（fileId+previewUrl+mime+status=success/failed+running）——VideoNode `<video>` 直接渲染；后端 `canvasApi.upload` 拖入链已在收视频 |
| ④语义（Q1） | 上传即产物：下游 @引用、C11 抽帧/C12 截取按钮随 fileId 出现自然可用；**重传覆盖**（同图片节点口径，无确认弹窗）；与生成链并存——上传后节点=有产物起点，之后「生成」走任务链覆盖 fileId/previewUrl（现状语义不变） |
| ⑤文案 | 按钮下 hint 一行：`本地视频 ≤50MB，上传后作为节点素材`（KIND_LIMIT_LABEL 单源引常量，防 VD 式文案漂移） |

### X-2 从库选择全类型预览（2x 未解决②）P0

| 子项 | 需求 |
|---|---|
| ①行布局改版 | 每行：左 **72×48 缩略块**（复用/参照 AssetPickerMediaPreview）+ 名称/meta 主区 + 行尾「选择」按钮（现状保留）。缩略块渲染四态：图片/视频/音频/文本类 |
| ②图片行 | `useLazyFilePreview(fileId)`（list VO 已带 fileId）→ thumb img；**悬浮**：HoverPreviewImage 包 thumb（300ms 防抖弹大图+尺寸行，零请求口径）；**点击 thumb** → Lightbox（kind=image，滚轮缩放） |
| ③视频行 | thumb=`<video preload="metadata" muted playsinline>` 首帧 + ▶ 角标（上游面板同款）；**悬浮**：HoverPreviewImage 扩展 `kind` 可选 prop（默认 'image' 向后兼容）——NPopover 内嵌 `<video preload="metadata">` 首帧放大（无尺寸行）；**点击 thumb** → Lightbox（kind=video 播放）。文件名不改（5 处 import 不连锁，注释标注双态） |
| ④音频行（Q2） | 缩略块=类型字标「音」+ **行内嵌原生 `<audio controls preload="none">`**（meta 区下方或缩略块右侧，`@click.stop` 防触发行交互——ReferencePreview 口径）；无悬浮/无灯箱 |
| ⑤文本类行（Q2） | 缩略块=textPreview 片段（≤120 字，AssetCard S16 范式；溢出省略）；无片段回落类型字标（提/剧/分）；无悬浮/无灯箱（片段即预览，全文选择后落节点可见） |
| ⑥交互口径（用户原话） | **点击缩略图=预览**（图大图/视频播放）；**点击行尾「选择」=真实选择**（onPick→resolve→emit picked→关弹窗，现状链不动）；**行其余区域点击不动作**（去掉整行 `@click="onPick"`——防误选，用户拍板「点击右侧选择则真实选择」）。缩略块为 `<button type="button">` role 可达（Enter 开预览，aria-label「预览 {资产名}」） |
| ⑦层级修正 | Lightbox `z-index: 2000 → 3000`（n-modal 弹窗内可盖；AnnotateOverlay/FocusEditOverlay 2000 不同屏共存不冲突）；HoverPreviewImage 走 NPopover 自带 teleport 层级不受影响 |
| ⑧失败/降级 | 预览失败（blob 拉挂）→ 缩略块回落类型字标（AssetPickerMediaPreview `failed` 范式）；ARCHIVED 行半透明现状保留；选择按钮 loading/防重入现状保留 |
| ⑨性能口径 | 列表 size=100 现状不动；IO 门控=滚动到视口才拉 blob，模块级 LRU 缓存跨开弹窗复用——不做「打开即全量拉流」 |

### X-3 组边连线保留（2x 未解决③）P0

| 子项 | 需求 |
|---|---|
| ①复制收集 | `buildCopySet(nodes, edges, selectedIds)` 调用点改传 `[...edges.value, ...groupEdges.value]`；crossEdges 过滤改：**组端点边**（恰一端为伪 id、另一端在选中集）纳入收集（伪 id 恒算「集外」端）；innerEdges **维持排除**组端点（诱导边=纯节点间，VIII-1⑧ 口径不变）。注释同步：组边「不带出」口径修订为「诱导边不带出；跨集组边随 ⛓ 开关保留（修复X）」 |
| ②重映射 | `remapCrossEdges` 签名 +`aliveGroupIds: Set<string>`：组伪 id 端点**保原不动**（组非选中集成员、id 稳定）；alive 校验分流——组端点查 aliveGroupIds（组存活），节点端点照旧查 aliveNodeIds；组已解散（复制后）→ 丢边不产断边（悬挂防护同口径） |
| ③粘贴落点分流 | `pasteSubgraph`：alive 集=现节点 id 集 ∪ 现组 id 集（`groups.value.map(g=>g.id)`）；remapCrossEdges 产物按端点分流——含组伪 id → `groupEdges.value.push`，否则 `edges.value.push`（现状）。几何 watch(groupEdges) 自动跟随、undo 快照合并链已覆盖（⑧现状事实），零额外工作 |
| ④副本克隆 | `cloneEdgesForDuplicate` 去 `isGroupEndpoint` 过滤——组边同样单侧重映射（节点端换 newId、组端保原）；alive 无需校验（getEdges() 实时取，prune 链保证集合内组边指活组） |
| ⑤appendEdges 分流 | 过滤改分流：组端点边进 `groupEdges.value`（浅拷贝**剥会话 class**——loadSnapshot 1537 行同口径，防选中态烤进新组边），普通边照旧进 `edges.value`；`scheduleStoreReconcile` 仅普通边非空时需要（组边不进 v-model，addEdge 1685-1695 同口径）；pushHistory('edge') 一次含两池（快照合并已覆盖） |
| ⑥语义（Q3 拍板） | 新节点=组的**外部对端**：节点'→组=广播进组消费、组→节点'=聚合产物作源——resolveEdgesForFlow 展开零改动自然生效；**新节点不入组员**（平节点口径维持，nodeClone 模块注释已有）；组→组边/组自环边两端均伪 id 恒不在选中集 → 天然不收集（无需特判） |
| ⑦开关治理 | ⛓ keepLinksOnCopy 继续统一管：开=粘贴/副本保留连线**含组边**；关=零边**含组边**——用户口径「连线保留模式下」组边也要保留，无需新开关 |
| ⑧文档批注 | CanvasBoard.vue:932/nodeClone.ts:54/CanvasBoard.vue:1705 三处「组边不带出」注释随实现修订（引用本规格），防后来者按旧注释回滚 |

## 5. 非功能需求

- **性能**：面板上传=零新增请求链；picker 预览=IO 门控懒加载（视口内才拉 blob，LRU 复用，100 行无感）；组边收集=复制时一次 filter 增量（组边量级 <百）；Lightbox z-index 抬层零运行时成本。
- **安全**：无新端点、无新参数面；上传走既有 `canvasApi.upload` 鉴权链，50MB 前端预检+后端 KIND_MAX_BYTES 双闸；picker 预览走既有 `/api/files/{id}` blob 通道（鉴权同现状）。
- **兼容/回滚**：X-1 纯增按钮+预检（图片/音频上传行为仅多一道本地 toast 预检）；X-2 行交互变化=需求本身（整行即选→按钮选），AssetPicker 仅画布一处使用；X-3 组边进保留链后开关关=回今日行为分毫不差；快照 schema 零变更（组边本就在快照内）；全部纯代码 revert 即回现状。
- **依赖**：零新增 npm/maven 依赖。

## 6. 数据模型

- 三项**零数据库变更、零迁移**：AssetVO.fileId 列表态既有；画布快照组边结构既有；上传走既有 stored file 链。
- file_structure：零新目录（改动全落既有文件：PropertyPanel/AssetPicker/Lightbox/HoverPreviewImage/canvasClipboard/nodeClone/CanvasBoard/CanvasView + 对应 .test.ts）。

## 7. 测试策略

- **前端 vitest**：
  - ①canvasClipboard.test 增：组端点 cross 收集两向（节点→组/组→节点各一）、组→组不收、组自环不收、innerEdges 仍零组边；remapCrossEdges：组端点保原伪 id+节点端换新、aliveGroupIds 缺组（解散）丢边、组活+节点活产出。
  - ②nodeClone.test 增：组边克隆（节点端换 newId 组端保原）、开关关副本零边（含组边）。
  - ③CanvasBoard.test 增：appendEdges 组边分流进 groupEdges 池；pasteSubgraph 组边落 groupEdges+一步撤回含组边；buildCopySet 传入含 groupEdges 全量（932 改动点回归）。
  - ④AssetPicker 新测试文件：四类型行渲染（图 img/视 video+▶/音 audio 条/文 textPreview 片段+回落字标）、点击 thumb **不**触发 picked、点击「选择」触发 picked（resolve 链 mock）、行空白点击不动作、audio @click.stop、ARCHIVED 半透明。
  - ⑤PropertyPanel 增：视频区渲染上传按钮（accept=video/*）、超限文件 toast 拒不 emit upload（三 kind 预检各一例）。
  - ⑥HoverPreviewImage 增：kind=video 渲染 video 分支/默认 image 回归。
  - ⑦全量回归：VII 复制粘贴、IX 开关双口径、VIII 组边建/删/解散、上游面板、自动保存、undo/redo 既有用例不破。
- **人工测试标记**（过 `docs/测试方案/人工测试遗留问题修复X测试方案.md` R 系列后勾销 2x 三项）：
  - ①视频上传：传 mp4 显预览可播、50MB 边界过/超限 toast 拒、上传后抽帧/截取可用、重传覆盖、下游 @引用取到。
  - ②从库选择：四类型行各渲染正确；图悬浮放大+点击大图（滚轮缩放）；视频悬浮首帧+点击播放；音频行内播；文本片段；点行空白不误选；Lightbox 在 modal 之上层级正确；ARCHIVED 半透明。
  - ③组边保留：节点连组（两向）开 ⛓ 复制粘贴→新节点连原组（广播/聚合消费链真实生效——组下游生成取到新节点产物）；创建副本同口径；关 ⛓ 两处零组边；复制后解散组再粘贴→不产断边；Ctrl+Z 一步撤含组边；组包围盒随新组边出现连线。
- **回归重点**：普通边跨集保留（IX）不回归；组边不进 VueFlow v-model（分流正确性）；图片/音频面板上传多预检不破现有用例。

## 8. 边界与不做

- 视频节点**不做**生成参考视频通道（Q1 拍板产物口径；签名 URL 基建另案）；**不做**上传进度条（按钮 loading 现状够）；重传**不做**确认弹窗（与图片节点对齐）。
- picker **不做**文本类全文灯箱/悬浮（片段即预览）；**不做**音频悬浮放大（无画面语义）；**不做**公共池/本地来源预览差异（fileId 同返同链）；行点击**不做**二级详情抽屉（选择弹窗保持轻）。
- 组边**不做**副本入组为成员（Q3 拍板外部对端）；**不做**组→组边保留（选择集只含节点天然不触发）；**不做**组边单独开关（随 ⛓ 一个开关）。
- HoverPreviewImage **不改文件名**（kind prop 向后兼容；重命名连锁 5 import 无收益）。
- Lightbox z-index 只抬不改关闭/缩放逻辑。

## 9. 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-09-01 | 建立规格（X-1~3，Q1~Q3 拍板；三路代码探查：面板/上传链、picker/预览组件群、组边六排除点） | 2x 未解决 3 项设计 |

## 10. 术语表

| 术语 | 大白话 | 案例 |
|---|---|---|
| 产物口径上传 | 传上去的文件就是这节点「做出来的东西」，下游能直接用 | 上传 mp4 后别的节点 @它取素材、可抽帧 |
| IO 门控懒加载 | 滚到看得见的地方才去服务器拉图，看不见的不拉 | 100 行资产列表只拉屏幕里那十几张 |
| LRU 缓存 | 「最近用过的留着，太久的丢掉」的省内存策略 | 关了弹窗再开，刚才看过的缩略图秒出 |
| 伪 id（组端点） | 组没有真节点 id，用 `group:xxx` 冒充端点让边能连到组框上 | 外部节点→`group:g1`=连到 g1 组整体 |
| 广播/聚合 | 进组一条线=组里每人都收到；出组一条线=组里每人的产物都算 | 组→视频节点：全体成员产物汇成输入 |
| 单侧重映射 | 克隆/粘贴时只换「自己这头」的 id，对面不动 | 副本 A'→组 g：A' 是新的，g 还是那个 g |
| 平节点口径 | 副本/粘贴体永远是普通节点，不自动入组 | 复制组员，副本站组外，靠组边连回 |
| z-index 层级 | 网页元素谁盖谁的数字，大的在上 | Lightbox 抬到 3000 才能盖住选择弹窗 |
