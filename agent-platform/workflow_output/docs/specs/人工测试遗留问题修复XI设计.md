# 规格 · 人工测试遗留问题修复XI（2x 四项：画布右键菜单 + 官方库大卡片 + 叙事角色两级词汇 + 组大节点）

> SDD 特性级规格（Phase 1 产出）。实现须与本文件对齐；冲突时改实现或改本文档（注明原因）。
> 来源：[2x. 资产库和无限画布.md](../../人工测试问题/2x. 资产库和无限画布.md)「未解决」4 项（2026-09-02）。
> 用户拍板见 §3。前序：修复 X 已收（P4 冒烟 27/27，commit `97d6547f`~`fe22581c`，R 系列体感项待人工）。

## 1. 背景与代码现状事实（2026-09-02 三路探查）

### 1a. 画布右键无菜单（2x 未解决①）

| # | 现状事实 | 位置 |
|---|---|---|
| ①右键被吞 | CanvasBoard 根元素 `@contextmenu.prevent`——吞浏览器默认菜单，**无自绘菜单**；节点右键走 vue-flow `@node-context-menu` → CanvasView 开「存入资产库」弹窗（保持不变） | CanvasBoard.vue:10、35；CanvasView.vue:147、2092-2095 |
| ②节点类型清单 | 左侧调色板 7 类：text 文本/image 图片/video 视频/audio 音频/script 脚本/storyboard 分镜/director 导演台——`palette` 常量**写在 CanvasView 内**（右键菜单要用需提取共享，防双源漂移） | CanvasView.vue:2246-2254 |
| ③建节点链现成 | `addNode({type, position?, data})`（position 缺省=随机落点）；拖入链 `project(clientX-left, clientY-top)` 已做屏幕→flow 坐标换算 | CanvasBoard.vue:1148-1177、1078-1100 |
| ④画布操作现成 | 撤销/重做（canUndo/canRedo + undo()/redo()）、Ctrl+V 粘贴（onPaste 链）、✨ 一键整理（dagre）全在 CanvasBoard 工具条/handler | CanvasBoard.vue:144-162 |
| ⑤自绘菜单范式 | workflow FlowCanvas：全屏透明 overlay（click/contextmenu 关）+ 绝对定位菜单（clientX/Y）——现成范式照搬 | FlowCanvas.vue:31-45、162-205 |
| ⑥quick-add 已占双击 | 双击空白/拉线落空白已开 quick-add 搜索弹窗（7 类过滤）——右键菜单=第三入口，互不干扰 | CanvasView.vue:2656-2712 |

### 1b. 画布无官方库入口（2x 未解决②）

| # | 现状事实 | 位置 |
|---|---|---|
| ①官方标记已有 | `asset_projects.published_by_admin`（Boolean，发布时 admin 身份快照）——**无独立官方库实体**，现状=公众池摘要里「官方发布」徽标 | AssetProject.java:74；V88__asset_public_pool.sql |
| ②列表接口无过滤参数 | `GET /api/assets/public-pool` `listPublic(userId, admin)` 返回全已发布项目；无 official 过滤参数 | AssetPublicPoolController.java、AssetPublicPoolService.java:101-113 |
| ③资产浏览/选用链现成 | AssetPicker 公共池页签：选可用项目（usable=OPEN/已批/admin/owner）→ `assetApi.list(projectId,{type,q,size:100})` → 行「选择」→ `assetBridgeApi.resolve(assetId,{canvasId,nodeId})` → emit picked → CanvasView 写节点。**官方库可整链复用** | AssetPicker.vue:215-229、283-337 |
| ④行预览组件现成 | AssetPickerRow 四态缩略（图/视首帧/音/文片段）+悬浮放大+Lightbox z3000（修复X B 轮刚收口）——大卡片行直接复用 | AssetPickerRow.vue、Lightbox.vue |
| ⑤resolve 需节点上下文 | AssetPicker 由属性面板打开（有 props.node）；**官方库从调色板打开无选中节点**——需先建节点再 resolve | AssetPicker.vue:319-337 |
| ⑥类型→节点映射 | 节点→媒体有 NODE_TO_MEDIA；反向（提示词→text/剧本→script/分镜→storyboard/图片→image/视频→video/音频→audio；自定义类型按 mediaCategory TEXT/IMAGE/VIDEO/AUDIO 回落）需新定 | AssetPicker.vue:100 附近；Asset.java CATEGORY_* |
| ⑦官方发布恒可用 | admin 发布强制 `publicAccessMode=OPEN`+publishedByAdmin=true——官方项目 usable 恒 true，无审批分支 | AssetPublicPoolService.java:53-63 |

