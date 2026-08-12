# 生图大模型接入 · README

> B+C 类功能：用户向（创作者生图）+ 技术向（同步图片 API + manifest 驱动 + 按张计费 + 资产库双向）。

## 用户地图

- **谁用**：需生图的创作者（须 `media:gen` 权限）。
- **场景**：① 文生图（提示词→图）；② 参考图生图（资产库选图做参考）；③ 组图批量（lite 一次生多张）；④ 生成图一键入库资产库复用。
- **效益**：两个 Seedream 模型（lite/pro）按**官网全参数**生图，模型不同 UI 不同（manifest 驱动），生成图一键入库、参考图从资产库取——打通「生成→资产库」闭环。

## 两模型差异（决定 UI）

| 能力 | lite | pro |
|---|---|---|
| 参考图上限 | 14 | 10 |
| 组图（sequential） | ✓ max15 | ✗ |
| 联网搜索 | ✓ | ✗ |
| 流式 | ✓（MVP 固定关） | ✗ |
| 尺寸预设 | 2K/3K/4K + 自定义 WxH | 2K/3K + 自定义 WxH |
| 提示词优化 | standard | standard + fast |
| 引导尺度 | ✗ | ✓ [1,10] |

## 技术说明（简要）

- **同步 API**：图片端点 POST 一次返 `data[].url`（区别视频异步 create/poll）。故 `ArkImageProvider` 独立 `@Component`，worker 按 task_type 分流，视频零回归。
- **manifest 驱动 UI**：`ImageModelCapability` 描述单模型能力，前端按其条件渲染控件——加模型只改 manifest，不动前端。
- **按张计费**：`chargeMedia(KIND_IMAGE, imageCount=usage.generated_images)`，后扣。
- **生成→库桥**：`AssetMediaBridgeService` 复用 SOURCE_MEDIA fileId（不拷贝），genMeta.source=MEDIA 标来源。无画布节点→无重复检测（同图可多次入库独立资产）。
- **跨包解耦**：asset 只读依赖 media（loadImageForImport），media 不 import asset——同 asset→canvas 模式。

## 部署必做

1. admin 建 IMAGE 类 provider（ctaigw key）。
2. admin 配两模型 `price_per_image` 价表（无 seed）。
3. 给角色授 `media:gen`。

## 相关文档

- 速查/调用链：`docs/feature-map/生图大模型接入.feature-map.md`
- 操作手册：`docs/user-ops/生图大模型接入用户操作手册.md`
- 测试方案（含联动用例）：`docs/测试方案/生图大模型接入测试方案.md`
- 计划：`docs/plans/生图大模型接入.plan.md`
- 开发进度：本目录 `开发进度1.md`

## 状态

实现全完（A-F 六 chunk，4 commit），asset+media 全模块 192 单测绿零回归。**剩 Phase4 真 E2E**：admin 填 key+价表后跑人工测试方案（真 ctaigw 调用 + 主观视觉）。
