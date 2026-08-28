# 人工测试遗留问题修复VII · 开发计划（P2 plan）

> 范围：`workflow_output/人工测试问题/2x. 资产库和无限画布.md`「未解决」最后 2 项：画布节点复制粘贴、一键优化布局。
> 规格权威：`docs/specs/人工测试遗留问题修复VII设计.md`（VII-1/VII-2，Q1~4 已拍板）。
> 分支：beifen。**纯前端**，零 DB 迁移、零后端改动、零新端点。
> 基线：修复VI 已落地（commit `619fecae`/`b8fda394`/`9e68287d`，挂待人工验证）。

---

## 0. 现状锚点（P3 直接引用，均已核实）

| 锚点 | 事实 | 位置 |
|---|---|---|
| 键盘链 | window `onWindowKeydown` 只处理 Esc/Delete/Backspace（先排除可编辑元素）；boardRoot @keydown 处理 Ctrl+Z | CanvasBoard.vue:505-520、1033-1041 |
| 粘贴链 | boardRoot @paste `onPaste` 只认剪贴板图片文件；落点=鼠标（`lastClient`+`project`，无记录=视口中心） | CanvasBoard.vue:563-589 |
| 复用件 | `project`、`fitView`(别名 vfFitView) 已从 useVueFlow 解构；`isEditableTarget` 工具已有；`seqCounter` 防 id 撞；`uniqueLabel` 去重 | CanvasBoard.vue:170-173、142、679、utils/interpolate.ts |
| 克隆口径 | `cloneNodeForDuplicate` 深拷贝+RESET_KEYS 脱钩（taskId/资产清零、产物保留、+40/+40）；`cloneEdgesForDuplicate` 单节点连边克隆 | nodeClone.ts:14-56 |
| 历史 | `pushHistory(tag)` 同 tag 800ms 合并；快照栈 50 步不含 viewport；`appendEdges` 已是「一条历史步+单 structure-changed」批量范式 | CanvasBoard.vue:989-999、1097-1103 |
| 选择态 | 单选 `selectedNodeId`、多选 `multiSelectedIds`（Esc 清空）；组 `groups[].memberIds` 只存组侧 | CanvasBoard.vue:505-517 |

---

## 一、Chunk 拆分（每 chunk ≤20 文件，含验证）

### Chunk 1 · dagre 依赖 + autoLayout 纯函数

- **目标**：`computeAutoLayout(nodes, edges, {direction:'LR', includeIds?}) → Map<id,{x,y}>` 可单测的布局核（含子图模式+锚定+网格对齐）。
- **动作（伪代码）**：
  - `pnpm add @dagrejs/dagre`（锁 1.x；gzip ≈12KB，只进 CanvasView 所在 chunk）。
  - 新建 `src/utils/autoLayout.ts`（纯函数，不 import Vue）：
    - `nodeSize(node)`：宽高取 `data.width/height`，缺省按类型默认表（image/video 320×320；text/storyboard/script/audio/director 等文本类 300×180）；返回数值。
    - `computeAutoLayout(nodes, edges, opts)`：
      - includeIds 给定 → 参与集=includeIds（诱导边=source/target 都在集内）；否则全集。
      - `new dagre.graphlib.Graph({multigraph:true})`，setGraph({rankdir:'LR', ranksep:100, nodesep:60, marginx:20, marginy:20})；逐节点 setNode(id,{width,height})、逐诱导边 setEdge。
      - `dagre.layout(g)`；读回每个节点 `g.node(id).x/y`（dagre 给中心点 → 减半宽高转左上角）。
      - 全集模式：坐标原点归一（min x/y 平移到 0,0）。
      - 子图模式：算子图新 bbox 左上角与原子图 bbox 左上角差值 → 整体平移回原位（最小漂移，未选节点零动）。
      - 两模式末尾都 `Math.round(v/16)*16` 网格对齐（对齐 snap-to-grid 16）。
      - 空集/单节点/无节点 → 返回空 Map 或恒等位置（不抛错）。