### 1c. 叙事角色纯平铺（2x 未解决③）

| # | 现状事实 | 位置 |
|---|---|---|
| ①词汇=JSONB 平铺数组 | `asset_projects.narrative_roles` 默认 `["人物","道具","场景","风格","通用"]`；**无层级字段** | AssetProject.java:48-52；V56__asset_projects.sql:26 |
| ②挂载多对多 | `asset_role_links(asset_id, role_key)`；`syncRoleLinks` 全删重插 + 受控词汇校验（key 须在词汇内，含 Bridge 复用） | V57__assets.sql:74-88；AssetService.java:714-736 |
| ③删桶重指派 | `reassignOnRemovedRoles`：被删桶资产归「通用」（FALLBACK_ROLE）；重命名=删+加（挂载资产会迁移，现状语义） | AssetProjectService.java:259-296 |
| ④角色过滤走子查询 | list role 参数单 key `eq(roleKey)` → ids → `in(Asset::getId)`——**一级点击「含子类」需改展开 IN** | AssetService.java:419-427 |
| ⑤一键分镜硬编码 | `getImageCatalog` 取 DEFAULT[0..2]（人物/道具/场景）——两级后需展开子类 | AssetService.java:274-289 |
| ⑥前端消费点 6 处 | VocabEditor（编辑弹窗，roles=string[] 草稿行）、AssetProjectView（:328 资产表单角色多选 options、:56 矩阵 roles）、AssetMatrixFilter（左栏分段）、SaveToAssetDialog（画布存入库角色多选）、AssetListView（卡片 meta 计数）、types/asset.ts（3 处类型） | 各文件 grep 定位；VocabEditor.vue:100、129 |
| ⑦媒体类型已是两级范式 | mediaTypes `[{key,category}]` 两层受控词汇 + VocabEditor 已有两层编辑 UI 先例——叙事角色两级可照此风格 | VocabEditor.vue:46-82 |

### 1d. 组不是可选单位（2x 未解决④，2026-09-02 增补）

| # | 现状事实 | 位置 |
|---|---|---|
| ①组框体穿透 | `.canvas-board__groupbox` 框体 `pointer-events:none`——点组框空白落到 pane=清选中；**无「整组选中」概念**（选中集只装节点 id，组伪 id 永不进）；可点的只有：头部组名（改名）、✕（解散）、左右组端口（拖组边）、组边本身 | CanvasBoard.vue:51、1871、66-112 |
| ②点成员=选成员（已通） | 点组内节点=单选该节点出属性面板——「进入组下该节点」半句现状已支持 | CanvasBoard.vue:1334 |
| ③无整组拖动 | 组框无 drag handler；成员各自拖（拖出框不移出组，包围盒 rAF 纯视觉跟随） | CanvasBoard.vue:1508、586、849 |
| ④剪贴板无组 | `CanvasClipboard` 仅 items/innerEdges/crossEdges/bbox/pasteCount——**无 groups 字段**；粘贴产物恒平节点（修复X「副本不入组员」口径：复制部分成员时成立） | canvasClipboard.ts:25、53、159；CanvasBoard.vue:961 |
| ⑤组边/快照链现成 | 组边伪 id `group:{id}` 独立池；快照 getSnapshot groups 深拷贝+组边单池落库、loadSnapshot 拆回；pushHistory=快照全量——组重建后撤回/落库自动覆盖 | groupEdges.ts:11、84-98；CanvasBoard.vue:1558 |
| ⑥建组入口 | ≥2 选中→批量工具条「设为组」（createGroup 滤死成员/2~50 上限/自动移出旧组） | CanvasView.vue:202、813；CanvasBoard.vue:466 |

## 2. 无外部依赖

