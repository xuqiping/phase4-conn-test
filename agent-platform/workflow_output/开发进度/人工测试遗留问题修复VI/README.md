# 开发进度 · 人工测试遗留问题修复VI（画布粘贴/拖拽/副本连线/提示词/附件URL/参数对齐）

> 2026-08-27~28。规格 `docs/specs/人工测试遗留问题修复VI设计.md`；计划 `docs/plans/人工测试遗留问题修复VI.plan.md`（VA~VF）。
> 对应问题单：`人工测试问题/2x. 资产库和无限画布.md` 未解决 1~4 + 规格追加项 5/6。
> 测试方案：`docs/测试方案/人工测试遗留问题修复VI测试方案.md`（人工 H1-H12/L1-L6，随 VD commit 落盘）。

## VA · 剪贴板粘贴成图节点 + 本地拖拽成节点（commit `619fecae`，与 VB 同提交）

- `utils/mediaLimits.ts`（新）：`KIND_LIMITS = { image: 30MB, audio: 15MB, video: 50MB }` + `kindFromMime()` 单源；VideoGenView 旧常量改引此（防双源漂移）。
- `CanvasBoard.vue`：onDrop 加 OS 文件分支（无内部 vueflow MIME 且 files>0 → 逐文件分流，未知类型/超限 toast 拒）；根元素挂 paste 监听——焦点在 input/textarea/contentEditable 直接放行（不吞输入框粘贴），命中图片文件 emit 事件（落点=最近鼠标画布坐标，无记录回落视口中心）。
- `CanvasView.vue` `uploadAndCreateNode`：复用画布上传链，成功一个建一个节点（image/video 沿定型口径，audio 节点 audioMode='upload'+fileId+name），多文件 +40/+40 错位；建节点即 structure-changed→防抖保存。
- 测试：`mediaLimits.test.ts` 6 用例（MIME 分流/超限）；vue-tsc 0。

## VB · 副本连线克隆（同 commit `619fecae`）

- `nodeClone.ts` 新 `cloneEdgesForNode()`：原节点入边+出边各克隆一条改指副本；原边不动；自环→副本自环；副本不入组。
- `CanvasView.onCloneNode`：克隆后 applyEdges 追加 → structure-changed → scheduleSave。
- 测试：`nodeClone.test.ts` 11 用例（入/出边克隆、原边保留、新边 id 唯一、自环、无组加入）。

## VC · 画布提示词 maxlength + 建议文案（commit `b8fda394`）

- PropertyPanel 视频节点 MentionTextarea `:maxlength="8000"` + 浅色提示「官方建议 ≤500 汉字/1000 英文词」；图片节点同 8000 + 「≤300 汉字/600 英文词」。
- 后端 PROMPT_MAX_LEN=8000 零改动（@插值展开后超限由后端兜底）。

## VD · 附件图切签名 URL 传输 + 上限 8→30MB（commit `9e68287d`）

- `MediaReferenceUrlService.createMediaUrl` 泛化（图/视频同参同闸），createVideoUrl 兼容别名；Controller 端点放开 image/*（音频仍 data URI，规格 §8）。
- Worker：图片/视频附件走签名公网 URL，音频保持 base64；legacy refImageUrl 同切。KIND_MAX_BYTES image 8→30MB，错误话术自动推导。
- 测试：MediaGenTaskWorkerTest 3 用例改写 + MediaReferenceUrlServiceTest 增图 URL 用例（23/23 过）。
- 附两份测试方案文档（修复VI + 视频模型扩展）。

## VE · 画布视频节点参数 capability 对齐独立视频页（commit `a84534b7`）

- 模型下拉换 `/media/models`（MediaModelVO 带 capability，与 VideoGenView 同源）；比例/分辨率/时长全按能力动态，未选/加载失败回落保守兜底档不白屏。
- 新增「生成音频」（supportsGenerateAudio 显隐）/水印开关，写入 data 随提交透传；切模型收敛参数（分辨率取最近档不清空、时长夹取、不支持音频置 false、maxImages=0 清首尾帧残留）。
- @音频节点收 kind=audio 附件（音频N 独立编号）；参考区音频卡内嵌原生 audio 条自播。顺手修跨节点同 fileId 重复 push。
- 测试：vitest 870/870（capability 5 例 + 音频引用 5 例）；vue-tsc 0。

## VF · 文档收尾（本提交）

- 2x 未解决 1~4 挂「待人工验证（修复VI）」标记（不勾销）；feature-map/user-ops 无限画布创作页 2026-08-28 增补；变更记录补行；本 README。

## 验证与回滚

- vitest 前端全绿、vue-tsc 0、后端 Media 系测试全过（各 chunk 明细见上）。
- 回滚：无 schema 变更；逐 commit revert 即可（VA/VB 同提交整体回）。
- 人工测试 H1-H12/L1-L6 待 P4（真 Ctrl+V/真拖三种文件/副本连线/超长提示词/30MB 边界/参数联动）。

## 下一步

P4 跑测试方案 → 全绿后勾销 2x 未解决 1~4。