- **涉及文件**：`package.json`、`pnpm-lock.yaml`、`src/utils/autoLayout.ts`(新)、`src/utils/autoLayout.test.ts`(新)。
- **依赖**：无。
- **验证**：单测 ① LR 序不变量：任一边 u→v，`x(u)+width(u) ≤ x(v)`；② 两个不连通子图布局后 bbox 不相交；③ 有环图（含自环）不挂死、返回坐标有限；④ includeIds 模式：集外节点不在返回 Map、集内节点 bbox 左上角=原子图左上角；⑤ 同输入两次调用结果全等（确定性）；⑥ 全部坐标 %16==0；⑦ 空/单节点边界。

### Chunk 2 · CanvasBoard 接入「一键整理」

- **目标**：工具条「✨ 一键整理」按钮，全图/选中子图双模式（Q4），一步撤回+fitView。
- **动作（伪代码）**：
  - `CanvasBoard.vue` 新 `onAutoLayout()`：
    - 范围裁决：`selection = multiSelectedIds.length ? multiSelectedIds : (selectedNodeId ? [selectedNodeId] : [])`；非空 → includeIds = selection ∪ 所选节点所属组的全员（`groups` 扫 memberIds）；空 = 全图。
    - `positions = computeAutoLayout(nodes, edges, {direction:'LR', includeIds: 非空?includeIds:undefined})`。
    - `pushHistory('layout')` **一次** → 批量写 `node.position = positions.get(id)`（直编数组引用）→ `scheduleStoreReconcile()` + `emit('structure-changed')` **一次** → `nextTick` 后 `vfFitView({padding:0.15, duration:300})`。
  - 工具条（撤回/重做按钮旁）加按钮：icon ✨、title「一键整理布局：按上游→下游从左到右重排（选中节点时只排选中，Ctrl+Z 可撤回）」、aria-label 同 title、`:disabled="!nodes.length"`、aria-disabled 联动。
  - 不动：groups（memberIds 零改动，包围盒 rAF 派生自动跟随）、边样式、visibility class（隐藏节点照排）。
- **涉及文件**：`src/components/canvas/CanvasBoard.vue`、`src/components/canvas/CanvasBoard.test.ts`。
- **依赖**：Chunk 1。
- **验证**：组件测 ① 挂 3 节点链 A→B→C 点击按钮 → position 全变且 LR 序成立、structure-changed 恰 emit 1 次、undoStack +1；② undo() 一次 → 三个旧 position 全还原；③ 框选 A、C（组外）→ 只 A/C 位置变、B 不动；④ 选中含组成员 → 同组其余成员也被排；⑤ 无节点 disabled。vue-tsc 净。

### Chunk 3 · canvasClipboard 纯函数（复制集构建/落点/批 label）

- **目标**：复制粘贴的数据变换全部下沉纯函数，CanvasBoard 只做接线（同 nodeClone 范式）。
- **动作（伪代码）**：
  - 新建 `src/components/canvas/canvasClipboard.ts`：
    - `buildCopySet(nodes, edges, selectedIds) → {items:[{key, type, data, position}], innerEdges:[CanvasEdge 快照], bbox}`：选中集深拷贝（JSON 克隆断响应式链）、data 过 RESET_KEYS（复用 nodeClone 同表，抽公共常量）、status 按产物重算（success/idle，同 cloneNodeForDuplicate）；innerEdges=两端都在集内的边（诱导边口径，Q1）。
    - `planPastePosition(items, bbox, target:{x,y}, pasteCount) → {x,y}[]`：粘贴集 bbox 中心平移到 target；`pasteCount>0` 时整体再 +32*pasteCount。
    - `planLabels(items, existingLabels) → data.label[]`：批内+对画布现有 label 走 uniqueLabel 去重（撞名追加序号）。
    - `remapEdges(innerEdges, keyToNewId) → CanvasEdge[]`：source/target 按 key→新 id 重映射，新 id `edge-${s}-${t}-${Date.now()}-${seq++}`（复用 nodeClone 防撞序号范式）。
