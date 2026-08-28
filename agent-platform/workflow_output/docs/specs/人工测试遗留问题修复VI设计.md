# 规格 · 人工测试遗留问题修复VI（2x 资产库和无限画布 收尾轮）

> SDD 特性级规格（Phase 1 产出）。实现须与本文件对齐；冲突时改实现或改本文档（注明原因）。
> 来源：[2x. 资产库和无限画布.md](../../人工测试问题/2x. 资产库和无限画布.md) 未解决 5 项 + 用户增补 1 项（2026-08-28）；外部调研与两轮拍板见 §2/§3。
> 前序：修复 III（§9）/IV（§5）/V（§3）已收；本规格为 2x 问题单最后一批。

## 1. 背景与代码现状事实（2026-08-28 探查）

| # | 现状事实 | 位置 |
|---|---|---|
| ①粘贴 | 画布无 paste 处理；MentionTextarea 的 onPaste 只取纯文本 | MentionTextarea.vue:371-375 |
| ②拖拽 | CanvasBoard.onDrop 只读内部拖拽 MIME `application/vueflow`，OS 文件丢弃（no-op） | CanvasBoard.vue:548-559 |
| ③副本 | cloneNodeForDuplicate 只克隆节点本体（平节点，不带边/组）；「副本完全独立」C-8 指产物零共享，不涉连线 | nodeClone.ts:14-35、CanvasView.vue:2549-2558 |
| ④提示词 | 画布视频/图片节点提示词无 maxlength；后端 PROMPT_MAX_LEN=8000 硬拦（>8000→400）；独立视频页有 maxlength=8000 先例 | PropertyPanel.vue:366-380/178-183、MediaGenTaskService.java:53-54/617-622、VideoGenView.vue:55 |
| ⑤附件图 | image 上限 8MB 是**平台自设**（KIND_MAX_BYTES 硬编码），非模型限制；传输走 base64 data URI；视频已走签名公网 URL（HMAC-SHA256 TTL 900s，permitAll 端点） | MediaStorageService.java:48-51、MediaGenTaskWorker.java:546-560、MediaReferenceUrlService.java:29-40 |
| ⑥参数 | 画布视频面板全硬编码：比例 6 档（缺 adaptive）、分辨率恒 4 档（任何模型显 4K）、时长写死 4-15、无生成音频/水印开关、模型下拉读 `/llm/user/models/video`（无 capability 字段）、切模型零收敛；独立视频页全 capability 驱动 | PropertyPanel.vue:389-415/462-469/1254-1255、api/llm.ts:83-89 |
| 通用 | 画布已有 audio 节点（upload 模式）；MENTION 候选只识别 image/video 节点；@引用组装在 canvasVideoAttachments.ts（image→图N、video→视频N，无 audio 分支） | PropertyPanel.vue:625-653、canvasVideoAttachments.ts:85-173 |

## 2. 外部调研结论（2026-08-28，官方文档）

- **官方提示词上限均为「建议值」，无硬上限**：
  - Seedance 2.0（视频，即 Cdance2.0）：建议中文 ≤500 字 / 英文 ≤1000 词；超长→生成质量下降，不报错。
  - Seedream 5.0（图片，lite/pro）：建议 ≤300 汉字 / ≤600 英文词。
  - 第三方（EvoLink）称 Seedance 上限 10000 token——非官方，不采信。
  - 平台唯一硬闸门=自建后端 8000 字符校验。
- **图片附件官方口径**：单张 ≤30MB、请求体 ≤64MB、大文件勿用 Base64（×4/3 膨胀）。现状隐患：9 张 8MB 图 base64 ≈ 96MB > 64MB 请求体上限，潜在必炸。

