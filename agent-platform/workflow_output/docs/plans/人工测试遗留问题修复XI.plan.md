# 计划 · 人工测试遗留问题修复XI（A 右键菜单 / B 官方库 / C 两级词汇 / D 组大节点 / E 文档收尾）

> Phase 2 产出，源自 [specs/人工测试遗留问题修复XI设计.md](../specs/人工测试遗留问题修复XI设计.md)（Q1~Q7 拍板已入规格 §3）。
> 硬闸门：本文件过审并获明确许可前不写实现码。

## 〇、总览与依赖序

```
A1 palette 共享常量提取 → A2 CanvasBoard 右键菜单（判定/两组菜单项/落点/接线） → A3 A轮收口
B1 后端 official 参数+AssetVO.mediaCategory → B2 OfficialLibrary.vue 大卡片 → B3 CanvasView 插入链接线 → B4 B轮收口
C1 后端词汇地基（RoleVocab+迁移+双容错+normalize+受控校验） → C2 后端行为（reassign 两级+筛选展开+分镜展开） → C3 前端类型+VocabEditor 两级 → C4 前端消费点（表单/存入库/矩阵左栏） → C5 C轮收口
D1 组框点选+高亮 → D2 整组拖动 → D3 剪贴板 groups 收集+组级跨边（纯函数） → D4 粘贴重建组+组边克隆 → D5 D轮收口
E 文档收尾+测试方案+问题单（依赖 A3+B4+C5+D5 全绿）
```

轮间顺序 A→B→C→D→E（A2 与 D1 都动 CanvasBoard，串行防冲突；B/C 后端可独立）。轮内步串行。

**对规格的实现期细化**（critique 自查后补，原因随行）：
1. §4 XI-1①「组框右键不开菜单」依赖 XI-4① 框体 pointer-events 放开——A2 先把 closest 判定分支写好（对现状 pointer-events:none 的组框天然不触发=右键落 pane 开菜单，可接受过渡），D1 落地后分支自然生效；D1 收口补组框右键不开菜单用例。
2. §4 XI-4⑨ ⛓ 关=「组壳仍重建、边全丢」——粘贴分支按开关先判边（现有 keepLinks 判定处扩含组级跨边），组壳重建不进开关判定。
3. B2 大卡片项目 mediaTypes 为 VO 原样 JSON 字符串——前端 `JSON.parse` try/catch 回落 `[]`；分组顺序=词汇序，不在词汇内的资产类型归尾组「其他」。
4. B3 resolve 失败回滚删节点走程序化 remove（不额外入撤销栈；撤销栈留「建+删」两步可撤回原状，可接受）。
5. C1 Jackson 混合数组（string|object 同层）反序列化用自定义 deserializer 判 `JsonNode` 类型（string→`{key,children:[]}`）；DB 读侧 `parseRoles` 改走 `readTree` 同判——两处容错同一规则。

---

## Chunk A · 画布右键菜单（XI-1，P0）

### A1 palette 共享常量提取 ✅ 2026-09-02 c1db6300

- **目标**：7 类节点清单单源化（调色板与右键菜单共用），行为等价重构。
- **动作**（伪代码）：
  ```
  新建 components/canvas/paletteItems.ts:
    export const PALETTE_ITEMS = [{ type:'text',label:'文本',icon:DocumentTextOutline }, … director]   // 7 类照搬 CanvasView:2246-2254
  CanvasView.vue: 删本地 palette 常量 → import PALETTE_ITEMS；模板/quickAddFiltered/onPalette* 全部改引（零行为变化）
  ```
- **涉及文件**：components/canvas/paletteItems.ts(新)、views/CanvasView.vue、CanvasView.test.ts(若有 palette 断言则同步)
- **依赖**：无
- **验证**：vitest 全量回归（调色板渲染 7 项、quick-add 过滤、拖拽 dataTransfer payload 不变）+ vue-tsc 0 错。

### A2 CanvasBoard 右键菜单：判定+两组菜单项+落点+接线 ✅ 2026-09-02 9d9140fc（偏离注：↑↓ 循环焦点未做——11 项均为原生 button，Tab/Enter 天然可达；无 aria-activedescendant 支撑的 arrow 循环易反成无障碍负担，简化为「可聚焦+Enter 触发」满足 spec ⑥「键盘可达」下限）