- **涉及文件**：`src/components/canvas/canvasClipboard.ts`(新)、`canvasClipboard.test.ts`(新)、`nodeClone.ts`(仅抽 RESET_KEYS 导出，不改行为)。
- **依赖**：无（可与 Chunk 1 并行）。
- **验证**：单测 ① 复制 3 节点 2 内边 1 外边 → items=3、innerEdges=2；② RESET_KEYS 全清+产物保留+status 重算（对齐 nodeClone 既有断言口径）；③ 落点：bbox 中心=target、pasteCount=1/2 时 +32/+64；④ label 撞名去重（同批两个同名+画布已有同名）；⑤ 边重映射后 id 唯一且端点全换新 id；⑥ nodeClone 既有 11 用例回归不破。

### Chunk 4 · CanvasBoard 接入 Ctrl+C/V（子图粘贴）

- **目标**：多选子图复制→鼠标处成组粘贴（Q1/Q2），单步撤回，与修复VI 图片粘贴共存。
- **动作（伪代码）**：
  - CanvasBoard 闭包态：`clipboard = ref<{items, innerEdges, bbox, pasteCount:number} | null>`。
  - `onWindowKeydown` 扩展（保持既有可编辑元素守卫在最前）：
    - Ctrl/Cmd+C：可编辑目标→放行 return；选中集=multiSelectedIds 非空?之: selectedNodeId?[之]:[]；空 → `clipboard=null` 且**不** preventDefault（恢复图片粘贴通道）；非空 → `clipboard=buildCopySet(...)+pasteCount:0`，preventDefault，`emit('nodes-copied', n)`（父 toast「已复制 N 个节点，Ctrl+V 粘贴」）。
    - Ctrl/Cmd+V：可编辑目标→放行；`clipboard` 非空 → preventDefault + `pasteSubgraph()`；null → 不拦（原生 paste 事件落 onPaste 图片链）。**顺序关键：keydown 里 preventDefault 后 paste 事件不会再触发，天然防双建**。
  - `pasteSubgraph()`：落点 target=鼠标画布坐标（`lastClient`+`project` 范式，无记录=视口中心）；`positions=planPastePosition(...)`；`labels=planLabels(...)`；`pushHistory('paste')` **一次** → 批量组 CanvasNode（id=`node-${Date.now()}-${seqCounter++}`、style=nodeSizeStyle(data)）push 进 nodes → `remapEdges` 后入 edges → `scheduleStoreReconcile()`+`emit('structure-changed')` 一次 → `clipboard.pasteCount++`。**复用 addNode 会逐个 pushHistory('add')+逐次 emit——禁用，必须直批**（tag 不同会拆两步历史）。
  - `defineExpose` 增 `pasteSubgraph`（测试/父组件可调）；emit 声明增 `nodes-copied`。
  - `CanvasView.vue`：`@nodes-copied` → 走该页既有 message toast 通道提示（Board 无 toast 上下文，父层出；P3 对齐 CanvasView 现有 useMessage 用法，勿新开 message 实例）。
- **涉及文件**：`src/components/canvas/CanvasBoard.vue`、`CanvasBoard.test.ts`、`src/views/CanvasView.vue`（仅事件接线）。
- **依赖**：Chunk 3。
- **验证**：组件测 ① 模拟选中 2 节点 1 内边 → dispatch window keydown Ctrl+C → clipboard 非空、emit nodes-copied(2)；② 再 Ctrl+V → nodes +2、edges +1（重映射端点=新 id）、undoStack +1、structure-changed 恰 1 次；③ 粘贴两次 → 第二次整体 +32；④ undo 一次 → 节点边全消；⑤ Ctrl+C 焦点在 input → 不拦截（clipboard 不变）；⑥ 无选中 Ctrl+C → clipboard=null 且未 preventDefault（再 Ctrl+V 走原生链）；⑦ label 撞名追加序号；⑧ onPaste 图片链回归（clipboard=null 时 dispatch paste 事件仍 emit pane-paste-files）。

