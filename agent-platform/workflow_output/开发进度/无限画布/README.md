# 无限画布创作页 · 功能 README

> Phase 3 功能收尾产出。受众类型 = **C 两者**（终端用户创作面 + 视频处理技术复杂度）→ 写「一、用户地图」+「二、技术说明」。
> 关联：实现 plan [../../docs/plans/无限画布创作页.plan.md](../../docs/plans/无限画布创作页.plan.md) · Feature Map [../../docs/feature-map/无限画布创作页.feature-map.md](../../docs/feature-map/无限画布创作页.feature-map.md) · 用户操作手册 [../../docs/user-ops/无限画布创作页用户操作手册.md](../../docs/user-ops/无限画布创作页用户操作手册.md) · 速查表 [速查表24](../../../项目工程文档/项目功能介绍/速查表/24-无限画布创作页.md) · 开发进度 1-7（本目录）。

## 一、用户地图

- **谁会用**：内容创作者、短视频/广告/分镜制作人员（持有 `canvas:write` 权限；视频生成还需 `media:gen`）。
- **什么场景下用**：
  - 多镜头叙事 / 分镜编排：一张画布上摆多个文本/图片/视频节点，连线表达「提示词→图→视频」的生产链。
  - 视频二次加工：对已生成视频**抽帧**（取首/尾/指定秒做封面）或**截取**（裁掉头尾多余片段）。
  - 成片拼接：把多段视频按顺序排成故事板，一键拼成一条成片。
  - 素材复用：节点可重跑、可衍生（不覆盖原图）、可打组（后续）。
- **带来什么效益**：
  - 平台原本只有**单镜头独立生成页**，无编排面；画布把「多模态产出 + 节点复用 + 时间线剪辑」聚合到一张可无限缩放的创作面上。
  - 多镜头叙事从「分别生成→本地剪辑软件拼接」变成「画布上一站式闭环」。
  - 节点产出**互为输入**（图生视频自动取上游图作首帧），改一处重跑全链。
- **谁用不到**：
  - 无 `canvas:write` 权限的普通用户（看不到入口）。
  - 移动端用户（≤768px 显示占位页，节点画布不适合触屏）。
  - 需要深度剪辑（多轨/转场/特效/导出剪印子草稿）的用户——独立 plan，本画布只做基础故事板。
  - 需要 AI 生图的用户——MVP 图片节点只支持上传/衍生/焦点编辑，AI 生图待生图 provider 落地（R-3 平行子 plan）。

## 二、技术说明

- **职责**：LibTV 式无限创作画布——5 类节点（文本/图片/视频/音频/脚本）+ 自由连线 + 一键拓扑重跑 + 焦点编辑 + 故事板 + 视频抽帧/截取/拼接。**独立 `canvas/` 包 + 独立路由 + 独立表，只读复用 media/llm/file 三件套，零主链路回归**。
- **关键接口 / 入口**：
  - 后端：`CanvasController` `/api/canvas`（快照 CRUD + `nodes/run` + `upload` + `nodes/{id}/frames` + `nodes/{id}/clip` + `storyboard/concat`），全端点 `@RequirePermission("canvas:write")`。
  - 前端：`CanvasView.vue` 路由 `/canvas` `/canvas/:id` + `CanvasBoard`（Vue Flow）+ `PropertyPanel`/`StoryboardPanel`/`FocusEditOverlay`。
  - 复用：`MediaGenTaskService.submit`（视频）、`LlmGateway.chat`（文本/脚本）、`FileStorageService.storeStream(SOURCE_CANVAS)`/`loadPath`（文件落地/读取）。
- **依赖**：
  - `@vue-flow/core ^1.41.5`（已装，工作流编辑器同款）。
  - **javacv（bytedeco）1.5.13 + ffmpeg 8.0.1-1.5.13**：抽帧/截取/拼接。纯 Java 无系统依赖；sidecar 禁 subprocess 故走后端 Java。
  - 复用 media 包（ArkSeedanceProvider 等）、llm 包（LlmGateway）、file 包（FileStorageService + V40 stored_files）。