- **目标**：空白右键弹菜单（添加节点 7 类+画布操作 4 项），点类型在右键点建节点；操作接线既有链。
- **动作**（伪代码）：
  ```
  CanvasBoard.vue:
    根 @contextmenu.prevent 改 @contextmenu="onRootContextMenu"（内 preventDefault）:
      target closest('.vue-flow__node'|'.vue-flow__edge'|'.canvas-board__groupbox'|'.canvas-board__toolbar') → 命中=不开（return）
      否则 menu = { visible:true, x:clientX, y:clientY, flowPos: project(clientX-rect.left, clientY-rect.top) }   // 拖入链同款换算
    菜单 DOM（FlowCanvas overlay 范式）:
      <div v-if=menu.visible class="ctx-overlay" @click/@contextmenu.prevent=closeMenu>   // 全屏透明
        <div role="menu" class="ctx-menu" :style=贴边翻转(x,y,预估宽高,viewport)>
          组头「添加节点」: PALETTE_ITEMS.map → <button role=menuitem @click=addAtMenu(p)>icon+label</button>
          分隔线
          组头「画布操作」: 粘贴(disabled=!hasClipboard)/撤销(disabled=!canUndo)/重做(disabled=!canRedo)/一键整理
    addAtMenu(p): addNode({type:p.type, position:menu.flowPos, data:{label:p.label}}); closeMenu()
    粘贴: pasteSubgraph(clip, /*落点*/ menu.flowPos)   // 落点参数化：现状取鼠标位置处，加可选参（不传=现状鼠标位）
    Esc: 菜单开着 window keydown 捕获+stopPropagation+closeMenu（Lightbox R7 逐层退教训；关菜单即摘监听）
    键盘: 菜单开自动焦第一项，↑↓ 循环、Enter 触发、role=menu/menuitem、aria-label=动作名
  ```
- **涉及文件**：components/canvas/CanvasBoard.vue、CanvasBoard.test.ts
- **依赖**：A1
- **验证**：vitest——空白（pane 元素 mock）右键开菜单；节点/边/工具条右键不开；组框分支存在（细化1：D1 后补生效用例）；点「图片」在 flowPos 建节点；粘贴调 pasteSubgraph 带落点；四操作 disabled 态（剪贴板空/无撤销/无重做）；Esc 只关菜单（外层 document 监听不收到——同 R7 用例范式）；菜单开着再右键=挪位不叠层；quick-add modal 开着右键不触发（overlay 在 modal 下天然挡）。

### A3 A 轮收口测试 ✅ 2026-09-02（全量 1028/1028+vue-tsc 0 错；手测项——贴边翻转/节点右键仍开存资产库/落点体感——并入 E 轮 S1 手测清单，不在本轮重复记录）

- **动作**：vitest 全量 + vue-tsc 0 错；手测标记——右键菜单位置贴合/贴边翻转/节点右键仍开「存入资产库」/建节点落点准/粘贴落点=右键点。
- **验证**：全绿 + 手测记录入变更记录。

---

## Chunk B · 官方库大卡片（XI-2，P0）

### B1 后端：listPublic official 参数 + AssetVO.mediaCategory ✅ 2026-09-02（bc23dd8b；AssetVO.mediaCategory 核查既有已透出——「缺则补」分支未触发零改动）

- **目标**：官方项目服务端过滤；资产 VO 供前端反向映射节点类型。
- **动作**（伪代码）：
  ```
  AssetPublicPoolController: GET /public-pool 增 @RequestParam(required=false) Boolean official → listPublic(userId, admin, official)
  AssetPublicPoolService.listPublic: official==TRUE 时 query 加 .eq(AssetProject::getPublishedByAdmin, true)   // 服务端强制，不信前端
  AssetVO: 查 mediaCategory 字段有无——缺则 list 组装处补透出（Asset.mediaCategory 既有列）
  api/assets.ts: publicPoolApi.list(params?: { official?: boolean })
  ```
- **涉及文件**：AssetPublicPoolController.java、AssetPublicPoolService.java、AssetVO.java(若缺)、AssetService.java(组装处，若需)、api/assets.ts、对应后端测试
- **依赖**：无
- **验证**：后端测试——official=true 只返 publishedByAdmin 项目；不传=全量（现状回归）；AssetVO 含 mediaCategory。

### B2 OfficialLibrary.vue 大卡片 ✅ 2026-09-02（4997a767；实现偏离：①emit `picked {asset}` 不含 resolve——resolve 需先有 nodeId，改由 B3 父组件建节点后发起；②无 canvasId prop——resolve 参数在父组件就地取 editingId；③项目卡无封面（文本卡：badge/名称/计数/发布者/日期/描述），封面懒加载不做）

- **目标**：双栏大卡片——左官方项目列表、右按媒体类型分组资产行（四态预览复用）。
- **动作**（伪代码）：
  ```
  新建 components/canvas/OfficialLibrary.vue:
    props: { show, canvasId }; emit: update:show / picked { asset, resolve }
    打开 → publicPoolApi.list({official:true}) → 左栏项目卡（封面/名称/描述/assetCount/publishedAt；loading/error+重试）
    选中项目 → assetApi.list(projectId, { size:100 })（无 type 全量）
      分组 = JSON.parse(project.mediaTypes ?? '[]') 词汇序 .map(key => { 组头 key, 行=assets.filter(a => a.mediaType === key) })
      不在词汇内的 mediaType → 尾组「其他」（细化3）；空组隐藏
    行 = AssetPickerRow 复用（props/事件同 AssetPicker 用法；@click.stop 口径照搬）+ Lightbox 接入（z3000 盖 modal 既有）
    空态: 无官方项目「暂无官方发布项目」；项目无资产「该项目下无资产」
    a11y: modal preset card aria；项目卡=button；行交互继承 AssetPickerRow
  ```