三项均站内既有能力补口：无新协议/新第三方/新部署项；XI-2 官方库零新端点（仅 listPublic 加可选参数）；XI-3 一条 Flyway 迁移。

## 3. 用户决策（2026-09-02 拍板）

| # | 问题 | 决策 |
|---|---|---|
| Q1 | 右键菜单内容 | **节点类型+画布操作**——7 类节点（调色板同源）+ 粘贴/撤销/重做/一键整理 |
| Q2 | 官方库交互能力 | **浏览+插入画布**——预览四态复用从库选择行组件，点「选择」把官方资产插进画布（先建节点再 resolve，同 AssetPicker 链） |
| Q3 | 官方库收录范围 | **仅官方发布**（publishedByAdmin=true）——名副其实官方库；全公共池已有「从库选择」公共池页签覆盖 |
| Q4 | 子维度适用范围 | **通用两级词汇**——任意一级角色（含自定义）都可加子类，不硬编码「人物特判」 |
| Q5 | 组整选后能力 | **拖动+复制，不接 Delete**——选中组可整组拖动（成员联动移）、Ctrl+C 整组复制；Delete 在组选中态无动作（防误删一批），删除仍逐个或解散后框选；✕ 解散保留成员（现状不变） |
| Q6 | 「进入组内节点」语义 | **点选分层**——点组框空白=选整组、点成员=选成员（属性面板切过去，现状已通）；不做双击进「节点内部视图」（重，另案） |
| Q7 | 复制带组的判定 | **完全包含即带组**——框选/多选恰好含某组全部成员→该组整体进剪贴板（Ctrl+V 粘出新组），与「点组框选组再 Ctrl+C」同结果，不分入口 |

## 4. 功能需求

### XI-1 画布右键菜单（2x 未解决①）P0

| 子项 | 需求 |
|---|---|
| ①触发与判定 | 画布空白（pane/背景）右键 → 自绘菜单于光标处。根元素 contextmenu handler 判 `event.target` closest：命中 `.vue-flow__node`/`.vue-flow__edge`/`.canvas-board__groupbox`/工具条/组框 → 不开菜单（节点右键=现状「存入资产库」不变；边/组框右键维持无菜单） |
| ②数据单源 | palette 7 类清单从 CanvasView 提取到共享常量（`components/canvas/paletteItems.ts`，含 icon/label/type）——调色板与右键菜单同源，防漂移 |
| ③菜单结构 | 两组：**添加节点**（7 类，icon+label）＋分隔＋**画布操作**（粘贴/撤销/重做/一键整理）。范式照 FlowCanvas：全屏透明 overlay（click/contextmenu 关）+ 绝对定位菜单（clientX/Y，贴边翻转防出屏） |
| ④落点 | 点节点类型 → `addNode({type, position: 右键点 flow 坐标})`（复用拖入链 project 换算）——节点落在右键处，非随机 |
| ⑤画布操作接线 | 粘贴=Ctrl+V 同链（落点=**右键点**——现状粘贴链取鼠标位置，需参数化指定落点；剪贴板空 disabled）；撤销/重做=undo()/redo()（canUndo/canRedo 驱动 disabled）；一键整理=✨ 同 handler。零新增逻辑，纯入口 |
| ⑥关闭与键盘 | 点菜单项/点 overlay 他处/右键他处/Esc 关。Esc 逐层退（Lightbox R7 教训：菜单开着 Esc 只关菜单，stopPropagation 不再清画布多选）；菜单项=button role=menuitem，菜单 role=menu，键盘上下可达 |
| ⑦边界 | 菜单开着再右键空白=挪到新位置（不叠两层）；quick-add 弹窗开着右键无效（modal 遮罩在上，天然不触发）；右键创建节点入 structure-changed 防抖保存链（addNode 既有） |

### XI-2 官方库大卡片（2x 未解决②）P0