### Chunk 5 · 文档同步 + 全量验证

- **目标**：规格→文档全链闭环；机器验证全绿。
- **动作**：① `docs/测试方案/人工测试遗留问题修复VII测试方案.md`(新)——规格 §7 人工 7 项展开成用例，编号接 VI 测试方案现有序列续排（P3 开工时先读 VI 文档定前缀）；② feature-map×2（无限画布创作页、项目资产库——只涉画布页，资产库若无涉动则只更新变更记录行）+ user-ops×2 增「2026-08-28 增补（修复VII）」节（Ctrl+C/V 用法、一键整理用法）；③ `2x. 资产库和无限画布.md` 末两项挂「已实现，待人工验证（修复VII）」注记（commit hash P3 回填）；④ 本 plan 勾选+`docs/specs/...VII设计.md` 变更记录补行。
- **涉及文件**：4-6 个文档 + plan/spec 回填。
- **依赖**：Chunk 1-4 完成。
- **验证**：`vue-tsc --noEmit` 0 错；`vitest run` 全绿（含 nodeClone/autoLayout/canvasClipboard/CanvasBoard 新旧用例）；grep 确认问题单注记 commit hash 已回填。

---

## 二、技术坑点预判（含性能坑）

1. **【正确性·历史步】粘贴禁走 addNode**：addNode 每次 `pushHistory('add')`+emit；再 appendEdges 又一条 'edge' 步 → 一次粘贴=2 步撤回（节点消失边还在的中间态）。**必须** pasteSubgraph 直批：一次 pushHistory('paste')、一次 emit。800ms tag 合并看似能救 addNode 批量，但边是另一个 tag，救不了。
2. **【正确性·事件顺序】keydown vs paste 双建**：window keydown 先于 paste 事件；在 keydown 里 preventDefault 则 paste 不触发。若两边都放行 → 节点+图片双建。规避：clipboard 非空分支必 preventDefault；null 分支必不拦。
3. **【正确性·响应式代理】dagre/剪贴板数据直喂 reactive 对象**：nodes/edges 来自 v-model ref（Proxy），dagre 与 JSON 克隆对 Proxy 可能异常或带响应式包袱。规避：入参先 `JSON.parse(JSON.stringify())` 剥壳（cloneHistoryState 同款既定范式）。
4. **【性能·包体】dagre 进主包**：import 写在 CanvasBoard → 随 CanvasView chunk 拆包（Vite 现状路由懒加载）。**不**要 import 进 utils 公共入口或 main.ts。509 节点级 layout 同步 <150ms 可接受，不上 worker（规格 §8 不做）。
5. **【正确性·坐标语义】dagre 返回中心点、vue-flow position 是左上角**：必须减 width/2、height/2，否则节点整体右下偏移半个身位、「对不齐」。子图锚定用左上角 bbox 比对，同口径。
6. **【正确性·id 撞】批量粘贴同毫秒**：`node-${Date.now()}-${seqCounter++}` 沿用；边 id 也要 seq（cloneEdgeSeq 范式），禁裸 Date.now()。
7. **【正确性·label×@引用】撞名加序号改变量、@文本不动**：粘贴节点 label 被 uniqueLabel 改写（图1→图1 1），但**不重写**提示词里的 @图1（仍指原节点）。这是规格口径（内容引用非结构）；若 P3 手贱做引用重写 → 副本与原脱钩语义破裂（重生成命中错误上游）。单测钉死。
8. **【坑·focus】window keydown 与画布失焦**：用户点了属性面板后 Ctrl+C 仍应复制节点（window 级监听天然覆盖）；但焦点在面板输入框时必须放行（isEditableTarget 守卫，onWindowKeydown 第一行）。与 Delete 键同一守卫，勿复制两份判定逻辑。
9. **【坑·fitView 时序】位置写完立即 fitView 读旧布局**：vue-flow 位置应用在渲染周期生效。规避：`nextTick()` 后再 `vfFitView({duration:300})`（CanvasView focusNodeById 既有 nextTick 范式）。
10. **【正确性·组拉入边界】子图模式组员并集只在「选中含组成员」时发生**：全图模式无此步；选中纯组外节点不得把组员卷进来。组件测钉死（Chunk 2 验证 ④）。
11. **【性能·超大粘贴】Ctrl+C 500 节点 Ctrl+V**：JSON 深拷贝 500 节点 <10ms、批写同步一次——可接受；不设上限（规格未限），文档建议大子图用批量生成替代。
12. **【坑·snap 网格】整理坐标若不取整 16**：拖一下任何节点会跳格（snap-to-grid 生效瞬间视觉跳动）。computeAutoLayout 末统一 round16 钉死。

