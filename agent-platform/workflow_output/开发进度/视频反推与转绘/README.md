# 视频反推与转绘 · 功能 README

> 计划6 全四步落地（f29531e1 / bdc84cca / 3f1dbdca / 8c92d1a0）+ **P4 冒烟过（2026-08-18，修 2 缺陷 `aee7605`/`94ac27ab`，见 [开发进度5](开发进度5.md)）**。来源问题：2x 资产库和无限画布 #33（视频反推四能力）。
> 关联 [plan](../../docs/plans/视频反推与转绘.plan.md) / [feature-map](../../docs/feature-map/视频反推与转绘.feature-map.md) / [user-ops](../../docs/user-ops/视频反推与转绘用户操作手册.md) / [测试方案](../../docs/测试方案/视频反推与转绘测试方案.md)。

## 用户地图（B 类）

**谁用**：持有 `media:gen` 权限、需要「看懂一段视频→改造成新内容」的创作者（短视频改编、海外本土化二创、分镜学习）。

**场景**：
1. 拿到一条成品视频，想知道它的分镜结构/关键画面/剧情剧本 → 反推三产物（分镜表/关键帧/剧本）。
2. 国内剧情改编海外版：剧本里筷子→刀叉、春节→圣诞，剧情骨架不动 → 本土化转绘（changeLog 逐条核对）。
3. 反推出的剧本直接喂回生成表单，源视频顺手当参考视频 → 一条链「反推→转绘→再生成」。

**效益**：过去拆片靠人工逐帧截图+手写分镜；现在 FFmpeg 场景检测自动抽帧 + 多模态 LLM 一次出三产物，改写有清单可核对，全程分钟级、按 token 计费可预估（仅勾关键帧零 LLM 成本）。

## 技术说明（A 类）

- **零新 Provider/零 DB 变更**：抽帧=本地 FFmpeg 系统进程（两遍式：showinfo 全片扫时间戳→按选点 input-seek 取帧，命中<4 均匀采样兜底、>maxFrames 均匀截断）；分镜/剧本=STORYBOARD/SCRIPT 共用**单次**多模态 LLM 调用（缩略帧 ≤1024 长边 base64 + 帧序/时间戳标注，temp 0.3/maxTokens 4000）；坏 JSON 重试 1 次两败报 UNPROCESSABLE。
- **转绘**：剧本 JSON + 目标地区 + 保留要求 → LLM 改写（镜头数与顺序不变约束）；scenes 数不一致不拒——落 warning「请人工核对」，结果仍可用。
- **两入口**：无限画布（视频节点属性面板反推→逐帧图片节点+storyboard/script 节点连边；script/storyboard 可再转绘）；视频生成页「视频反推」Tab（时间轴条+分镜表+剧本+转绘+「用剧本生成」预填冲突三选）。
- **安全/运维**：`media:gen` 权限 + analyze 3/min、localize 6/min 限流；taskId 源走 `loadForDownload` 归属咽喉（他人任务 403）；帧产物落 stored_files（SOURCE_REVERSE）；审计 reverse_analyze/reverse_localize；`media.reverse.*` 九项配置（开关可整体下线、ffmpeg 路径/阈值/上限/超时/并发闸）；计费走 LlmGateway chat 链路落 usage_log（projectGroupId 透传兼容组池）。

## 开发进度索引

| 文档 | 覆盖 |
|---|---|
| [开发进度1.md](开发进度1.md)·未建（Step1 见 plan 落地段） | FFmpeg 关键帧提取（14 单测+真机冒烟） |
| [开发进度2.md](开发进度2.md) | analyze/localize 接口（31 单测） |
| [开发进度3.md](开发进度3.md) | 画布反推入口 |
| [开发进度4.md](开发进度4.md) | 视频模块反推 Tab（599 前端全绿） |
| [开发进度5.md](开发进度5.md) | P4 冒烟（两入口 Playwright，修 2 缺陷，性能实测） |

## 手测入口

见 [测试方案](../../docs/测试方案/视频反推与转绘测试方案.md)（L1-L6 联动用例 + 取消/冲突/边界）——P4 冒烟已过主链（详表见方案执行记录），B1/B2+A4-D9 剩余项人工补。