- **涉及文件**：components/canvas/OfficialLibrary.vue(新+test)、api/assets.ts(B1 已动)
- **依赖**：B1
- **验证**：vitest——official=true 请求断言；项目列表渲染；按 mediaTypes 词汇序分组+「其他」尾组+空组隐藏；行四态渲染（复用 AssetPickerRow 即组件级回归）；Lightbox 开于卡片之上；空态两路；选择按钮 emit picked（resolve mock）+关弹窗。

### B3 CanvasView 接线：入口条目 + 插入链 ✅ 2026-09-02（4997a767；实现偏离：①无 CanvasView.test.ts 该文件——链路拆两端单测替代：mediaToNodeType 纯函数 3 例在 paletteItems.test.ts、addNodeAtCenter/abortNodeAdd 3 例在 CanvasBoard.test.ts，接线层留 E 轮 S 系列手测；②回滚抽 abortNodeAdd 公共方法而非临时 removeNode）

- **目标**：调色板导演台下「官方库」条目；选择→建节点→resolve→写节点→保存。
- **动作**（伪代码）：
  ```
  CanvasView.vue:
    palette aside 尾部: <button class="canvas-palette__item canvas-palette__item--action" @click=officialOpen=true>
      <n-icon LibraryOutline />官方库</button>   // 非 draggable、与节点条目样式区隔（分隔线/底色）
    OfficialLibrary 接入: @picked=onOfficialPicked
    onOfficialPicked({ asset, resolve }):
      type = MEDIA_TO_NODE[asset.mediaType] ?? CATEGORY_FALLBACK[asset.mediaCategory]   // 提示词→text/剧本→script/分镜→storyboard/图片→image/视频→video/音频→audio；TEXT/IMAGE/VIDEO/AUDIO 回落
      newId = boardRef.addNodeAtCenter({ type, data:{ label: asset.name } })
      try: 写节点 = AssetPicker picked 落点同款（fileId/previewUrl/文本 content——抽公共函数或调既有 handler）
      catch/resolve 失败: toast + boardRef 程序化 removeNode(newId)（细化4：不入撤销栈）+ 大卡片留
      成功: scheduleSave + 关大卡片
  CanvasBoard.vue: defineExpose 增 addNodeAtCenter(partial)   // 视口中心=board rect 中心 screenToFlowCoordinate
  ```
- **涉及文件**：views/CanvasView.vue、components/canvas/CanvasBoard.vue、CanvasView.test.ts
- **依赖**：B2
- **验证**：vitest——入口条目渲染+不可拖拽；六已知类型映射正确+自定义类型按 category 回落；建节点于中心（addNodeAtCenter mock 断言位置）；成功链写节点+scheduleSave+关卡片；resolve 失败删节点+toast+卡片留；addNodeAtCenter 中心换算单测。

### B4 B 轮收口测试 ✅ 2026-09-02（全量 vitest 1040/1040 + vue-tsc 0 错 + 后端 2715/2715 BUILD SUCCESS；手测项并入 E 轮 S6-S9）

- **动作**：vitest 全量 + vue-tsc 0 错 + 后端测试全绿；手测标记——真实官方项目浏览四态预览/大图盖卡片/选择插入成正确类型节点+产物可看/刷新重现。
- **验证**：全绿 + 记录入变更记录。

---

## Chunk C · 叙事角色两级词汇（XI-3，P0）

### C1 后端词汇地基：RoleVocab + 迁移 + 双容错 + normalize

- **目标**：词汇 shape 切 `[{key,children}]`；存量迁移；读/入参双容错；全局唯一约束。
- **动作**（伪代码）：
  ```
  新建 db/migration/V<max+1>__asset_role_vocab_two_level.sql:
    UPDATE asset_projects SET narrative_roles = 变换(每 string 元素 → {"key":s,"children":[]})
    WHERE jsonb_typeof(narrative_roles)='array' AND EXISTS(任一元素为 string)   // 幂等：对象形状行跳过
  dto/RoleVocab.java: { key, children: List<String> }
  AssetProjectService:
    DEFAULT_NARRATIVE_ROLES → List<RoleVocab>（五桶 children=[]）
    parseRoles → readTree 逐元素判（string|object）→ RoleVocab（细化5）
    normalizeRoles(List<RoleVocab>): trim/非空/一级去重/**扁平全集全局唯一**（子类撞任何一级或他父子类 → 400「叙事角色「X」重名」）/children 内部去重/children 数上限（≤20）+每名 maxlength 30
  ProjectUpdateRequest.narrativeRoles: List<RoleVocab> + @JsonDeserialize(自定义: JsonNode string|object 同判)
  AssetService.loadNarrativeRoles → 返扁平全集（父+子）——syncRoleLinks 校验签名不变（受控校验零改动效果）
  ```