来源：[视频生成 API](https://www.volcengine.com/docs/82379/1520757)、[图片生成 API](https://www.volcengine.com/docs/82379/1541523)、[Seedream 4.0-5.0 提示词指南](https://www.volcengine.com/docs/82379/1829186)、[Seedance 2.0 提示词指南](https://www.volcengine.com/docs/82379/2222480)。

## 3. 用户决策（2026-08-28 拍板）

| # | 问题 | 决策 |
|---|---|---|
| Q1（⑤） | 附件图传输与上限 | **切 URL 传输（视频现范式），上限 8MB→30MB** |
| Q2（④） | 提示词上限口径 | 官方核实后**画布视频/图片节点提示词补 maxlength**（官方仅建议值 → maxlength 用平台自设 8000，界面附官方建议文案） |
| Q3（⑥） | 括号语义/范围 | **全对齐独立视频页**：参数 capability 化 + 生成音频/水印开关；上游图/视频仍靠 @节点 引用（现状） |
| Q4（③） | 副本带连线语义 | **连线克隆一份**：原节点连线保留，副本获得同样的入边+出边；产物零共享口径（C-8）不变 |

## 4. 功能需求

| 编号 | 需求 | 说明 | 优先级 |
|---|---|---|---|
| VI-1 | 剪贴板粘贴成图片节点 | 画布层监听 paste：clipboard 含图片文件（items kind=file）→ 走画布上传接口 → 在鼠标位置（无鼠标记录则视口中心）创建图片节点；多张错位排布；**焦点在输入框（input/textarea/contentEditable）时不拦截**（正常粘贴文本不受影响）；非图片剪贴板内容走浏览器默认 | P0 |
| VI-2 | 本地文件拖拽生成节点 | onDrop 增加分支：无内部 MIME 且 dataTransfer.files 非空 → 按 MIME 判类型（image/*→图片节点、video/*→视频节点、audio/*→音频节点 audioMode=upload）→ 逐个上传+在落点创建节点（多文件错位）；不识别类型 toast「支持拖入图片/视频/音频」；上传前按前端大小预检（与后端 KIND_MAX_BYTES 同步的镜像常量，超限 toast 即拒）；新节点走 structure-changed→防抖保存链 | P0 |
| VI-3 | 副本连线克隆 | 创建副本时：原节点所有入边（target=原）→ 新增 target=副本 的克隆边；所有出边（source=原）→ 新增 source=副本 的克隆边；新边 id、原边不动；边克隆后触发结构保存；**副本不入组**（组关系维持现状平节点口径）；产物独立（C-8）不变 | P0 |
| VI-4 | 画布提示词 maxlength+建议文案 | 视频/图片节点提示词加 maxlength=8000（MentionTextarea 已支持软截断）；输入区下方一行浅色建议文案：视频「官方建议 ≤500 汉字/1000 英文词，超长效果下降」、图片「官方建议 ≤300 汉字/600 英文词」；后端 8000 校验不动；@引用组装后超限仍由后端 400 兜底（maxlength 只管原始输入） | P0 |
| VI-5 | 附件图 URL 传输+30MB | ①KIND_MAX_BYTES image 8MB→30MB（audio 15MB/video 50MB 不动），报错话术随动；②Worker 请求组装 image 分支 base64 data URI→签名公网 URL（复用视频 createVideoUrl 范式：HMAC 签名、TTL 900s、permitAll 端点；服务泛化为 createMediaUrl(fileId) 或平移同款 image 版）；③前端大小预检镜像常量同步 30MB；④multipart 60/65MB 不动（30MB 单文件兼容）；音频/视频传输本轮不动 | P0 |
| VI-6 | 画布视频节点参数对齐独立页 | ①模型下拉改读 `/media/models`（MediaModelVO 含 capability），与独立页同源；②比例/分辨率/时长选项按所选模型 capability 动态生成（含 adaptive 档与 RATIO_LABELS、fast/mini 无 4K、时长 min/max 区间）；③补生成音频开关（supportsGenerateAudio 显隐）、水印开关；④切模型时参数收敛（越档值回落新能力区间，仿独立页 applyCapabilityConstraints）；⑤首/尾帧选择器按 capability.maxImages 显隐；⑥MENTION 候选扩展 audio 节点 + canvasVideoAttachments 增 audio 分支（kind:'audio'、占位「音频N」），maxAudios>0 时可见——Seedance 2.0 支持参考音频，画布已有音频节点可引 | P0 |

## 5. 非功能需求

- **性能**：粘贴/拖拽上传走既有画布上传接口与并发现状；capability 列表请求一次缓存复用（独立页同款）；URL 签名零 IO 开销（HMAC 内存计算）。
- **安全**：签名 URL 与视频链同面（permitAll 端点+签名+TTL，无新增攻击面）；30MB 上限仍是服务端闸门（前端预检只是体验）；paste/drop 创建节点走既有保存链（认证+归属校验不变）。
- **可回滚**：VI-5 纯代码 revert 即回 8MB+base64；无 DB 迁移、无数据迁移；旧画布快照零影响（新字段缺省=现行为）。

## 6. 数据模型

无变更。画布快照结构（nodes/edges/groups）已含全部所需字段；副本克隆边即普通 CanvasEdge。

## 7. 测试策略

- **单测**：①nodeClone：入边/出边克隆、原边保留、新边 id 唯一、无组加入；②canvasVideoAttachments：audio 分支占位「音频N」+ 断链口径；③PropertyPanel：capability 驱动选项渲染（adaptive/无4K/时长区间）、切模型收敛、maxlength 与建议文案、粘贴/拖拽 handler（焦点在输入框不拦截、类型分流、超限拒）；④后端：KIND_MAX_BYTES 30MB 边界、image 组装出 URL 非 data URI（WireMock 断言请求体）、签名 URL 过期话术。
- **人工测试标记**（需真实浏览器手势/真渠道）：①外部复制图→画布 Ctrl+V 成节点；②从资源管理器拖图/视频/音频各一成节点；③带上下游连线节点创建副本→两侧连线齐全+原边未动+副本可独立重生成；④29MB 图附件真跑生成（Ark 收到 URL）；⑤画布视频节点切 Cdance2.0→比例 7 档/4K/4-15s/音频开关在；⑥@音频节点进视频提示词→提交附件含 audio。
- **回归**：MentionTextarea 纯文本粘贴、@候选跟光标、独立视频页全链、副本产物独立（C-8 用例）、画布自动保存。

## 8. 边界与不做

- **不做**：音频/视频附件的 URL 传输改造（音频 base64 3×15MB×4/3≈60MB<64MB 未爆，另轮）；副本带组关系；@文本/分镜节点进视频参考；TTS/音乐生成 provider；画布参考图/视频/音频**上传瓦片**（独立页形态）——画布参考一律走 @节点 或首尾帧选择器。
- 组装后提示词（@插值展开）超 8000：后端 400 拦截为最终口径，前端不做展开后预检。
- 官方建议值变化不改 maxlength（maxlength 锚定平台 8000），只改建议文案。
- 30MB 若官方上调/下调解禁：改 KIND_MAX_BYTES 常量+镜像常量两处即可，无迁移。

## 9. 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-08-28 | 建立规格（VI-1~6，Q1~4 拍板） | 2x 问题单最后 6 项未解决：粘贴/拖拽/副本连线/提示词上限/附件传输/参数对齐 |

## 10. 术语表

| 术语 | 大白话 | 案例 |
|---|---|---|
| data URI | 文件内容直接以 base64 塞进请求字符串 | 8MB 图变 10.7MB 文本 |
| 签名公网 URL | 带防伪签名的临时下载链接，模型服务自己去取 | /api/media/reference/xx?sig=…&ttl=900s |
| capability 驱动 | 界面选项由模型能力表决定显示哪些 | fast 模型不显 4K |
| 平节点 | 复制出来的节点不带任何连线和分组关系 | 现状副本口径（VI-3 后带边仍不带组） |
| MENTION 候选 | 提示词里打 @ 弹出的可引用上游节点列表 | @图片节点→「图1」 |
| 建议值上限 | 官方说「建议别超」，超了不报错只是效果变差 | 视频 ≤500 汉字 |