---

## 三、安全检查清单（对照 P1 安全策略 §5，P3 逐项验）

- [x] **鉴权**：零新端点、零后端改动；保存仍走既有画布 PUT（认证+归属校验不变）——P3 无需动作，回归即可。
- [x] **输入校验**：剪贴板内容仅来自本画布选中集（非用户任意输入），无注入面；dagre 输入为内部结构化数据。
- [x] **XSS**：粘贴/布局不渲染任何新 HTML（label 经既有文本渲染路径）；无 v-html。
- [ ] **依赖供应链**：`@dagrejs/dagre` 用正式版本区间锁 1.x（禁 `*`/next），pnpm-lock 提交；安装前核对其 npm 维护状态与周下载（P3 动作）。
- [x] **审计/日志**：纯前端本地计算，无敏感数据出网；不新增日志。

---

## 四、功能联动点清单（含反向/边界）

1. **Ctrl+C × 无选中（反向恢复）**：无节点选中按 Ctrl+C → 清 clipboard 且不 preventDefault → 之后 Ctrl+V 走原生图片粘贴链。**必测反向**：复制过节点→无选中 Ctrl+C→Ctrl+V 外部图 → 建图片节点而非节点副本。
2. **Ctrl+V 三级优先**：内部剪贴板节点 > 剪贴板图片文件 > 浏览器默认。半选边界：焦点在输入框时三级全让位（正常粘文本）。
3. **粘贴 × 撤回/重做**：一步撤=节点+边同快照消失；redo 一步整体回来。撤回后 clipboard 保留（可再粘，新 id 不撞）。
4. **label 去重 × @引用**：批内同名+画布既有名三级去重；@文本永不重写。边界：原节点被删后粘贴体 @引用 → brokenMentions 既有兜底（不新增处理）。
5. **一键整理 × 选中态**：选中≥1=子图模式（组整组拉入、锚定原位）；Esc 清选后点按钮=全图模式。**反向**：全图整理后撤销→旧位置全还原（含未选时）。
6. **整理 × 「只看关联」隐藏节点**：隐藏节点参排（可见性是会话态，布局是结构）；整理后隐藏态不变。
7. **整理 × 组**：memberIds 零改动；包围盒 rAF 跟随新位置；组头改名/解散不受影响。
8. **整理 × 运行中节点**：位置动、taskId/轮询链不动（updateNodeData 回写不依赖 position）。生成完成定型尺寸（320×320）与 dagre 估算尺寸同源（data.width/height），无跳变冲突。
9. **整理 × 保存**：单次 structure-changed → 防抖保存落库一次；保存失败徽标链复用。刷新后整理结果保持（position 已持久化）。
10. **工具条 × disabled 边界**：无节点禁用；子图模式选中 1 个孤立节点 → 点击无视觉变化（合法 no-op，不报错）。

---

