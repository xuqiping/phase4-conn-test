# 修复XI README（右键菜单 + 官方库 + 两级词汇 + 组=大节点）

> 对应 spec `docs/specs/人工测试遗留问题修复XI设计.md`｜plan `docs/plans/人工测试遗留问题修复XI.plan.md`
> 轮序：A 右键菜单 ✅ → B 官方库 ✅ → C 两级词汇 ✅ → D 组大节点 ✅ → E 文档收尾 ✅ → P4 运行验证 ✅（冒烟 27/27 修 4 bug `9fa0f25c`；交叉 review 8 发现修 7 驳 1，后端 2735/2735+前端 1077/1077）
> 进度明细：[开发进度1.md](开发进度1.md)（A）[开发进度2.md](开发进度2.md)（B）[开发进度3.md](开发进度3.md)（C）[开发进度4.md](开发进度4.md)（D）[开发进度5.md](开发进度5.md)（P4）

## 用户地图

### ① 画布右键菜单（2x 未解决①）

- **谁用**：所有在无限画布上搭流程的创作者。
- **场景**：想在某个位置加节点，懒得去左上角拖调色板；或想粘贴/撤销时手已在鼠标上。
- **效益**：空白处右键 → 两栏菜单（添加节点 7 类 + 粘贴/撤销/重做/一键整理），节点落在右键点的位置；菜单项禁用态实时（没复制过=粘贴灰）；Esc 只关菜单不清框选。

### ② 官方库大卡片（2x 未解决②）

- **谁用**：需要高质量官方素材的创作者；管理员发布官方包给全站用。
- **场景**：新画布想直接用官方调好的提示词/图片/视频资产，不想自己从零配。
- **效益**：调色板底部「官方库」→ 大卡片双栏（左选包右列资产，按包 mediaTypes 词汇序分组）；点「选择」= 资产节点落视口中心即用；失败 toast + 画布零残壳可重试。官方口径=仅管理员发布（公众池分享不算）。

### ③ 叙事角色两级词汇（2x 未解决③）

- **谁用**：资产多的项目团队（尤其导演/制片管角色分类）。
- **场景**：「人物」下资产几十个，想再分老人/青年/孩童细管。
- **效益**：编辑分类两级（子类全局唯一名；删子类资产归父级、删一级归通用不丢资产）；矩阵左栏两级筛选（点一级=含全部子类，徽标聚合计数；点子类=精确）；一键分镜/画布入库下拉都认子类。存量数据 V169 自动迁移。

### ④ 组=大节点（2x 未解决④）

- **谁用**：用分组收纳复杂画布的创作者。
- **场景**：一组镜头要整体搬位置/复制到别处继续连上下游。
- **效益**：点组框空白=选中整组（高亮，与节点选中互斥）→ 按住拖=整组跟手移动（松手才保存）；框选包含**全部**组员复制=粘贴出新组（名去重/成员全新/上下游连线克隆，⛓ 开关治边不治壳）；半含=平节点照旧；选组按 Delete 零动作（防误删）；Ctrl+Z 一步撤整组。

## 简要技术说明

- **A 右键**：`CanvasBoard.vue` boardRoot `contextmenu` 拦截，命中 pane 空白才开菜单；菜单项数据单源 [paletteItems.ts](../../../frontend/src/components/canvas/paletteItems.ts)；落点用 `screenToFlowCoordinate` 换算。
- **B 官方库**：新 [OfficialLibrary.vue](../../../frontend/src/views/canvas/OfficialLibrary.vue) 大卡片；后端 `AssetPublicPoolController` 增 `official=true` 过滤（服务端不信前端）；插入复用画布既有 asset 节点 resolve 链。
- **C 两级**：`narrative_roles` JSON 从字符串数组改 `[{key,children[]}]`，Flyway [V169](../../../backend/src/main/resources/db/migration/V169__two_level_narrative_roles.sql) 幂等迁移；读/入参双容错（旧格式自动升级）；筛选与一键分镜服务端展开含子类。
- **D 组**：组层点击捕获段命中 `groupBoxes` → 点选分层（Q6）；越阈 4px 整组拖动 rAF 合帧；剪贴板 [canvasClipboard.ts](../../../frontend/src/components/canvas/canvasClipboard.ts) 增 `groups`/`groupCrossEdges` 两池（完全包含才收组，组端点边分治：组进板→新组引用，半含→照修复X 连原组）；粘贴同栈帧重建组=一步撤。
- **测试**：全量 vitest 1077/1077 + vue-tsc 0 错 + 后端 2735/2735（P4 冒烟 27/27 + review 修 7 各带回归锁）；人工项 S1-S19 见 `docs/测试方案/人工测试遗留问题修复XI测试方案.md`。

## 文档索引

| 文档 | 位置 |
|---|---|
| 测试方案（S1-S19） | `docs/测试方案/人工测试遗留问题修复XI测试方案.md` |
| Feature Map 增补 | `docs/feature-map/无限画布创作页.feature-map.md`、`docs/feature-map/项目资产库.feature-map.md`（2026-09-02 增补节） |
| User-Ops 增补 | `docs/user-ops/无限画布创作页用户操作手册.md`、`docs/user-ops/项目资产库用户操作手册.md`（2026-09-02 增补节） |
| Help 中心 | `docs/help-articles/21-assets-basics.md`（两级一行） |
| 问题单 | `人工测试问题/2x. 资产库和无限画布.md`（四项挂「已实现，待人工验证（修复XI）」） |