| 子项 | 需求 |
|---|---|
| ①入口 | 调色板导演台下方新增「官方库」条目（LibraryOutline 图标；**非节点类型**：不可拖拽、点击开大卡片——样式与节点条目区隔如分隔线） |
| ②大卡片布局 | 新组件 `OfficialLibrary.vue`：n-modal preset card 大尺寸（≈960px/75vh）双栏——左=官方项目列表（封面/名称/描述/资产数/发布时间，卡片式）；右=选中项目资产**按媒体类型分组** sections（组头=项目 mediaTypes 词汇，空组隐藏）——即「按资产库结构展示」 |
| ③官方过滤（Q3） | 后端 `listPublic` 加可选参数 `official=true` → `.eq(publishedByAdmin, true)`；前端 `publicPoolApi.list({official:true})`。白名单 VO 不动 |
| ④资产加载 | 选中项目**单次** `assetApi.list(projectId, {size:100})`（不带 type，全类型）→ 客户端按 mediaType 分组——零 N+1；行=AssetPickerRow 复用（四态预览/悬浮/Lightbox 同修复X 口径，Lightbox z3000 盖 modal 已就绪） |
| ⑤插入画布（Q2） | 行「选择」→ ①按媒体类型反向映射节点类型（提示词→text/剧本→script/分镜→storyboard/图片→image/视频→video/音频→audio；自定义类型按 mediaCategory 回落）②`addNodeAtCenter()`（CanvasBoard 新暴露：视口中心建节点）③`assetBridgeApi.resolve(assetId,{canvasId,nodeId})` ④同 AssetPicker picked 落点链写节点（fileId/previewUrl/文本 content）⑤scheduleSave ⑥关大卡片。官方项目恒 OPEN（1b⑦）无审批分支；resolve 失败 toast 节点回滚（删刚建节点）。反向映射的 mediaCategory 回落依赖 AssetVO 透出 mediaCategory（若现缺则补字段——资产 VO 非白名单摘要，可加） |
| ⑥空态/异常 | 无官方项目→「暂无官方发布项目」；资产加载失败→错误文案+重试；选择 loading/防重入同 picker 口径 |
| ⑦与「从库选择」分工 | 从库选择=选中节点后替换该节点产物（有节点上下文）；官方库=从零新建节点带入官方资产（无节点上下文）。互不替代，User-Ops 双入口都记 |

### XI-3 叙事角色两级词汇（2x 未解决③）P0

| 子项 | 需求 |
|---|---|
| ①词汇 shape | `narrative_roles` JSONB：`["人物",...]` → `[{"key":"人物","children":["老人","青年","孩童"]},...]`；Java `RoleVocab{key,children}` DTO；DEFAULT 五桶 children=[] |
| ②迁移 | 新 `V<next>__asset_role_vocab_two_level.sql`：存量平铺数组 jsonb 变换加壳（`[{key,children:[]}]`）。**读侧双 shape 容错**（parseRoles 逢 string 视为 {key,children:[]}）——防手改库/漏迁移行；编号实现时取最大+1 |
| ③受控校验不变 | `syncRoleLinks` 校验集=词汇扁平全集（父+子）；**扁平全集全局唯一**——子类名不得与任何一级或其他父的子类重名（role_key 无父前缀，重名=筛选/挂载歧义；normalize 拒重名，VocabEditor 前端先拦）；挂一级（不细分）或挂子级均合法；link 表零改动 |
| ④删/改指派两级化 | 删**子类**（父保留）→ 挂该子类 link 改挂**父级**（ensureRoleLink(parent)，不丢维度）；删**一级** → 其子类随删，挂该一级或其子类的 link 归「通用」（fallback 现状口径）；重命名=删+加语义维持 |
| ⑤筛选展开 | list role 过滤改 `.in(roleKey, 展开集)`：一级→[一级+其子类]，子级→[子级]；一键分镜 `getImageCatalog` 实体集=人物/道具/场景 ∪ 各自子类（按项目词汇展开，词汇缺项回落默认） |
| ⑥编辑 UI 两级 | VocabEditor 叙事角色 Tab：一级行（input+删+「+子类」）+ 子类 chips 行内（增/删/改名）；save payload roles=RoleVocab[]；删确认文案含两级计数（「挂该一级或其子类的 N 个资产将归通用」/「挂该子类的 N 个资产将归父级」） |
| ⑦挂角色 UI 两级 | 资产表单（AssetProjectView）与画布存入库（SaveToAssetDialog）角色多选改**分组选项**（n-select group：组=一级，组内=[一级「（不细分）」+ 子类项]）；矩阵左栏（AssetMatrixFilter）一级分段+子级 chip（点一级=发一级 key 后端展开含子类；点子级=仅子级） |
| ⑧展示不动 | AssetCard/AssetDetailDrawer/AssetPickerRow 角色 chips、AssetListView 计数——roleKeys 恒扁平 key 串，展示零改动 |
| ⑨API 兼容 | 前后端同仓同发，narrativeRoles 响应/请求体直接切 RoleVocab[]；旧 string[] 入参由后端容错归一（②） |