## 五、运维考量清单（7 类逐条落字）

| 类 | 考量 | 决策 |
|---|---|---|
| 可观测性 | 本地纯计算无后端路径，无日志必要 | **不做**：不埋日志/指标（无服务端参与，浏览器侧排障靠复现+人工用例）。后续若接前端监控 SDK 再纳入 |
| 配置开关 | 新交互可能误触（Ctrl+V 撞已复制内容） | **不做**独立开关：行为可逆（Ctrl+Z 一步撤）+ 无选中 Ctrl+C 即恢复旧通道，等价于天然开关；回滚=revert 代码 |
| 可回滚 | 零 DB/零迁移 | **做**：纯前端 revert 即回现状；画布快照字段未新增，旧版本打开已整理画布=普通位置数据，前向兼容 |
| 限流/熔断/降级 | dagre 纯本地无外部依赖 | **不做**：无第三方调用；超大画布（>2000 节点）同步计算的卡顿风险以「后续 worker 化/上限提示」挂规格 §8 不做清单 |
| 运维入口 | 无运维面（用户自服务功能） | **不做**：无脏数据修复场景（结构快照原子落库，撤回栈前端态） |
| 告警阈值 | 不适用 | **不做**（无服务端指标） |
| 容量/性能预案 | 画布节点数增长 → layout/保存变慢 | **后续再说但落字**：500 节点 ≤150ms 目标已在规格 §5；超大盘 dagre 换 worker/虚拟化属「无限画布大图性能」独立议题，不混入本轮 |

---

## 六、出口条件自检

- [x] plan.md 完成，5 chunk 步骤清晰、文件明确（每 chunk ≤20）、含验证步骤
- [x] 技术坑 12 条（含历史步拆裂/事件双建/坐标语义三大正确性坑）
- [x] 功能联动点 10 条（含反向恢复、三级优先、组拉入边界）
- [x] 运维考量 7 类每条落字（做/不做/后续再说 明确）
- [x] **用户明确许可开始实现**（2026-08-28「好，开始吧」，P3 已执行完毕）

## 六·补 P3 执行记录（2026-08-28，全 chunk 落地）

| Chunk | commit | 验证 |
|---|---|---|
| 1 dagre+autoLayout | `2b7b7ed0` | autoLayout.test 10 例绿；tsc 0 错（偏差：全图模式也锚定旧 bbox 左上角，优于归一 0,0） |
| 2 一键整理接入 | `404b77ab`+收窄补 `f451eb9d` | CanvasBoard +5 例（LR 序/单步撤回/子图/组拉入/disabled）；22/22 绿 |
| 3 canvasClipboard | `6b639e0e` | canvasClipboard.test 9 例 + nodeClone 11 例回归绿 |
| 4 Ctrl+C/V 接入 | `1095e04a` | CanvasBoard +7 例（复制/粘贴重映射/+32 错开/一步撤/输入框放行/通道恢复/脱钩直查）；29/29 绿 |
| 5 文档同步 | 本 commit | vitest 全量 122 文件/901 用例绿 + tsc 0 错；测试方案 J1-J8/K1-K10、feature-map/user-ops/问题单挂注记 |

---

## 七、术语表

- **dagre**：把有向图自动分层摆整齐的布局库（上游左、下游右、层间等距）。
- **诱导边**：两端都在选中集里才算的连线（A→B 都选中算，A→C 只有 A 选中不算）。
- **包围盒（bbox）**：圈住一组元素的最小矩形，粘贴对齐/子图锚定都用它。
- **pushHistory tag 合并**：同标签 800ms 内的多次变更只留一条撤回记录（防批量操作拆成 N 步）。
- **直批**：绕过逐个 addNode/addEdge，一次 pushHistory+一次结构事件批量写入（保证「一次粘贴=一步撤回」）。
- **锚定**：子图重排后整体平移回原包围盒左上角，让没选中的邻接节点视觉上不被拉扯。
