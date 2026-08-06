# 资产库与画布测试问题修复 · 开发进度4（Phase4 冒烟验证）

> 对应 [plan](../../docs/plans/资产库与画布测试问题修复.plan.md)。worktree `khfz1`。
> Phase4 Step1（Run）：Playwright MCP 冒烟，重点 C2 卡片缩略图懒加载。

## 冒烟环境
- 栈：从 worktree `khfz1` 起（main 栈无 testfix 代码，已切到 khfz1）：后端 8080 + 前端 5173。
- 后端 Flyway 启动自动应用 **V60**（media_category）成功，`Started in 11.256s`。
- 账号：admin / admin123（admin 角色含 `asset:write`）。
- 项目：「测试短句」（id=6，所有者 admin）。
- 测试素材：System.Drawing 生成 cat.png/dog.png（纯色）+ ffmpeg 由 cat.png 生成 2s smoke-test.mp4。

## C2 冒烟结果（Playwright MCP + DOM 断言）

| 场景 | 资产 | DOM 证据 | 结论 |
|---|---|---|---|
| IMAGE 缩略正向 | smoke-cat.png | `<img class=cover-media src=blob:...84278222>` | ✅ 渲缩略图 |
| IMAGE 缩略正向（第二张） | smoke-dog.png | `<img src=blob:...65f8491f>` | ✅ 渲缩略图 |
| VIDEO 首帧正向 | smoke-test.mp4 | `<video src=blob:...67e37bf3 readyState=4 currentTime=0.04>`（metadata 就绪+seek 首帧） | ✅ 渲首帧 |
| 孤儿文件回退（IMAGE） | 抽帧(LAST)（fileId 文件已删） | `tag=null, hasIcon=true 🖼️`；网络 `/api/files/{id}.jpg` 404 | ✅ 回退色块不崩 |
| 孤儿文件回退（VIDEO） | 视频 2（fileId 文件已删） | `tag=null, hasIcon=true 🎞️`；网络 `/api/files/{id}.mp4` 404 | ✅ 回退色块不崩 |
| 详情抽屉预览未回归 | smoke-cat.png 开抽屉 | 抽屉 `<img src=blob:...a08a78ba>` | ✅ fetchCanvasPreview(=fetchFilePreview re-export) 正常 |
| N+1 / 去重 | 网络 `/api/files/` | 每个 fileId 单次 GET（cat 200 一次）；去重逻辑单测 5/5 覆盖 | ✅ 无 N+1 |

截图证据：`.playwright-mcp/c2-thumbnails-cat.png`（单图缩略）、`.playwright-mcp/c2-grid-all-cases.png`（5 卡：3 缩略+2 回退）。

## 其余 chunk 顺带回归（资产库页可见）
- **C1b 媒体类型两层**：顶栏 图片3/视频2/音频0 + 左栏 角色 5 桶 计数矩阵正常加载（mediaCategory/mediaTypes V60 后端返正常）✅
- **C1a 叙事角色桶**：左栏人物/道具/场景/风格/通用 渲染正常 ✅
- C3/C4/C5/C6 为画布节点特性，本页不涉及（单测全绿，留画布页 Phase4）。

## 发现问题
- **C2 本身零 bug**。
- ⚠️ **既有孤儿数据（非 C2 引入）**：项目里 2 个资产（「抽帧(LAST)」「视频 2」）的 fileId 指向已删文件，每次列表加载都 404（console 噪音）。C2 已优雅回退不崩，但 404 噪音仍在。**建议 backlog**：上传/产线产物删除时清资产版本 fileId，或前端对连续 404 静默（记档，非阻塞）。
- 测试 mp4 由 ffmpeg 单图 `-t 2` 生成，video.duration 显示 0.04（生成侧怪异，非 app bug；首帧仍正常 seek 显示）。

## Phase4 剩余项（本次冒烟范围外，留项）
- AC 逐条核对（plan 验证 checkbox）— C2 5 条全过，其余 chunk 联动反向边界留人工。
- User-Ops 全量傻瓜验证（资产库已有 user-ops，按表逐项）。
- 性能评测（首屏/矩阵筛选/搜索 p99 — 已有 Phase4 数据；C2 增量：缩略懒加载不阻塞首屏）。
- 第二 AI review（4-review.prompt 结构化 8 维度）。
- spec 漂移检查。

## 测试数据清理建议
本次冒烟在项目「测试短句」新增 3 资产：smoke-cat.png / smoke-dog.png / smoke-test.mp4。可留作样例或手动删除。