### XI-4 组=可选可复制的大节点（2x 未解决④，P0）

| 子项 | 需求 |
|---|---|
| ①组框可点选（Q6 分层） | 组框**框体**可命中（框体区 `pointer-events` 放开；成员节点在其上仍优先接住点击——点组框空白=选中整组）：`groupSelectedId` 新态+框体描边高亮（accent 色）；点画布/Esc/点成员=清组选中（点成员即选该节点，现状交互不变）。标题栏改名/✕ 解散/组端口拖线三入口维持 |
| ②整组拖动（Q5） | 选中组后按住组框拖=**全部成员联动移动**（pointer 拖拽改成员 position 集，松手 emit structure-changed 入防抖保存链）；组框包围盒随动（rAF 既有）；拖动不解散、不改成员关系。**Delete 在组选中态无动作**（Q5：防误删；✕ 解散保留现状） |
| ③复制收集带组（Q7） | `CanvasClipboard` 增 `groups: [{name,color,memberIds}]` 字段。收集规则：选中集**完全包含**某组全部成员→该组进剪贴板（无论框选/组框点选/逐个点满）；部分包含维持现状平节点+跨集组边（修复X 口径不动）。同时收集：成员间内边、成员↔组外节点普通跨边（现状链照旧）+ **原组的组边**（`group:原id`↔外部）记为组级跨边——「保留上下游连线」=普通跨边+组级跨边双通道 |
| ④粘贴重建组 | `pasteSubgraph` 增组重建：新组 id 走 createGroup 生成口径、name 撞名追加序号（同 label 三级去重风格）、color 拷贝、memberIds 重映射到新节点；组级跨边克隆为**新组↔原外部对端**（对端保原——新组作为新广播/聚合一端接回原上下游，即「保留其他上下游连线」）；两含组间的组→组边克隆为新组↔新组；成员→原组的成员级组边重映射到新组。组边全部进 groupEdges 池（伪 id 不进 v-model，修复X 分流口径） |
| ⑤完全独立 | 新组与原组零共享：新组 id/新成员/新边/新组边——对原组重命名、解散、删除均不影响粘贴组（快照深拷贝口径既有） |
| ⑥撤回/落库零新增 | pushHistory=快照全量（groups+两池边）——粘组一步 Ctrl+Z 撤全；防抖保存链落库刷新重现（1d⑤ 现状链，零 schema 变更） |
| ⑦反馈文案 | 整组复制 toast：`已复制 1 个组（N 个节点），Ctrl+V 在鼠标处粘贴`；粘贴后组框即现、bbox 跟随 |
| ⑧连贴错开 | 连续 Ctrl+V 组粘贴同 +32 错开口径（子图粘贴现状）；粘贴落点=鼠标处（组 bbox 中心对齐） |
| ⑨开关治理 | ⛓ keepLinksOnCopy 照旧**只治边不治壳**：开=成员内边+成员跨边+组级跨边全保留；关=**组壳仍重建**（组是结构不是连线）、上述全部边不保留（纯新组零连线）——与修复X「开关关=零边」口径衔接 |

## 5. 非功能需求