- **涉及文件**：db/migration/V<max+1>__*.sql(新)、dto/RoleVocab.java(新)、ProjectUpdateRequest.java、AssetProjectService.java、AssetService.java、后端测试
- **依赖**：无
- **验证**：后端测试——迁移后存量行变对象形状（testcontainer/H2 手动 SQL 验证或本地 PG 实测）；parseRoles 双 shape（string 数组/对象数组/混合）；normalize 重名拒（子撞父/子撞他父子类）；空词汇拒；loadNarrativeRoles 扁平全集含子类；syncRoleLinks 挂子类合法/挂未知 key 拒。

### C2 后端行为：reassign 两级 + 筛选展开 + 分镜展开

- **目标**：删子类归父/删一级归通用；一级筛选含子类；一键分镜目录含子类。
- **动作**（伪代码）：
  ```
  reassignOnRemovedRoles(projectId, oldVocab, newVocab):
    removed 子类(父保留) → 挂它的 link 删+ensureRoleLink(父级 key)
    removed 一级(含其子类随删) → 挂一级或其子类的 link 删+fallbackExists 则 ensureRoleLink(通用)
    log 两分支 affected 数（既有日志风格）
  AssetService.list role 过滤(:419-427): key → 展开集 = key 是某一级 → [key+其 children]；是子级 → [key]
    roleLinkMapper .in(roleKey, 展开集)   // 词汇加载一次（loadNarrativeRoles 复用）
  getImageCatalog(:274-289): 实体集 = {人物,道具,场景} ∪ 各自在项目词汇中的 children（词汇缺该一级回落默认集=现状）
  ```
- **涉及文件**：AssetProjectService.java、AssetService.java、后端测试
- **依赖**：C1
- **验证**：后端测试——删子类→link 改父级（断言 roleLinkMapper 调用）；删一级→子类随删+挂子类者归通用；一级筛选 SQL 含子类（mock wrapper 断言 in 集）；子级筛选只子级；一键分镜目录含「老人」类资产。

### C3 前端类型 + VocabEditor 两级编辑

- **目标**：types 切 RoleVocab；编辑弹窗两级草稿（一级行+子类 chips）。
- **动作**（伪代码）：
  ```
  types/asset.ts: NarrativeRoleVocab { key, children: string[] }; narrativeRoles 三处类型替换
  VocabEditor.vue roles Tab 重写:
    draft: { key, children: string[] }[]
    一级行 = n-input(key) + 删除(popconfirm 计数=挂一级或子类资产数) + 「+子类」
    子类行 = chips（n-tag closable=删）+ 添加小输入（Enter 加；blur 拦全局重名→空+提示）
    normalized: 保序去重+**扁平全局唯一**（与后端同规则）+children ≤20/名 ≤30
    save payload: { roles: NarrativeRoleVocab[], mediaTypes }   // emit 签名改
    hint 文案: 两级说明+删除重指派两级口径（子类归父/一级归通用）
  ```
- **涉及文件**：types/asset.ts、components/asset/VocabEditor.vue、VocabEditor.test.ts
- **依赖**：C1（shape 对齐）
- **验证**：vitest——打开拷贝两级草稿；加/删/改子类；子类撞一级或他父子类前端拦（空值回退）；一级删确认文案两级计数；save payload shape；一级至少 1 条 canSave。

### C4 前端消费点：表单多选 / 存入库 / 矩阵左栏

- **目标**：三处挂角色/筛角色 UI 两级化；展示位零改动。
- **动作**（伪代码）：
  ```
  AssetProjectView.vue: roleOptions(:328) → n-select group: 每一级 { label:key, children:[{label:`${key}（不细分）`,value:key}, …children.map(c=>{label:c,value:c})] }
  SaveToAssetDialog.vue: 同款分组 options（同抽 util buildRoleGroupOptions(vocab) 共享——两处+将来复用）
  AssetMatrixFilter.vue: 左栏一级分段（现状样式）+ 其下子级 chip 缩进行
    点一级 → emit role=[一级 key]（后端展开含子类）；点子级 → emit role=[子级 key]；再点取消
    一级 active 态=自身或其任一子级选中
  AssetListView.vue 计数: narrativeRoles.length（=一级数，语义微调可接受——文案「N 个叙事角色」）
  ```
