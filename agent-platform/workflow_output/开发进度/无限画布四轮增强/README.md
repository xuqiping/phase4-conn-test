# 无限画布四轮增强 · README

> 功能闭环：2026-08-18。计划 9 步全部完成（含后端 transform/annotate），前后端单测全绿。
> 入口：[计划](../../docs/plans/无限画布四轮增强.plan.md) · [规格](../../docs/specs/无限画布四轮增强设计.md) · [测试方案](../../docs/测试方案/无限画布四轮增强测试方案.md) · [开发进度1](./开发进度1.md)

## 解决了什么问题（用户地图）

| 谁 | 场景 | 之前 | 现在 |
|---|---|---|---|
| 画布用户 | 长任务（视频 10min+）/临时断网 | 30s 弹「服务不可达」红 toast，会话被踢、任务像挂了 | 后台轮询豁免断路计数；toast 换「后台任务网络波动」；退避 5→10→30s 封顶；切回标签页立即补轮询 |
| 画布用户 | 节点尺寸 | 六类节点宽高全固定 | 选中节点四角拖柄改宽高（min 160×64），持久化刷新还原 |
| 画布用户 | 批量生成有上下游 | 同时提交上下游，下游拿到旧产出甚至插值失败 | 上游 SUCCEEDED 才释放下游；失败级联标灰跳过；分支并行 2；浮条可停调度 |
| 图片创作者 | 图片编辑 | 只能重新生成 | 翻转/旋转 5 操作产衍生图；8 色框选标注+逐框指令，可一键 AI 修改 |
| 画布用户 | @ 引用图/视频 | 提示词里只有文字占位符，不知道引用了什么 | 属性面板「参考」区徽标缩略（首帧/尾帧/图N/视频N），悬浮放大、点击全屏/播放 |
| 画布用户 | 看不清节点关联 | 全图连线一个色 | 选中即上下游闭包高亮、无关淡化；「只看关联」直接藏掉无关节点 |
| 画布用户 | 连线方向 | 上下进出，读图别扭 | 左进右出横向贝塞尔 |
| 画布用户 | 一批节点反复整体引用 | 只能逐个 @ | 框选「设为组」：彩框+组名，下游 @ 候选含组全员（组分节） |

## 技术说明（按 9 步）

1. **后台请求豁免+轮询退避**（4614a366）：`request.ts` 支持 `_background` config——跳过连续错误计数与清会话，toast 降级；`mediaTaskPolling` 退避 5→10→30s 封顶、成功归 5s、timeout 30s、visibilitychange 补轮。标记清单=getTask/fetchMediaBlob/fetchVideoBlob（帧提取是用户主动 POST 不豁免）。
2. **节点宽高**（1bb9591a）：`@vue-flow/node-resizer@1.5.1` 四角柄（spike 过兼容 core 1.41.5）；真源 `data.width/height` 单一字，style 由 loadSnapshot/addNode 推导、getSnapshot 剥离；resize 落库只在 `resizing:false` 一次 emit，防防抖保存风暴。
3. **连线左右**（43da6532）：Handle 两处 Position 改向即成——`getBezierPath` 自动跟随。
4. **依赖调度**（22178e3a）：`batchRunner.runDependencyScheduled` 两段式（submit 占槽/awaitTerminal 不占槽）；429 冷却重排队尾不占槽（cooling 计数防误判收工）；60min 看门狗+取消令牌；跳过态写 `status='skipped'` 标灰区分失败。
5. **关联高亮**（4de1892b）：`graphClosure.relatedClosure` 纯函数传递闭包（菱形弦边含）；`relatedInfo` computed 只依赖选集+边集，拖动不重算；`applyVisualClasses` 统一注入 class（会话态不入快照）。
6. **图片翻转/旋转**（fa74da69）：`POST /canvas/{id}/nodes/{nodeId}/transform-image`（源 fileId 从快照解析不信任客户端）；`ExifOrientation` 零依赖解析（JPEG APP1/TIFF ~100 行）；五 op 像素级单测+EXIF 归正先后序。
7. **彩色标注**（548e71e3）：服务端合成（`op=ANNOTATE` 同端点分流）：每框 30% 填充+描边+序号徽标（字号自适应）；`validateAnnotateBoxes` 拒任意 hex/越界/超 8 框；指令留前端拼 prompt（序号徽标=AI 定位锚）。AI 出口继承源节点生图参数，有 model 自动提交。
8. **@参考预览区**（5b406939）：抽 `collectCanvasRefs` 收集内核——提交序号化与预览徽标同源必然一致；`ReferencePreview` 懒加载（IO+LRU）；视频 poster 用 `<video preload="metadata">` 原生首帧（不走 /frames 防污染产出物）。
9. **节点组**（a8df80f5）：`CanvasGroup` 成员关系只存组侧；包围盒 onMove 视口跟踪+rAF 合帧；@候选并集 `expandGroupCandidates` 纯函数——命中组=组内任一成员∈祖先→组全员，孤立组不进候选（防越权引用未连通节点）。

## 关键坑（沉淀）

- vue-flow 无公开 `useViewport`：用 `onMove(({flowTransform}))` 钩子存 ref 跟视口。
- `/frames` 抽帧每次产新 stored_file——纯预览场景改 blob objectURL，别给产出物列表塞垃圾。
- 标注色叠加期望值按白底混色算（30% alpha 叠白），黑底算错返工过一轮。
- 快照剥会话态三件套：node.style/node.class/edge.class；新会话态（组色 `--group-color`）同口径剥离。
- axios 自定义 config 用下划线前缀（`_background` 同 `_retry` 既有约定）。

## 验证状态

- 后端：`VideoFrameServiceTest` 44/44（五 op 像素断言+EXIF+annotate 8 用例）、canvas 包 64/64。
- 前端：87 文件 588 用例绿；`vue-tsc` 零错。
- L1-L7 联动手测（含断网 30min 长任务）→ Phase 4 统一跑，见[测试方案](../../docs/测试方案/无限画布四轮增强测试方案.md)。