- **性能**：右键菜单=纯前端零请求；官方库=打开 1 次项目列表+选中项目 1 次资产列表（size 100 与 picker 同量级），IO 懒加载行复用；词汇展开=每请求 1 次词汇加载（校验已有同款）零额外查询；迁移 O(项目行数) 瞬时；组复制=收集时一次 filter（组量级 <百）、整组拖动=pointermove 批改成员坐标（rAF 节流同包围盒跟随）。
- **安全**：official 过滤服务端强制（公开白名单 VO 不加字段）；resolve/资产列表/文件链鉴权全既有；syncRoleLinks 受控词汇校验防任意 key 注入（扁平全集）；菜单/大卡片纯前端无新攻击面。
- **兼容/回滚**：XI-1 纯增入口（palette 提取共享=行为等价重构）；XI-2 纯增组件+1 可选参数（不传=现状全量）；XI-3 迁移前向修复可逆（逆 jsonb 变换可写 repair 脚本，运维清单落字），读侧容错兜底；XI-4 部分成员复制行为分毫不变（修复X 口径），整组能力纯增量、Delete 不接=零误删风险；四项各自独立 revert。
- **依赖**：零新增 npm/maven 依赖。

## 6. 数据模型与文件结构

- **一条 Flyway 迁移**（XI-3②），其余零 DB 变更。`asset_role_links`/`assets` 零改动。
- 新文件：`components/canvas/paletteItems.ts`（共享清单）、`components/canvas/OfficialLibrary.vue`(+test)、`components/canvas/CanvasContextMenu` 若抽组件（或 CanvasBoard 内联，plan 定）、`db/migration/V<next>__asset_role_vocab_two_level.sql`。
- 改动文件：CanvasBoard.vue（菜单+addNodeAtCenter+组框点选/整组拖动/粘组重建）、CanvasView.vue（palette 引共享/官方库入口/picked 链复用/组复制 toast）、canvasClipboard.ts（groups 字段+组级跨边收集/重映射）、AssetPickerRow 无改、AssetPublicPoolService/Controller（official 参数）、AssetProjectService（RoleVocab/normalize/reassign）、AssetService（role 展开/分镜目录）、ProjectUpdateRequest、types/asset.ts、VocabEditor、AssetProjectView、SaveToAssetDialog、AssetMatrixFilter + 对应 .test.ts。
- 目录骨架不变（全落既有目录）。

## 7. 测试策略

- **前端 vitest**：
  - ①CanvasBoard 增：右键空白开菜单（节点/边/组框/工具条右键不开）、点类型在右键坐标建节点、粘贴/撤销/重做/整理接线+disabled 态、Esc 只关菜单（stopPropagation 断言）、菜单开着再右键挪位。
  - ②OfficialLibrary 新测试：官方项目列表渲染（mock official=true 请求）、资产按 mediaType 分组、选择→建节点+resolve 链 mock、resolve 失败回滚删节点、空态。
  - ③paletteItems 共享：调色板与菜单同源（单一常量两消费方）。
  - ④VocabEditor 增：两级草稿拷贝/子类增删改/归一化去重（父子同名、子类跨父重名）/save payload shape/删确认两级计数文案。
  - ⑤AssetMatrixFilter 增：一级点击 emit 一级 key、子级点击 emit 子级 key、两级渲染。
  - ⑥AssetProjectView/SaveToAssetDialog 增：分组 options 渲染、挂父级/挂子级提交。
  - ⑦CanvasBoard/canvasClipboard 增（XI-4）：组框点选=groupSelectedId+高亮、点成员仍单选成员、Esc 清组选中；整组拖动成员联动+落库；**部分成员复制不进组**（修复X 回归锚）；完全包含收集 groups 字段+原组组边；粘组重建（新 id/name 去重/memberIds 重映射/组级跨边连原对端/组边进 groupEdges 池/v-model 零伪 id）；两含组组→组边克隆；成员→原组边重映射新组；Ctrl+Z 一步撤含组；连贴 +32；Delete 组选中态无动作。
  - ⑧全量回归：AssetPicker/AssetPickerRow、复制粘贴 VII/IX/X、矩阵、上游面板既有用例不破。
