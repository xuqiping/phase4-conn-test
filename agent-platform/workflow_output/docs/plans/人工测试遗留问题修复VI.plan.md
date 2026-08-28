# 计划 · 人工测试遗留问题修复VI（画布粘贴/拖拽/副本连线/提示词上限/附件URL/参数对齐）

> 规格：[人工测试遗留问题修复VI设计.md](../specs/人工测试遗留问题修复VI设计.md)（Phase 1 定稿，Q1~4 已拍板）。
> 硬闸门：本计划经用户许可后才进 Phase 3 实现。

## Chunk 总览（依赖顺序）

| # | 内容 | 层 | 依赖 |
|---|---|---|---|
| VA | 剪贴板粘贴成图节点 + 本地文件拖拽成节点（VI-1/2） | 前端画布 | 无 |
| VB | 副本连线克隆（VI-3） | 前端画布 | 无 |
| VC | 画布提示词 maxlength + 建议文案（VI-4） | 前端 | 无 |
| VD | 附件图 URL 传输 + 30MB（VI-5） | 后端+前端镜像 | 无 |
| VE | 画布视频节点参数 capability 对齐（VI-6） | 前端 | 无 |
| VF | 文档收尾 + 人工测试标记 | 文档 | VA~VE |

VA~VE 相互独立可并行；VF 收尾。

---

## VA · 粘贴 + 拖拽成节点（VI-1/VI-2）