- **涉及文件**：views/AssetProjectView.vue、components/canvas/SaveToAssetDialog.vue、components/asset/AssetMatrixFilter.vue、共享 util（utils/assetVocab.ts 新或就近）、对应 .test.ts
- **依赖**：C3
- **验证**：vitest——表单分组 options（含「（不细分）」）；提交挂父级 key/子级 key 各一例；存入库同款；矩阵一级点击 emit 一级 key、子级点击 emit 子级、active 态联动、取消；AssetCard/DetailDrawer/PickerRow roleKeys 展示回归（扁平串零改动）。

### C5 C 轮收口测试

- **动作**：vitest 全量 + vue-tsc 0 错 + 后端全绿 + 本地 PG 迁移实测（Flyway 起动跑 V<max+1> + 存量项目词汇变形抽验）；手测标记——编辑分类两级保存/删子类资产归父/矩阵两级筛/一键分镜含子类/画布存入库分组选。
- **验证**：全绿 + 记录入变更记录。

---

## Chunk D · 组大节点（XI-4，P0）

### D1 组框点选 + 高亮 + 选中态互斥

- **目标**：点组框空白=选整组（高亮）；与节点选中/框选/Esc 分层互斥；Delete 无动作。
- **动作**（伪代码）：
  ```
  先探层序: 组框 z-index vs .vue-flow__pane/节点层（读样式+devtools 实测）
    组框在节点下层 → 框体 pointer-events:auto 安全（成员在上接住自己点击）
    组框在节点上层 → 仅边带+空白垫层命中（成员区 pointer-events:none 打洞）
  CanvasBoard.vue:
    state: groupSelectedId = ref<string|null>
    组框 @pointerdown.self(或空白垫层) → groupSelectedId=g.id（不清 multiSelected——并存可后议，简化：设组选时清节点多选）
    高亮: groupbox--selected class（accent 边框+微光）
    清组选: onPaneClick/onNodeClick/框选起手/watch groups 无此组 → null
    Esc: 并入现有清选中链（清组选+清多选，**不吞事件**——组选非模态；菜单/灯箱才吞）
    Delete/Backspace: groupSelectedId 非空 → 不动作（Q5；节点删除链入口判组选态直接 return）
  ```
- **涉及文件**：components/canvas/CanvasBoard.vue、CanvasBoard.test.ts
- **依赖**：A2 完成（CanvasBoard 无并发改动）
- **验证**：vitest——点组框空白 groupSelectedId+高亮 class；点成员=清组选+选成员（现状单选）；点 pane/Esc 清；框选起手清；Delete 组选态无动作（不删成员）；改名/✕/组端口三入口回归；A2 补组框右键不开菜单用例（细化1）。

### D2 整组拖动

- **目标**：选中组拖框=成员联动移动，rAF 节流，松手落库。
- **动作**（伪代码）：
  ```
  组框 pointerdown(已选中态) → setPointerCapture + 记起点
    pointermove: rAF 节流 → 全成员 position += delta（批改 nodes 模型；包围盒 rAF 既有跟随）
    pointerup: releaseCapture + emit structure-changed（防抖保存链）   // 拖动中不 emit
  边界: 未选中组框拖=先选中不拖（点选与拖动分离：move 超阈值才算拖）；拖动不改成员关系；组端口 pointerdown 优先（连线不被抢）
  ```
- **涉及文件**：components/canvas/CanvasBoard.vue、CanvasBoard.test.ts
- **依赖**：D1
- **验证**：vitest——选中态拖框成员坐标批变（+delta 断言）；未选中拖=只选中不动；拖动中零 structure-changed、松手一次；组端口拖线回归；rAF 节流（多 move 一帧一批）。

### D3 剪贴板 groups 收集 + 组级跨边（纯函数）

- **目标**：完全包含组进剪贴板；组级跨边收集；混合组边规则纯函数化。
- **动作**（伪代码）：
  ```
  canvasClipboard.ts:
    CanvasClipboard + groups: { name, color, memberIds:string[] }[]  // 旧 id
                       + groupCrossEdges: { fromKey, toKey }[]       // key=节点 id|`group:${组下标}` 伪引用
    buildCopySet(nodes, edges, selectedIds, **groups**):   // 签名 +第4参
      完全包含: g.memberIds ⊆ selectedIds 且非空 → groups 收 {name,color,memberIds}（成员 items 照收）
      原组组边（group:原id ↔ 节点X|group:另一组）:
        组进板+对端不在板 → groupCrossEdges 收（组端记 `group:${idx}`）
        两端组都进板 → 也收（组端各记下标）——粘贴时判新↔新
      成员→本组 group:原id 成员级组边: 组进板 → 按「内边」口径收（两端都在板）→ 粘贴重映射新组
    remap: 节点 id 照旧映射表；`group:${idx}` → 新组 id；组级跨边对端=节点 → aliveNodeIds 校验、=原组伪 id → 原组 alive 校验（解散丢边）
  ```