- **后端测试**：listPublic official 过滤（真/假/admin 视角）；parseRoles 双 shape 容错；normalizeRoles 两级校验（空/重名/深限）；reassign 删子类归父、删一级归通用；list role 展开查（一级含子类）；getImageCatalog 子类展开。
- **人工测试标记**（P3 产 `docs/测试方案/人工测试遗留问题修复XI测试方案.md` S 系列后勾销 2x 三项）：
  - ①右键：空白右键出菜单位置贴合/节点右键仍存入库/右键建节点落点准/菜单手感（贴边翻转、Esc）。
  - ②官方库：入口开大卡片/官方项目浏览四态预览/选择插入画布成正确类型节点+产物可看/大图灯箱盖卡片。
  - ③两级：编辑分类加子类保存/矩阵左栏两级筛选（一级含子类/子级单选）/删子类资产归父级/删一级归通用/一键分镜含子类资产/画布存入库分组选角色。
  - ④组大节点：点组框空白选中整组（高亮）/点成员照常选成员/整组拖动成员联动落库/完全包含 Ctrl+C→Ctrl+V 粘出新组且连回原上下游（真实生成消费=广播/聚合走组边）/部分成员复制仍平节点/Delete 组选中态无动作/解散 ✕ 不变/刷新重现。

## 8. 边界与不做

- 右键**不做**节点/边/组框右键菜单改造（节点右键维持「存入资产库」；边/组框无菜单）；**不做**多级子菜单（平铺两组够用）；quick-add 双击弹窗不并入右键。
- 官方库**不做**复选批量插入（单选即刻插入）；**不做**官方库内检索跨项目聚合（项目选中后单项目关键词沿用 picker 参数 q）；**不做**复制官方资产到本地项目（公众池页已有复制链，另案不重复）；**不做**非官方公共项目收录（Q3）。
- 两级**不做**三级及更深（children 恒一层）；**不做**子类跨父拖拽移位（删了重加）；**不做**已删一级的子类保留（随删）；矩阵**不做**子级独立列（左栏 chip 筛选即达意）。
- 组大节点**不做** Delete 删整组（Q5 拍板防误删）；**不做**双击进「节点内部视图」（Q6，另案）；**不做**组折叠收起成单卡片（大节点=展开容器选成单位，非收起）；**不做**组框右键菜单（§8 XI-1 口径维持）；批量工具条（对齐/整理）不感知组选中态（仍按节点多选）；嵌套组（组内组）维持不支持（createGroup 自动移出旧组现状）。
- 不改 `asset_role_links` 表结构；不改公众池白名单 VO；不改 AssetPicker 既有交互。

## 9. 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-09-02 | 建立规格（XI-1~3，Q1~Q4 拍板；三路代码探查：画布菜单链、公众池/resolve 链、词汇/挂载/筛选链） | 2x 未解决 3 项设计 |
| 2026-09-02 | 增补 XI-4 组大节点（Q5~Q7 拍板；组框/剪贴板/组边链探查） | 2x 未解决④（用户追加） |

## 10. 术语表

| 术语 | 大白话 | 案例 |
|---|---|---|
| 上下文菜单 | 点右键弹出来的那列操作按钮 | 画布空白右键→「图片/视频/…/粘贴/撤销」 |
| pane（画布底板） | 画布里没放节点的空白背景区 | 右键底板才弹菜单，右键节点不弹 |
| 受控词汇 | 项目里「允许填哪些值」的白名单，只能从里面挑 | 叙事角色只能在 人物/道具/… 里选，不能自由输入 |
| 两级词汇 | 白名单的值自己还能再挂一层小类 | 人物→老人/青年/孩童 |
| JSONB | PostgreSQL 里能整段存 JSON 还能查询的字段类型 | narrative_roles 存整串角色清单 |
| 官方发布 | 管理员发到公众池的项目打的官方标记 | 官方库只收这类 |
| resolve（引用解析） | 把资产「绑定到节点」的后端登记+取内容动作 | 选官方图片→新图片节点带出图 |
| 反向映射 | 从资产类型倒推该建哪种节点 | 音频资产→建音频节点 |
| 删桶重指派 | 删分类时，挂着它的资产自动搬到别处防孤儿 | 删「老人」子类→其资产归父级「人物」 |
| 大节点（组） | 组整体当一个单位选、拖、拷——像一个大号节点 | 点组框选全组，Ctrl+V 粘出新组连回原上下游 |
| 完全包含即带组 | 只要选中集把某组成员全圈进去了，复制就带上组壳 | 框选盖住整组→粘贴出来是组，不是一盘散节点 |
| 组级跨边 | 复制组时，原组对外的那几条线记下来，粘出来接回原对端 | 原组→下游 X：新组→X（X 还是原来那个） |
