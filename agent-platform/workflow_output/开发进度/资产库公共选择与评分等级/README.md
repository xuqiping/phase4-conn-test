# 资产库公共选择与评分等级 · README

> 功能闭环：2026-08-18。计划 6 步全部完成，后端/前端单测全绿。
> 入口：[计划](../../docs/plans/资产库公共选择与评分等级.plan.md) · [规格](../../docs/specs/资产库公共选择与评分等级设计.md) · [测试方案](../../docs/测试方案/资产库公共选择与评分等级测试方案.md) · [开发进度1](./开发进度1.md)

## 解决了什么问题（用户地图）

| 谁 | 场景 | 之前 | 现在 |
|---|---|---|---|
| 图片/视频生成用户 | 生成时从资产库选素材 | 只能选我的/共享项目，公共池资产够不着 | 选择器加「公共池」来源，官方/开放项目直接选用 |
| 项目 OWNER | 分享项目给别人 | 弹窗打开「无数据」，必须先输入用户名才有列表 | 打开即展示 ≤50 人候选，输入用户名=筛选 |
| 所有人 | 看评分 | 只见数字 88 分 | 数字旁带等级徽章 A+/A/B/C/D；打分实时变级；等级=快捷筛选 |
| 项目 OWNER | 发布到公众池怕资产被白嫖复制 | 发布后任何人可复制副本 | 发布时自选「允许公共用户复制资产」开关；关掉后公共用户只能引用不能复制，项目成员不受限 |

## 技术说明（按 6 步）

1. **公共池摘要补 `mediaTypes`**（`PublicProjectSummaryVO` + `AssetPublicPoolService.listPublic`）：单查询补列零 N+1，选择器按图片/视频过滤公共池项目。VO 白名单其余不变（narrativeRoles/assets 等仍不外泄）。
2. **分享候选开箱即载**：根因=前端空关键词不发请求。后端 `searchCandidates` 空关键词 LIMIT 20→50；`ShareDialog` watcher 打开即 `searchCandidates('')`，清空关键词恢复全量。
3. **评分等级后端派生**：`AssetGrade.fromScore` 唯一真相源（null→null；≥95 A+ / ≥90 A / ≥80 B / ≥70 C / 其余 D；均分先 `Math.round` 再映射）。`AssetScoreVO`/`AssetVO` +`ownerGrade`/`memberAvgGrade` 派生字段，两处装配点（单资产 getScore、列表 assembleList），零新查询。
4. **前端等级 UI**：`constants/assetGrade.ts` 镜像后端常量 + 对齐单测防双份漂移。卡片/详情徽章只渲染 VO 字段；打分 slider 实时等级（`myGradeLabel` 已存分 / `liveGradeLabel` 跟草稿拆分，拖动不跳已存徽章）；筛选条第 3 下拉「等级」选中即覆写 scoreMin/scoreMax（A+=[95,100]…D=[0,69]），清除等级不清手动区间，gradeValue 不入 AssetFilter（后端契约零变化）。
5. **复制管控后端**（V132，原名 V132 因撞已占版本号改名，见知识库开发进度2）：`asset_projects.allow_public_copy BOOLEAN NOT NULL DEFAULT TRUE`（存量=允许零变化；unpublish 不清列，再发布回显）。`copyCurrent` 闸：公共池=true 且 allow=false 且非成员 → `ASSET_COPY_FORBIDDEN(40302)`。公共 VIEWER 与成员 VIEWER 同为 AssetRole.VIEWER，用 `AssetAclService.isMemberOrOwner` 查成员表区分（成员豁免）。copy 端点 +`@AuditLog`，拒绝路径走 aspect Throwable 分支自动记 FAIL。VO 透出用 `!Boolean.FALSE.equals`（null 视为 TRUE 兼容旧后端/存量）。
6. **前端发布开关**：发布弹窗「复制权限」n-switch（默认开、回显上次值、submit 携带 `allowPublicCopy`）；项目页复制按钮 `canCopyAsset = isPublicViewer && allowPublicCopy !== false`（false 不渲染非置灰；后端 403 兜底直调 API）。

## 关键坑（沉淀）

- `Map.of` 不可变 Map 对 null key 的 `get` 直接抛 NPE（非返 null）——查表前显式判空。
- Mockito 严格模式（PotentialStubbingProblem）：service 新增 `selectById(projectId)` 调用会打破既有测试对其他参数的 stub——受影响存量测试补新参数 stub。
- 滑杆默认 50 分属 D 档（<70），不是 C——档位以常量表为准，勿想当然。
- 双份等级常量（后端 Java / 前端 TS）靠镜像对齐单测锁死，改一处必改另一处。

## 验证状态

- 后端：`Asset*Test` 全绿（等级边界 94/95/89/90/79/80/69/100/0/null、均分取整跨档 94.5→95→A+、copy 三态、发布 null 沿用/显式覆盖/再发布保留、候选 50 上限、mediaTypes 透传）。
- 前端：80 文件 534 用例绿；`vue-tsc` 零错。
- Playwright 人工用例（P/S/G/C 四组）→ Phase 4 统一跑，见[测试方案](../../docs/测试方案/资产库公共选择与评分等级测试方案.md)。
