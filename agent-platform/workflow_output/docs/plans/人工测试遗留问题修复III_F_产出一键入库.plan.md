# Chunk F · 项目组产出一键入库（17x#1）

> 规格 §11。复用既有入库桥，前端为主。
> **已完成（2026-08-26）**：F1 判重（genMeta JSONB taskId+imageIdx 文本匹配，同项目命中返 duplicate+既有 assetId；存量无 imageIdx 行向前兼容）+ exists-by-source 批量端点；F2 产出列入库列（复用三个既有入库弹窗，图片多图任务默认第 1 张）+已入库 tag（一条 IN 回填）。验证：AssetMediaBridgeServiceTest 8/8、groupOutputImport 6 用例、前端 822 全绿+vue-tsc 0、后端全量绿。**偏离**：未建真 PG IT（资产桥测试无 IT 先例，判重/越权由 service 单测+既有 viewer 传播用例覆盖）；图片多图任务整任务入库默认第 1 张（其余张走生成页逐张，MVP 口径）。见 [开发进度6](../../开发进度/人工测试遗留问题修复III/开发进度6.md)。

## F1. 后端：from-media 幂等判重 ✅

- 文件：`asset/controller/AssetMediaBridgeController.java`、`asset/service/*`（from-media 落库服务）
- 伪代码：
  ```
  POST /api/assets/from-media（现有）加判重: 同 (project_id, source_task_id) 已存在 → 返回 200 data:{duplicate:true, assetId}（不重复建资产）
  CHAT 入库走现有 /assets/projects/{id}/assets（SaveChatToAssetDialog 链路不动）
  权限: 目标项目 OWNER/EDITOR 服务端校验已有（确认，缺则补）
  ```
- 验证：IT 同任务两次入库→第二次 duplicate；越权项目 403。

## F2. 前端：产出 tab「入库」✅

- 文件：`views/ProjectGroupsView.vue`（产出 tab）、目标项目选择复用（抽 `frontend/src/composables/useAssetTargetProjects.ts` 或复用 SaveChatToAssetDialog 现项目过滤逻辑）、`components/chat/SaveChatToAssetDialog.vue`（如需抽公共）
- 伪代码：
  ```
  媒体行「入库」→ 目标项目下拉(OWNER/EDITOR 且含对应媒体类型；空态「无可用项目」禁用按钮)
            → POST from-media(taskId) → duplicate?「已入库」: 成功 toast
  CHAT 行「入库」→ 打开 SaveChatToAssetDialog 预填该轮内容
  已入库态: 行首 tag「已入库」（加载时批量查该组产出 taskId 的入库状态，一条 IN 查询接口
            GET /api/assets/exists-by-source?taskIds=… —— 新增轻端点或复用列表过滤）
  权限: 入库按钮按产出可见性（member_visibility）显隐，服务端权限兜底
  ```
- 验证：vitest 判重态/空态；手工媒体+CHAT 各入库一次、二次点显已入库、刷新仍显。

## 验证收口

- 后端 `mvn test`；前端 vue-tsc/vitest；17x 文件勾销待人工复验后。