- **涉及文件**：components/canvas/canvasClipboard.ts、canvasClipboard.test.ts
- **依赖**：无（可与 D1/D2 并行，纯函数）
- **验证**：单测——完全包含收组（name/color/memberIds）；半含不收（修复X 回归锚）；两组全含+组→组边收；组进板+组边对端在外收 cross；成员级到本组边收内边口径；remap `group:${idx}`→新 id；对端解散/已删丢边；既有 VII/IX/X 用例全回归（无 groups 入参=零组收集，向后兼容）。

### D4 粘贴重建组 + 组边克隆 + 撤回

- **目标**：粘出新组（新 id/name 去重/成员重映射），组级跨边连原对端，⛓ 治边不治壳，一步撤。
- **动作**（伪代码）：
  ```
  CanvasBoard.vue pasteSubgraph:
    clip.groups 非空:
      每 group → newId=createGroupId(); name=uniqueGroupName(name)（撞名+序号）；memberIds → keyToNewId 映射
      groups.value.push（含 color 拷贝）
    边分流（在现有 keepLinks 判定内）:
      ⛓ 开: innerEdges（含成员→本组边 remap 新组）/crossEdges（成员↔组外节点 照旧）/groupCrossEdges（新组↔原对端；两板组=新↔新）→ 含组伪 id 进 groupEdges 池（剥会话 class），否则普通池
      ⛓ 关: 组壳照建（细化2）、innerEdges/crossEdges/groupCrossEdges 全不粘（现状零边口径扩含组级）
    pushHistory 一次含组+两池（快照既有）；toast「已粘贴 1 个组（N 个节点）」
  ```
- **涉及文件**：components/canvas/CanvasBoard.vue、CanvasBoard.test.ts、views/CanvasView.vue(toast 若在 View 层)
- **依赖**：D1（组框渲染复用）+D3（clip schema）
- **验证**：vitest——粘组：新组 id/name 去重/memberIds 全新/与原组零共享；组级跨边落 groupEdges 池+v-model 零伪 id；原组解散后粘贴丢组级边不产断边；⛓ 开全边/⛓ 关壳留边零（细化2 双向用例）；Ctrl+Z 一步撤（组+节点+边全消）；连贴 +32；部分成员粘贴（无 groups）=现状平节点回归。

### D5 D 轮收口测试

- **动作**：vitest 全量 + vue-tsc 0 错；手测标记——点组选组/整组拖动跟手/完全包含 Ctrl+C→V 粘新组连原上下游（真实生成消费：组下游广播取到新组成员产物）/半含平节点/Delete 无动作/解散 ✕/刷新重现。
- **验证**：全绿 + 记录入变更记录。

---

## Chunk E · 文档收尾（依赖 A3+B4+C5+D5）

- **feature-map/user-ops 增补**：无限画布创作页 feature-map+手册「2026-09-02 增补（修复XI）」节——右键菜单（两组项/落点/Esc 逐层退）、官方库（入口/大卡片结构/插入链/与从库选择分工）、组大节点（点选分层/整组拖动/完全包含即带组/⛓ 治边不治壳）；项目资产库 feature-map+手册增补节——两级词汇（编辑分类两级/矩阵两级筛选/删除重指派两级/一键分镜含子类）。
- **测试方案**：`docs/测试方案/人工测试遗留问题修复XI测试方案.md`（新，S 系列：S1-S5 右键 / S6-S9 官方库 / S10-S14 两级词汇 / S15-S19 组大节点，含反向：菜单 Esc 不清多选、官方库 resolve 失败回滚、删一级归通用、半含不带组、⛓ 关壳留边零）。
- **help 中心**：若资产库帮助文章提及叙事角色口径（21-assets-basics 等）补两级说明一行；画布帮助文章若无右键/官方库提及则不动（新增能力不追溯旧文）。
- **问题单**：2x 四项挂「已实现，待人工验证（修复XI）」+commit 号。

---

## 技术坑点预判

