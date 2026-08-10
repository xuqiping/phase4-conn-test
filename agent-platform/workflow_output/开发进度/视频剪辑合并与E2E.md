# 视频剪辑（FFmpeg 后端渲染）· 合并 + Phase4 真 E2E

> 把源项目 `E:\agent-platform\` 的视频剪辑模块合并进本仓，并跑通真 ffmpeg 渲染 E2E。
> 对应 plan：`workflow_output/docs/plans/视频剪辑合并.plan.md`。

---

## 本轮对话信息

| 字段 | 内容 |
|---|---|
| **日期** | 2026-08-10 |
| **功能** | 视频剪辑（剪切/拼接/字幕/BGM，单轨多段，后端 FFmpeg 单遍 filter_complex 渲染） |
| **对应 plan 步骤** | 合并 + Phase4 Run（真 E2E） |
| **本轮目标** | 合并源项目视频剪辑代码 → 后端起 → 前端起 → 真 ffmpeg 渲染 E2E 通 |
| **完成状态** | ✅ 完成（渲染 E2E 真 mp4 落盘 + 下载验证） |

---

## 模块组成（合并后落点）

| 层 | 文件 | 作用 |
|---|---|---|
| 表/迁移 | `backend/.../db/migration/V87__media_edit_tasks.sql` | `media_edit_tasks` 表 + `media:edit` 权限 seed（admin gated） |
| DTO | `.../media/edit/dto/EditSpec.java` | V2 多轨剪辑意图（VIDEO/AUDIO/TEXT track + segments/texts/output） |
| Provider | `.../media/edit/provider/FfmpegEditProvider.java` | FFmpeg ProcessBuilder 单遍 filter_complex 渲染（核心） |
| Service | `.../media/edit/service/MediaEditTaskService/Worker/QueryService` | 提交 / SKIP-LOCKED worker 认领+恢复 / 查询 |
| Controller | `.../media/edit/controller/MediaEditController.java` | submit/tasks/download/export-draft（剪映草稿） |
| 前端 | `frontend/src/views/VideoEditView.vue` + `api/mediaEdit.ts` | 素材库面板 + 时间轴（视频轨/字幕轨）+ 提交渲染 |

状态机：PENDING→RUNNING→SUCCEEDED/FAILED。产物走 `stored_files`（source=EDIT）单一咽喉。

---

## Phase4 真 E2E 结果（2026-08-10）

**环境**：Win10 / ffmpeg 7.1.1（`C:\ffmpeg-n7.1.1\bin\ffmpeg.exe`，经 `MEDIA_EDIT_FFMPEG_PATH` 指定）/ 后端 8080 / 前端 5173 / admin 登录。

**流程**：Playwright `/video-edit` UI 冒烟（0 console err）→ 上传 clip3s.mp4（fileId `b6cb730d-…`）→ API 提交 EditSpec（1 视频段 0-3s + 1 CJK 字幕 0.5-2.5s，720p/24fps）→ 轮询任务 → SUCCEEDED → 下载。

**结果**：
- task#3 FAILED（修前，盘符冒号 bug）
- task#4 / task#5 **SUCCEEDED**（修后，真渲染）
- 下载 `rendered-task5.mp4` = 92595 bytes，ffmpeg probe：`Duration 00:00:03.00` / `Video h264 yuv420p 1280x720 24fps` / `Audio aac 44100Hz mono` ✅

---

## 本轮修的 bug：Windows 盘符冒号破坏 filter_complex

**现象**：ffmpeg 7.1.1 报 `No option name near '/Windows/Fonts/simhei.ttf:…'` / `Failed to set value '…fontfile='C:/Windows/Fonts/simhei.ttf'…' for option 'filter_complex': Invalid argument`。

**根因**：filter_complex 的 drawtext `fontfile=`/`textfile=` 用绝对路径，盘符冒号 `C:` 被滤镜解析器当 **option 分隔符**（`:` 分隔 drawtext 各 option）。即使用单引号 `'C:/…'` 包路径，7.1.1 **不**保护冒号（实测单引号、单反斜杠转义 `\:` 都失败）。

**修法（FfmpegEditProvider.java，已落）**：
1. 字体拷进 per-task workDir，滤镜用相对名 `font.ttf`；
2. textfile 用相对名 `sub-N.txt`（仍写绝对路径到 workDir，仅滤镜串引用 basename）；
3. `run()` 加 `workDir` 参数 → `ProcessBuilder.directory(workDir)` 设 CWD，相对名解析；
4. 去掉 fontfile/textfile 的单引号（相对名无特殊字符）；`enable='between(t,s,e)'` 表达式保留单引号。

**实测可用方案（PowerShell 无 shell 篡改）**：相对名 `fontfile=simhei.ttf` ✅；双反斜杠转义 `fontfile='C\:\\Windows\\…'` ✅；纯单引号 ❌；单反斜杠 `\:` ❌。选相对名（最稳，无转义计数）。

**单测同步改**：`FfmpegEditProviderTest` 断言从 `fontfile='/font.ttf'`（带引号）→ `fontfile=/font.ttf`（无引号）+ 新增 `textfile=sub-0.txt` 相对名断言 + `assertFalse(contains workDir 绝对路径)`。

> ⚠️ 该单测本机因 **他人在写的 WIP**（`AssetPublicAccessControllerTest` 引用不存在的 `PublicAccessDecisionRequest`/`AssetPublicAccessController`）打断 testCompile，无法独立跑。非本模块引入。主码 `mvn -Dmaven.test.skip=true clean compile` 绿。

---

## 部署必做（见 `docs/run-guide/快速启动速查表.md`「视频剪辑」节）

1. Flyway V87 自动建表 + `media:edit` 权限（admin 默认有）。
2. 装 **ffmpeg ≥4.4**（`adelay all=`）。Windows PATH 首个 ffmpeg 常是 ImageMagick 4.2.3（缺 `all=`）→ 设 `MEDIA_EDIT_FFMPEG_PATH` 指现代版。Linux `apt install ffmpeg` 即可。
3. CJK 字体：默认探测 `C:/Windows/Fonts/simhei.ttf`；可设 `MEDIA_EDIT_FONT_FILE`。
4. 普通用户用前由 admin 授 `media:edit`。

---

## 待办 / 留项

- [ ] 合并的 video-edit 代码 + 本 bug 修复**尚未 commit**（待用户确认后提交）。
- [ ] 他人在写的 WIP（`AssetPublicAccessControllerTest` 等）打断 testCompile，待其完成后补跑 `FfmpegEditProviderTest`。
- [ ] 更多分支真 E2E（多视频段间隙填黑、多音轨混音、多字幕、1080p/480p）留人工走查。