- **目标**：外部复制图片 Ctrl+V、OS 拖文件进画布 → 上传并落对应类型节点。
- **动作**（伪代码）：
  1. 新 `utils/mediaLimits.ts`：`KIND_LIMITS = { image: 30MB, audio: 15MB, video: 50MB }` + `kindFromMime(mime)`（image/*→image、video/*→video、audio/*→audio、其他 null）。VideoGenView :676-680 改引此单源（行为不变，防两处漂移）。
  2. CanvasBoard.onDrop（:548-559）加分支：`无内部MIME && files.length>0` → 逐文件 `kindFromMime` → null 则 toast「支持拖入图片/视频/音频」；超限 toast 即拒；否则 emit 事件带 `{file, kind, 落点screenPos}` 给 CanvasView。确认 dragover 已 preventDefault（palette 拖拽已通，缺失则补）。
  3. CanvasBoard 根元素挂 paste 监听（onMounted 加/卸载移除）：焦点在 input/textarea/contentEditable → 直接 return（不拦正常粘贴）；`clipboardData.items` 含 kind=file 的图 → emit 同上事件，位置=最近鼠标画布坐标（新增 mousemove 记录，无记录回落视口中心）。
  4. CanvasView 新 `uploadAndCreateNode(files)`：复用画布上传链（onUploadFile :1647-1674 同款，api/canvas.ts:248-255）→ 成功一个建一个节点：image/video 沿 addNode 定型口径，audio 节点 `data.audioMode='upload'`+fileId+name；多文件 +40/+40 错位；建节点即触发 structure-changed→scheduleSave（:2132-2138）。失败 toast、不建节点。
- **涉及文件**：`utils/mediaLimits.ts`（新）、`components/canvas/CanvasBoard.vue`、`views/CanvasView.vue`、`views/VideoGenView.vue`（4 个）
- **依赖**：无
- **验证**：vitest（kindFromMime 分流/超限拒/paste 焦点守卫/多文件错位）；vue-tsc；人工（真 Ctrl+V、真拖图/视频/音频各一）。

## VB · 副本连线克隆（VI-3）

- **目标**：创建副本后，原节点所有入边+出边各克隆一条指向/发自副本；原边不动；副本不入组。
- **动作**（伪代码）：
  ```
  // nodeClone.ts 新函数
  cloneEdgesForNode(originalId, newId, edges):
    return edges.filter(e => e.source==originalId || e.target==originalId)
      .map(e => ({ ...e, id: 新id(),
                   source: e.source==originalId ? newId : e.source,
                   target: e.target==originalId ? newId : e.target }))
    // 自环边(source==target==original)→克隆成副本自环；handles/样式随展开保留
  // CanvasView.onCloneNode（:2549-2558）：克隆节点后
  clonedEdges = cloneEdgesForNode(原id, 副本id, edges.value)
  applyEdges(合并追加) → structure-changed → scheduleSave
  ```
- **涉及文件**：`utils/nodeClone.ts`、`views/CanvasView.vue`（2 个）
- **依赖**：无
- **验证**：vitest（入/出边克隆、原边保留、新边 id 唯一、自环成副本自环、无组加入）；人工（带上下游连线节点建副本，两侧连线齐全、原边未动、副本可独立重生成）。

## VC · 提示词 maxlength + 建议文案（VI-4）

- **目标**：画布视频/图片节点提示词限 8000 字符（平台闸门口径），界面附官方建议值文案。
- **动作**（伪代码）：
  1. PropertyPanel 视频节点 MentionTextarea（:366-380）加 `:maxlength="8000"`（组件 :271-273 已支持软截断）；下方一行浅色提示「官方建议 ≤500 汉字/1000 英文词，超长效果下降」。
  2. 图片节点提示词（:178-183）同加 `:maxlength="8000"` + 「官方建议 ≤300 汉字/600 英文词」。
  3. 后端 PROMPT_MAX_LEN=8000 与 400 校验零改动（@插值展开后超限由后端兜底，口径见规格 §8）。
- **涉及文件**：`components/canvas/PropertyPanel.vue`（1 个）
- **依赖**：无
- **验证**：vitest（maxlength 属性挂上、建议文案渲染、粘贴超长软截断）；独立视频页/聊天链回归不动。

## VD · 附件图 URL 传输 + 30MB（VI-5）

- **目标**：image 附件改签名公网 URL 传输（视频现范式），上限 8→30MB；消 base64 膨胀隐雷。
- **动作**（伪代码）：
  1. `MediaStorageService.KIND_MAX_BYTES`（:48-51）：`"image", 30MB`（audio/video 不动）。400 话术「（image ≤30MB）」随 Map 自动跟（:757-761 核实无独立硬编码文案）。
  2. `MediaReferenceUrlService`：createVideoUrl 范式泛化——新增 `createMediaUrl(fileId)`（或改签名带 kind），HMAC-SHA256、TTL 900s 同参。**P2 先核** MediaReferenceController 是否按 fileId 通用服务（视频链已 permitAll+签名校验；若路由按 kind 限定则扩 image，仍同面同闸）。
  3. `MediaGenTaskWorker.buildRequest`（:546-560）image 分支：`readAsDataUri(fileId)` → `createMediaUrl(fileId)`；audio 分支不动（仍 data URI，规格 §8 边界）。
  4. multipart 60/65MB（application.yml:11-15）不动——30MB 单文件兼容。
  5. 前端镜像：`utils/mediaLimits.ts`（VA 已建）image 30MB；上传预检（画布 VA 链 + PropertyPanel 图上传 + 独立页）读单源。
- **涉及文件**：`media/service/MediaStorageService.java`、`media/service/MediaReferenceUrlService.java`、`media/controller/MediaReferenceController.java`（核实/微扩）、`media/service/MediaGenTaskWorker.java`、`utils/mediaLimits.ts`（5 个）
- **依赖**：无（与 VA 的 mediaLimits.ts 文件共享——先建文件者补齐，实现时 VA/VD 顺序不分先后，文件一处定义）
- **验证**：junit（30MB 过/30MB+1 拒、话术含 ≤30MB、image 请求体为 URL 非 `data:` 前缀——WireMock 断言、签名过期 403 话术沿视频用例镜像）；真 PG 冒烟（29MB 图上传+生成任务提交）。

## VE · 画布视频节点参数 capability 对齐（VI-6）

- **目标**：画布视频面板比例/分辨率/时长/开关全按所选模型能力动态，与独立视频页同口径。
- **动作**（伪代码）：
  1. PropertyPanel 视频模型下拉数据源 :1264-1268/:1310 由 `llmApi.listVideoModels()`（无 capability）改 `mediaApi.listModels()` 过滤 VIDEO（MediaModelVO 含 capability，与 VideoGenView :506-526 同源）；`displayName/modelId` 字段映射核对（llm 侧叫法可能不同，切换后 node.data.model 存值口径不变=模型 ID）。
  2. 选中模型 → `cap = capabilityOf(data.model)`（列表缓存；未配置模型→保守兜底档+WARN 不白屏，capability 请求失败→回落现硬编码）：比例=cap.supportedRatios（含 adaptive+RATIO_LABELS）、分辨率=cap.supportedResolutions、时长 min/max=cap.minDuration/maxDuration（:398-415/:1254-1255 换源）。
  3. 新增开关：生成音频（cap.supportsGenerateAudio 显隐）、水印；写入 `data.generateAudio/watermark`。
  4. `onVideoModelChange`（新增，仿图片 onImageModelChange :1370-1393 + 独立页 :544-567）：切模型把 ratio/resolution/duration 收敛进新能力区间（越档回落最近合法值或默认），generateAudio 不支持则置 false。
  5. 首/尾帧选择器（:419-437）按 `cap.maxImages>0` 显隐。
  6. @音频引用：CanvasView mention 候选构建纳入 audio 节点（MENTION 过滤扩展）；`canvasVideoAttachments.ts` 增 audio 分支（仿 video :146-157：kind:'audio'、占位「音频N」、断链【断链】同口径）；`submitVideoOnly`（:1311-1333）generateAudio/watermark 改透传 data 真值（现 :1319-1320 恒 false）。
- **涉及文件**：`components/canvas/PropertyPanel.vue`、`views/CanvasView.vue`、`utils/canvasVideoAttachments.ts`、`components/canvas/MentionTextarea.vue`（候选过滤，若硬编码 image/video）（4 个）
- **依赖**：无
- **验证**：vitest（capability 驱动选项渲染：Cdance2.0→7 档含 adaptive/4K/4-15s/音频开关在；切模型收敛；@audio 占位「音频N」；断链）；vue-tsc；人工（切模型看面板、@音频提交附件含 audio、预估联动不破）。

## VF · 文档收尾 + 人工测试标记

- **动作**：①`人工测试问题/2x. 资产库和无限画布.md` 未解决 6 项挂「待人工验证（修复VI）」标记（不勾销）；②feature-map/user-ops「无限画布创作页」增补 2026-08-28 节（粘贴/拖拽/副本连线/建议文案/参数对齐/@音频）；③规格变更记录补行；④`开发进度/人工测试遗留问题修复VI/` README。
- **涉及文件**：4 类文档。**依赖**：VA~VE。

---

## 技术坑点预判

- **paste 误伤输入框**：监听必须挂在画布容器且先判焦点——否则 MentionTextarea 粘贴文本被吞。vitest 必测「焦点在输入框不拦截」。
- **drop 内外拖拽撞车**：palette→节点拖拽用 `application/vueflow` MIME，OS 拖拽无该 MIME——分支判定顺序先查内部 MIME 再查 files，二者互斥不叠加。
- **vue-flow 事件外泄**：CanvasBoard 的 emit 需在 defineExpose/emit 声明齐，漏声明=静默丢事件（:1038-1045 现状先例）。
- **mediaLimits 双源漂移**：VideoGenView 旧常量若不删，两处上限日后必漂移——VA/VD 谁先动谁把旧常量改引单源。
- **URL 可达性**：签名 URL 必须 Ark 公网可达（视频链已验证同面）；本地内网部署照搬视频链配置，无新增前提。
- **capability 缺失白屏**：模型未配置/接口失败必须兜底回落现硬编码档，画布不能因配置缺位瘫痪。
- **切模型收敛方向**：回落取「最近合法值」而非清空——清空丢用户已填内容，收敛保语义（720p 在新模型只支持 480p→落 480p）。
- **@音频占位编号独立**：图N/视频N/音频N 各自独立编号，不混排（attachmentMention.ts 独立页先例同口径）。
- **SCSS BEM 嵌套坑**（V 轮 PB-1 沉淀）：新样式类与兄弟平级，`&__x` 嵌 `&__y` 必死；vitest 测不出，vite build+人工看。
- **性能**：上传逐文件串行（复用现有并发口径不新开）；capability 列表一次拉取缓存组件周期，不每节点每渲染打接口。

## 安全检查清单（P3 逐项验证）

- [x] paste/drop 上传走既有认证端点（/api/canvas/{id}/upload），无新端点
- [x] 文件类型白名单（image/*、video/*、audio/* MIME 判定），未知类型拒
- [x] 30MB 服务端闸门维持（前端预检仅体验）；400 话术随 KIND_MAX_BYTES 单源
- [x] 签名 URL 同视频链：permitAll+HMAC+TTL 900s，不新增攻击面；Controller 若扩路由仍签名校验
- [x] 无 PII 新增日志；副本连线不触碰权限/归属模型

## 功能联动点清单（只列正向必漏 bug）

| # | 触发动作 | 联动对象 | 预期变化 | 边界（反向/取消/批量） |
|---|---|---|---|---|
| 1 | 拖/粘文件上传成功 | 画布节点+保存链 | 节点落点出现，防抖保存 | 失败 toast 不建节点；多文件错位不叠死 |
| 2 | 焦点在输入框 Ctrl+V | 输入框 | 正常粘文本，画布不拦截 | 焦点在画布空白才成节点 |
| 3 | 创建副本 | 画布连线 | 入+出边克隆一份 | 原边不动；自环成副本自环；不入组 |
| 4 | 切视频模型 | 比例/分辨率/时长/开关 | 按新 capability 收敛 | 未配置模型兜底硬编码；切回不恢复旧值（回落默认） |
| 5 | @音频节点 | rewrittenPrompt+附件 | 「音频N」占位+kind audio 附件 | 断链【断链】；maxAudios=0 模型不出候选 |
| 6 | 图>30MB | 前端预检+后端校验 | 双拦，话术 ≤30MB | 29MB 过；audio/video 上限不变 |

## 运维考量清单（7 类逐条落字）

- **可观测性**：**做（零新增）**——上传失败/签名失败 WARN 沿现状；不加指标。
- **配置开关**：**不做**——纯体验修复无高危面；上限即常量配置。
- **可回滚**：**做**——纯代码 revert；VD 回滚即回 8MB+base64 行为，无数据残留（URL 仅请求期）。
- **限流/熔断**：**不做**——无新外部依赖；Ark URL 链视频已验证。
- **运维入口**：**零新增**——大小上限改常量即可调；报错话术自描述。
- **告警阈值**：**不做**。
- **容量/性能**：**不做**——multipart 60/65MB 兼容 30MB；base64 消除反而降请求体峰值（96MB 隐雷→URL 几十字节）。

## 人工测试标记（自动化覆盖不了，P4 用）

1. 外部复制图→画布 Ctrl+V 成图片节点（真剪贴板手势）
2. 资源管理器拖图/视频/音频各一成对应节点；拖 .txt 被 toast 拒
3. 带上下游连线节点建副本→两侧连线齐全+原边未动+副本独立重生成
4. 29MB 图附件真跑生成成功（Ark 收到 URL 非 base64）；31MB 前端预检拒
5. 画布视频节点切 Cdance2.0→比例 7 档（含 adaptive）/4K/4-15s/生成音频+水印开关在；切换后越档值收敛
6. @音频节点进视频提示词→提交请求附件含 kind:'audio'，占位「音频N」

## 出口

- [ ] VA~VF 全绿 + 人工测试 6 项过 → 勾销 2x 问题单对应未解决项、回写规格变更记录
- [x] **硬闸门：未经用户明确许可不写任何实现代码**