- **部署 / 配置注意**：
  - **linux 服务器 pom 必须追加 `org.bytedeco:ffmpeg:8.0.1-1.5.13 <classifier>linux-x86_64</classifier>`**（dev 用 windows-x86_64，linux 不加会 `UnsatisfiedlinkError`）。
  - 临时文件落 `${java.io.tmpdir}`（clip/concat 产物），storeStream 后即删；高并发预留 tmpdir 空间。
  - 配置：`canvas.enabled`(默认true) / `canvas.max-nodes` / `canvas.frame-extractor=javacv`。
  - 迁移：V55 `canvases` 表 + `canvas:write` 权限 seed（gated 仅 admin）。
- **排障要点**：
  - 看不到菜单/直访 403 → 查 `canvas:write` 权限（角色权限页）。
  - 视频节点 FAILED → 查 `media:gen` 权限 + Ark 配额/超时。
  - 抽帧/截取在 linux 报 `UnsatisfiedLinkError` → pom 没加 linux classifier。
  - 重跑报「存在环」 → 节点连线成环，删一条边。
  - 拼接失败「段尺寸不一致」 → 各段非同源，改用同一视频的截取片段。
  - 磁盘增长 → 查 `${java.io.tmpdir}` 是否有遗留 `canvas-clip-*`/`canvas-concat-*`（storeStream 失败时 try-finally 应已删；极端崩溃可能残留，可手动清）。
- **关键技术决策**（维护时不用回忆「为啥这么写」）：
  1. **画布底座 = Vue Flow 非 tldraw**：LibTV 画布=节点图+连线非自由涂鸦；tldraw 是 React 仅，进 Vue 需双运行时（react/react-dom/veaury）。Vue Flow 已装、Vue 原生、团队在用 → 省 React 成本。
  2. **快照与产出物分离**：画布结构存 `canvases.snapshot` JSONB；图/视频/音频产出物存 V40 `stored_files`，快照里只放 `fileId` 引用——避免 JSONB 撑爆、支持增量。
  3. **抽帧走后端 javacv 非 sidecar**：sidecar 禁 subprocess（AGENTS 约束），javacv 纯 Java 自包含无系统依赖。
  4. **大产物写临时文件非 byte[]**：clip/concat 产物写 `Files.createTempFile` → service 返 `Path` → controller `storeStream(SOURCE_CANVAS)` 后 try-finally 删（大片段 byte[] 撑爆堆）。
  5. **节点运行无状态回包**：`NodeRunResult.dataPatch` 由前端合并 + 节流整存，service 不碰 snapshot 读写，零 JSONB 并发竞态。
  6. **javacv 依赖瘦身**：弃 `ffmpeg-platform` 全家桶（全平台 native 撑爆磁盘），改 `ffmpeg` base + 单平台 `<classifier>`；exclude 14 无用 preset。版本号坑：ffmpeg = `<ffmpegVer>-<javacvVer>` = `8.0.1-1.5.13`（非 1.5.13）。

## 落地范围与留项

- **已落（C1-C13）**：基建+画布底座+5 节点+连线数据流+拓扑重跑+焦点编辑骨架+视频抽帧+视频截取+故事板拼接。
- **留项**：
  - C14 Agent 自动建流（最后，依赖 C1-C13 全就绪）。
  - R-3 生图 provider（解锁图片 AI 生图 + 脚本分镜图）、R-4 多视频 provider（可灵/海螺/happy horse）。
  - 打组保存模板持久化（C9 一键重跑已落，模板未做）。
  - Phase4 真 E2E（真视频→抽帧/截取→故事板拼接→成片可预览→自动连边可视化）。
  - 深度剪辑（多轨/转场/导出剪印子草稿）、完整资产库——独立 plan。
