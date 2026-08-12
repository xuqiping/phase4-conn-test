# 积分系统 7x 问题修复 · 功能 README

> 本目录记录 7x 积分系统人工测试问题修复（4 问题 + 1 用户追加审查需求）的开发过程与产物索引。

## 这是什么

修复 `workflow_output/人工测试问题/7x_积分系统.md` 的 3 个未解决问题 + 用户追加的视频生成审查需求：
1. **7x-1** 图片模型设置单价报 500（实际所有 kind 创建价表都会 500，根因 PG 类型不匹配）
2. **7x-2** 价目表需导出 / 按模板上传（联动全局供应商未配置模型，区分 LLM/图片/视频）
3. **7x-3** 视频需区分有无参考视频定价（seeddance 有参考 10元/无参考 20元）
4. **7x-4（追加）** 视频生成后查看实际推送参数 + 是否有视频参考标志（含无限画布）

## 谁用 / 什么场景 / 什么效益

- **管理员**：批量配价/改价（模板+导入比逐条新增快）；为视频模型配双价（参考视频成本不同）；审查任务计费是否正确
- **所有用户**：视频任务详情/画布明确标注是否有参考视频，便于核对计费

## 技术说明（关键点）

- **500 根因**：`LlmProviderMapper.selectByIdForUpdate` 用 `deleted = false`，PG 的 `deleted` 是 INTEGER → `operator does not exist: integer = boolean` → 兜底 500。改 `= 0`
- **价表导入**：镜像 LLM 供应商那套（DTO + upsert + 200 上限 + 逐行容错），upsert 按 `(providerId+model+kind+hasReference)`
- **视频参考定价**：V95 加 `has_reference BOOLEAN NOT NULL DEFAULT FALSE`；`findEffective` 加谓词 + fallback 到 false 行（不区分的模型配 1 行即可）
- **审查标志**：`MediaTaskVO.hasReference` 计算字段（按 inputAttachments 里 `kind=="video"` 算，list/detail 都返）；首尾帧图（kind=="image"）不算参考视频
- **Canvas 审计**：推送参数已脱敏落库在 `request_config.providerRequestSnapshot`，**无需新 DB 列**，Canvas 只需保留字段 + 接面板

## 产物索引

- 实现计划：[../../docs/plans/积分系统7x问题修复.plan.md](../../docs/plans/积分系统7x问题修复.plan.md)
- 测试方案：[../../docs/测试方案/积分系统7x问题修复测试方案.md](../../docs/测试方案/积分系统7x问题修复测试方案.md)
- feature-map（已更新）：[../../docs/feature-map/积分计费系统.feature-map.md](../../docs/feature-map/积分计费系统.feature-map.md)
- user-ops（已更新）：[../../docs/user-ops/积分计费系统用户操作手册.md](../../docs/user-ops/积分计费系统用户操作手册.md)
- 问题清单（已标 ✅）：[../../人工测试问题/7x_积分系统.md](../../人工测试问题/7x_积分系统.md)
- 开发进度总览：[开发进度总览.md](开发进度总览.md)

## commit 列表

1. `feat(billing): 7x-1/3 修复价表500 + 视频has_reference定价维度`（Chunk A+C+D）
2. `feat(billing): 7x-2 价表导出/导入/模板下载 + 修复存量测试构造`（Chunk B）
3. `feat(media): 7x-4 视频任务 hasReference 审查标志 + 推送参数展示`（Chunk E）
4. `feat(canvas): 7x-4 画布视频任务审计面板接入 + 参考视频标志`（Chunk F）
5. `docs(billing): 7x 文档同步 + 测试方案 + 进度目录`（Chunk G）