| # | 坑 | 规避 |
|---|---|---|
| 1 | 组框 pointer-events 放开抢成员/组端口/组边点击 | D1 先探层序再选实现（下层=安全放开；上层=空白垫层+成员区打洞）；三入口（改名/✕/端口）回归用例锁死 |
| 2 | 整组拖动每 pointermove 全成员响应式+包围盒重算 → 大组卡顿 | rAF 节流批改+松手才 structure-changed（拖动中不落库不 emit）；≤50 成员上限既有 |
| 3 | Esc 层叠：菜单吞了 n-modal/清多选的 Esc（Lightbox R7 同款回归） | 菜单 Esc=window 捕获+stopPropagation+摘监听；**组选中 Esc 不吞**（并入现有清选中链）；两处用例分别锁 |
| 4 | 迁移 SQL 手写 jsonb 变换错写炸全表 | 幂等 WHERE（仅含 string 元素的数组行）+读侧 parseRoles 双容错兜底+本地 PG Flyway 实测抽验（C5）；逆变换 repair 口径落 spec §5 |
| 5 | Jackson 混合数组（string|object 同层）反序列化炸 | 自定义 deserializer 判 JsonNode 类型（细化5）；DB 读侧 readTree 同规则——两处容错单测各带混合数组例 |
| 6 | 官方库 mediaTypes 原样 JSON 字符串 parse 炸/顺序乱 | try/catch 回落 []（细化3）；词汇序分组+「其他」尾组兜住不在词汇的资产 |
| 7 | resolve 失败回滚删节点触发 watch(nodes) 级联/撤销栈脏步 | 新节点无组无边（watch 减员链不触发）；程序化 remove 不入栈（细化4），撤销栈「建+删」两步可回原状 |
| 8 | buildCopySet 加第 4 参后既有 3 参调用点漏改 | 签名默认空数组=零组收集向后兼容（D3 单测「无 groups 入参行为=现状」）；CanvasBoard:933 调用点同 chunk 改 |
| 9 | 子类全局重名漏拦 → role_key 筛选歧义 | 前端 blur 拦+后端 normalize 400 双闸（C1/C3）；存量迁移不产生重名（纯加壳）；重命名=删+加语义（挂载资产归父级）hint 文案写明 |
| 10 | 一级筛选展开每请求多一次词汇加载 | loadNarrativeRoles 复用（syncRoleLinks 校验同一加载器）；无 role 参数请求零额外查询 |
| 11 | n-select 分组选项 value 撞（父 key vs 子类 key） | 词汇全局唯一规则保证（坑9）；无子类一级=组内仅「（不细分）」一项 |
| 12 | 菜单 clientX/Y 出屏右下 | 贴边翻转（预估菜单尺寸 vs viewport，右溢左翻/下溢上翻）；A3 手测翻转 |
| 13 | 粘贴组 name 与既有组撞名 | uniqueGroupName 追加序号（label 三级去重同风格）；「副本」后缀不加（序号够用） |
| 14 | 组级跨边 id 与存量组边撞 | 组边 id 沿用 Date.now+seq 生成规则（伪 id 入串不破唯一性，修复X 坑11 同口径） |

## 安全检查清单（P3 逐项验）

- [x] official 过滤服务端强制（B1 `.eq(publishedByAdmin)`），不信任前端列表过滤
- [x] resolve/资产列表/文件 blob 链鉴权零改动零绕过（B2/B3 走既有 AssetPicker 同链）
- [x] 词汇受控校验扁平全集拒任意 key（C1 syncRoleLinks 效果不变）；子类输入 maxlength 30+trim+去重双闸
- [x] 迁移只动 narrative_roles 列；公众池白名单 VO 不加字段（B1 只加查询参数）
- [x] 菜单/组交互纯前端零新请求面（A/D）；无 v-html 零 XSS 新面
- [x] 审计既有：词汇变更/发布/resolve 日志不动（C2 沿用 log 风格补两级分支计数）

## 功能联动点清单（只列正向，边界含反向/半选/批量）

| # | 触发 | 联动 | 边界 |
|---|---|---|---|
| L1 | 右键点节点类型 | 右键点建节点→防抖保存链 | quick-add modal 开着不触发（遮罩挡）；菜单 Esc 只关菜单不清多选；建后菜单关 |
| L2 | 菜单「粘贴」 | 同 Ctrl+V 链落点=右键点 | 剪贴板空 disabled；⛓ 开关照旧治边；连贴错开 |
| L3 | 官方库「选择」 | 建节点→resolve 绑定→写节点→保存→关卡片 | resolve 失败删节点+toast+卡片留；六类型映射+自定义回落；批量=逐次单选（不做多选） |
| L4 | 删子类 | 挂子类资产 link 改挂父级 | 父级同批删→归通用；重命名子类=删+加（挂载归父级不自动随新名）；计数文案两级 |
| L5 | 矩阵点一级 | 后端展开含子类资产并集 | 点子级=仅子级；无子类=现状；挂一级（不细分）资产在子级筛选不出现 |
| L6 | 完全包含复制 | 剪贴板带组壳+组级跨边 | 半含不带（修复X 平节点）；两组一全一半混合各自规则；再 Ctrl+C 覆写无残留 |
| L7 | 粘贴组 | 新组+组级跨边连原对端+组→组双含新↔新 | 原组解散/对端删→丢边不产断边；⛓ 关=壳留边零（细化2）；Ctrl+Z 一步全撤 |
| L8 | 组选中态切换 | 点组框=选组高亮；点成员=切成员；框选/Esc/点 pane=清组选 | Delete 组选态无动作；改名/✕/端口入口不抢；菜单 Esc 与组选 Esc 语义分开（吞/不吞） |
| L9 | 一键分镜 | 图片目录含子类资产（人物/道具/场景∪子类） | 词汇缺该一级回落默认集=现状；子类资产额染同父级口径 |

