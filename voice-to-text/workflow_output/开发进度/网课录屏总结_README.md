# 网课录屏总结 · 功能 README

> Phase 3 功能完成收尾产物。**受众：C 类（两者）** —— 用户可感知的业务功能 + 有技术复杂度，两部分都写。
> 关联：[design spec](../docs/specs/网课录屏智能总结.design.md)（FR-101~109 / AC-101~109）· [父 plan](../docs/plans/网课录屏总结.plan.md) · [测试方案](../docs/测试方案/网课录屏总结测试方案.md)
> 存放约定：本功能进度文件为扁平命名（`网课录屏总结_开发进度n.md`），故 README 同为扁平 `网课录屏总结_README.md`，不用子目录。

## 一、用户地图

- **谁会用**：看录播网课学习的人（学生、考证/自学者），尤其是 2 小时以上长课没时间二刷的人。
- **什么场景下用**：播放网课时想让电脑「边看边记笔记」——录下窗口画面 + 讲解语音，课后自动产出带时间戳的章节笔记，点笔记能跳回视频对应时刻复习。
- **带来什么效益**：2h 课 → 一份结构化 Markdown 笔记（章节/要点/时间戳/课件截图引用），复习从「拖进度条找」变成「点要点跳转」；课件 OCR 原文可核对，总结说错了能改、能单独重生成某一段。
- **谁用不到**：① 不装 Windows 的人（窗口捕获/系统内录仅 Windows）；② 没有云端 LLM API Key 又不想配的人（只能拿到抽帧+转写，总结段会失败但可后续补 Key 重试）；③ 要求 100% 隐私离线的人（总结需上传转写文字，见隐私红线）。

## 二、技术说明

- **职责**：选定窗口三路录制（屏幕视频切片 + 音频 + 实时转写，共用同一墙钟 t0）→ 录后四阶段（精细抽帧 → 课件 OCR → 音字帧对齐 → 云端 map-reduce 总结）→ 学习区（章节时间轴 + 视频点播回跳 + 要点编辑）→ Markdown 导出。
- **关键入口**：前端 `App.vue`「网课总结」Tab → `Recorder.vue`（录制）→ `Processing.vue`（录后编排）→ `Study.vue` + `SummaryPanel.vue`（学习区）；后端命令全部注册在 `src-tauri/src/lib.rs`（IPC 命令表见 [architecture.md §四](../docs/specs/architecture.md)）。
- **依赖**：sherpa-onnx（本地转写模型）、windows-capture/WGC（抓屏）、oar-ocr + PP-OCRv5（本地 OCR，首用自动下载到 `$OAR_HOME`）、OpenAI 兼容云端 API（总结，reqwest blocking）、Windows 凭据管理器（API Key，keyring windows-native）。
- **部署 / 配置注意**：
  - 运维开关：环境变量 `VTT_COURSE_SUMMARY=off` 或 `<app_config_dir>/feature_flags.json` 写 `{"course_summary": false}` → 前端隐藏 Tab + 后端拒绝 `start_capture_session`（默认开）。
  - 运行时目录：exe 旁 `sessions/<id>/`（视频/音频/转写/帧/草稿/导出全在里面，删除目录即删除整节课）。
  - OCR/ONNX 缓存：`OAR_HOME`、`ORT_CACHE_DIR` 建议指向大容量盘（默认落 C 盘用户目录）。
- **排障要点**：
  - 日志 traceId = session_id，搜 `[session][<id>]` 可串起一节课的全部日志。
  - 总结失败先看是否「尚未设置 API Key」→ 总结设置里填 Key + 测试连接；API 失败会自动本地兜底（草稿标 `fallback`，学习区显示 ⚠️）。
  - 处理中断可断点续跑：已完成阶段自动跳过，重试只跑失败阶段。

## 三、DoD 勾选状态

> 权威 gate：[功能完成DoD清单.md](功能完成DoD清单.md)。当前状态 = Phase 3 收尾完成，Phase 4 人工验收待做。

- [x] **A 规格**：FR-101~109 / AC-101~109 已定义（design spec 三章拆分）；无数据库表（纯文件落盘，db_schema N/A）
- [x] **B 计划**：父 plan + 子 plan 00~05 出齐；坑点/安全/性能/运维清单在 00_横切清单
- [x] **C 实现**：Step1~12 全完成；check_all 全绿；README/FeatureMap/User-Ops/测试方案齐全；进度已更新到 开发进度4/5
- [ ] **D 验证**：单测 75 绿 ✅；**AC-101~109 人工验收未做**（挂起，需 2h 真实网课）；Phase 4 review 未做
- [ ] **E 发布**：N/A（本功能随主应用 Tauri 打包，无独立 CI/部署；监控告警 N/A 桌面端）
- [x] **F 文档同步**：file_structure/architecture 已同步（Step12）；FeatureMap/User-Ops/README 本轮产出；无 db_schema
