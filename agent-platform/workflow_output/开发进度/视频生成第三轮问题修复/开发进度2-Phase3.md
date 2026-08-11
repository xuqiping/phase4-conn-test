# 视频生成第三轮问题修复 · Phase 3 进度

日期：2026-08-11。

## 已实现

- Chunk 1：`GET /api/media/tasks?q&from&to&limit` 服务端筛选、300ms 防抖和陈旧响应保护。
- Chunk 2：详情参数/输入附件摘要、历史表单恢复、下线模型只读和失权附件禁用。
- Chunk 3-4：历史视频与资产选择器媒体懒预览。
- Chunk 5：MentionTextarea 用统一 DOM→token 序列化计算光标，支持连续多次 `@`。
- Chunk 6：删除业务任务总超时，后端单次非阻塞查询退避、画布无限可取消轮询。
- Chunk 7：实际 Provider body 单次构建、POST 前保存脱敏快照、详情双页签 JSON 查看/复制。

## 关键文件

- 后端：`MediaGenQueryService`、`MediaGenTaskWorker`、`MediaGenTaskTxService`、`ArkSeedanceProvider`、`MediaTaskVO`、`PreparedMediaRequest`。
- 前端：`VideoGenView.vue`、`CanvasView.vue`、`MentionTextarea.vue`、`MediaTaskRequestDetails.vue`、媒体预览组件与轮询/恢复工具。
- 测试：Provider 请求体/快照、Worker 状态机、查询契约、组件懒加载/复制、多次 @、持续轮询。

## 验证记录

- 功能存档提交：`55f2ed48`（`fix(media): complete video history and polling`）。
- 后端全量：`mvn test` 共 1449 测试、0 failure、0 error；`mvn compile` 成功。
- 前端全量：57 个测试文件、374 个测试全部通过；`npm run build` 成功。
- `git diff --check` 通过；仅有仓库既有 LF→CRLF 提示。
- 项目不存在 `scripts/check_all.bat/.sh`，使用 `mvn test`、`mvn compile`、`npm test`、`npm run build`、`git diff --check` 作为等价门禁。
- 浏览器真任务与真实第三方链路按用户要求留到 Phase 4。

## 下一步

进入 Phase 4，按测试方案验证历史筛选/恢复、行内播放、连续 `@`、真实请求快照以及长期 RUNNING/网络异常/重启恢复。