## 运维考量清单

| 类 | 结论 | 落字 |
|---|---|---|
| 可观测性 | **不做** | 纯前端三轮+1 过滤参数；resolve/词汇/发布既有 log 覆盖（C2 补两级分支计数沿既有格式） |
| 配置开关 | **复用** | ⛓ keepLinksOnCopy 治组粘贴边（关=回零边粘贴，组壳新增行为 revert 可关）；右键菜单/官方库/两级=纯增强非高危不设开关 |
| 可回滚 | **做预案** | C1 迁移幂等+逆 jsonb 变换 repair 脚本口径（spec §5）；四轮代码独立 revert；词汇读侧容错=旧格式行也能活 |
| 限流/熔断 | **不做** | 无新第三方依赖；官方库走既有接口既有超时 |
| 运维入口 | **不做** | 词汇脏数据=编辑分类重存即重指派（既有链）；画布组数据=快照既有 |
| 告警阈值 | **不做** | 无新指标面 |
| 容量/性能 | **想过** | 官方库资产 100/项目上限与 picker 同口径（超限分页后续另案）；narrative_roles JSONB 微量；组 ≤50 成员既有上限；拖动 rAF 节流 |

## 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-09-02 | 建立 plan（A1-A3/B1-B4/C1-C5/D1-D5/E 五轮；critique 后五处实现期细化：A2 组框右键时序/B2 mediaTypes 容错/B3 回滚不入栈/C1 双容错规则/D3 向后兼容签名）。同轮补规格 XI-4⑨ ⛓ 治边不治壳口径 | 修复XI 规格过审进入 P2 |
| 2026-09-02 | A 轮完成（c1db6300 palette 单源 + 9d9140fc 右键菜单 8 用例，全量 1028/1028）。两处实现期偏离：①A2 ↑↓ 循环焦点未做（原生 button Tab/Enter 可达即满足 spec ⑥ 下限）；②粘贴落点参数传 client 坐标 `{x,y}` 而非 flowPos（pasteSubgraph 内部统一换算，与键盘链同函数同口径，比外层预换算更不易漂）；A3 手测项并入 E 轮 S1 | 计划是建议不是圣旨——P3 发现更简实现回写 plan |
| 2026-09-02 | B 轮完成（bc23dd8b 后端 official 过滤 + 4997a767 OfficialLibrary 大卡片+插入链，全量 1040/1040+后端 2715/2715）。四处实现期偏离：①B2 emit `picked{asset}` 不含 resolve（resolve 需先有 nodeId，改 B3 建节点后发起）；②OfficialLibrary 无 canvasId prop；③无 CanvasView.test.ts——链路拆 paletteItems.test 3 例+CanvasBoard.test 3 例两端单测，接线层留手测；④项目卡文本化无封面。B4 手测项并入 E 轮 S6-S9 | resolve 反序架构必然+避免为测接线新建大文件；回写口径 |

## 术语表

| 术语 | 大白话 | 案例 |
|---|---|---|
| 上下文菜单 | 点右键弹出来的那列操作按钮 | 画布空白右键→「图片/视频/…/粘贴/撤销」 |
| pane（画布底板） | 画布里没放节点的空白背景区 | 右键底板才弹菜单 |
| 受控词汇 | 「允许填哪些值」的白名单，只能挑不能自由填 | 叙事角色只能在 人物/道具/… 里选 |
| 两级词汇 | 白名单的值自己再挂一层小类 | 人物→老人/青年/孩童 |
| 扁平全集 | 两级词汇拍平成一层去校验/去重（父+全部子类一个集合） | 「老人」不得与任何一级或其他父的子类重名 |
| 官方发布 | 管理员发到公众池的项目打的官方标记 | 官方库只收 publishedByAdmin=true |
| resolve | 把资产「绑定到节点」的后端登记+取内容 | 选官方图片→新图片节点带出图 |
| 反向映射 | 从资产类型倒推该建哪种节点 | 音频资产→建音频节点 |
| 大节点（组） | 组整体当一个单位选、拖、拷 | 点组框选全组，Ctrl+V 粘出新组 |
| 完全包含即带组 | 选中集把某组成员全圈进去，复制就带组壳 | 框选盖住整组→粘出的是组 |
| 组级跨边 | 复制组时把原组对外的线记下，粘出接回原对端 | 原组→X：新组→X |
| 治边不治壳 | ⛓ 开关只管连线留不留，组壳（分组结构）照建 | ⛓ 关粘贴：新组还在，里面连线全没 |
| 贴边翻转 | 菜单快出屏幕时往反方向弹 | 右下角右键→菜单往左上开 |
