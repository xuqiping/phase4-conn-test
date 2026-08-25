---
description: "子计划 C：预估个人口径 + 画布预估 + 图片比例推导（规格 §3、§5，Q5 拍板 7 比例）"
created-date: 2026-08-25
---

# 子计划 C：预估口径与图片比例

> 主索引：[人工测试遗留问题修复.plan.md](人工测试遗留问题修复.plan.md)
> 规格：§3（17x-2/2x 预估）、§5（6x 比例，官方文档已核实上游单 size 字段两方式不可混用）

## 技术坑点预判

| 坑 | 规避 |
|---|---|
| 管理成员预估口径漏双卡 → 重现「预估够提交拒」 | `inProjectAvailable`：MEMBER=quota−used；MANAGER(限额)=min(allocatable, quota−used)，直接复用 MemberBudgetService 既有方法 |
| WxH 推导 round 后出区间（比例极端/档位低） | 推导后**复核**总像素∈[min,max] 且比例∈[1/16,16]，不满足抛明确错误；1K/1.5K 档低于方式2下限 3686400 → 前置报错文案 |
| 前端两套尺寸实现再分叉 | 新公共工具 `frontend/src/utils/imageSize.ts`：比例列表、档位→像素预算、推导函数、WxH 预览——ImageGenView 与 PropertyPanel 共用 |
| capability 老 JSON 无 ratios/区间字段 | 解析缺省回退默认值（7 比例 + Seedream 区间），不迁移数据 |
| estimate 请求风暴（画布每键一动就查） | 前端 500ms 防抖 + 参数不变不重发；后端无状态无压力点 |
| 画布提交点两处（视频/图片）漏一 | 以 CanvasView.vue:1280/:1433 两处清单化接入 |

## 实现步骤

- [ ] **C1：estimatePreview 个人口径（后端）**
  - **目标**：gid 模式返回成员/管理双卡可用
  - **动作**：`MediaGenTaskService.estimatePreview`（:786-793）扩展：
    ```
    if gid != null:
        row = memberMapper.selectByGroupUser(gid, userId)
        if row != null:
            avail = row.quota==null ? null
                    : row.role==MANAGER ? min(budgetService.allocatable(gid,row,null), row.quota−row.used)
                    : row.quota−row.used
            personalScope = {quota, used, inProjectAvailable: avail,
                             affordableMember: avail==null || avail>=est,
                             bindingConstraint: avail!=null&&avail<est ? MEMBER : (池<est?POOL:NONE)}
    顶层 affordable = personalScope==null ? 池>=est : (affordableMember && 池>=est)
    ```
    老字段 estimatedPoints/balance 保留不动
  - **文件**：`media/service/MediaGenTaskService.java`、复用 `projectgroup/service/MemberBudgetService.java`
  - **验证**：单测三算例（限额成员卡 MEMBER / 不限额卡 POOL / 管理双卡取 min）

- [ ] **C2：生成页红字分层 + 画布预估（前端）**
  - **目标**：三处统一显示；2x 画布缺口补上
  - **动作**：
    - `ImageGenView.vue:164-172`、`VideoGenView.vue:254-262`：红字按 bindingConstraint 分层——MEMBER：「项目内剩余 X 不足（限额 Y−已用 Z）」；POOL：「项目组池剩余 X 不足」；affordableMember=false 且池够时也提示项目内不足
    - `CanvasView.vue`：视频（:1280）/图片（:1433）提交按钮旁预估 chip「预估 X · 项目内剩余 Y」；输入防抖 500ms 调 `GET /api/media/estimate`（带 projectGroupId 与当前参数）；不足红字
    - `PropertyPanel.vue` 参数区加只读预估条（同数据源）
  - **文件**：`frontend/src/views/CanvasView.vue`、`frontend/src/views/media/ImageGenView.vue`、`frontend/src/views/media/VideoGenView.vue`、`frontend/src/components/canvas/PropertyPanel.vue`、`frontend/src/api/media.ts`（estimate 参数补 projectGroupId，若缺）
  - **依赖**：C1
  - **验证**：人工——限额成员组池充足时生成页/画布均点名「项目内剩余不足」；画布提交前可见预估且与生成页一致

- [ ] **C3：图片比例推导（后端，Q5=7 比例）**
  - **目标**：平台层「比例+档位同传」→ 推导 WxH 走方式2
  - **动作**：
    - capability JSON 增可选字段：`ratios`（默认 `["1:1","4:3","3:4","16:9","9:16","3:2","2:3"]`）、`minTotalPixels/maxTotalPixels`（默认 3686400/16777216）；`MediaModelCapabilityService` 解析带缺省
    - 新 `media/service/ImageSizeDeriver.java`：
      ```
      档位预算 P = {2K:2048², 3K:3072², 4K:4096², 1K:1024², 1.5K:1536²}[tier]
      r = ratios 白名单解析("16:9"→16/9)
      W = round(sqrt(P×r)); H = round(W/r)
      校验 W×H ∈ [min,max] 且 W/H ∈ [1/16,16]，否则抛 BAD_REQUEST(含指引文案)
      return "WxH"
      ```
    - `MediaSubmitRequest` 增可选 `ratio`；与显式 `size=WxH` 互斥（都传→400）；`isValidSize`（:505-557）旁增 ratio 校验分支；提交时 ratio→推导→覆盖 size 后走既有链路（ArkImageProvider buildBody 不改）
    - 任务记录/资产回显用上游返回 `data.size`（现状已有则不动）
  - **文件**：`media/service/ImageSizeDeriver.java`（新）、`media/service/MediaModelCapabilityService.java`、`media/dto/MediaSubmitRequest.java`、`media/service/MediaGenTaskService.java`
  - **依赖**：无
  - **验证**：单测 7×3 档全矩阵落区间；1K+任意比例 → 明确报错；ratio+size 同传 400

- [ ] **C4：比例 UI（前端两处）**
  - **目标**：生成页+画布统一「比例+清晰度（推荐）/ 自定义宽×高」两模式
  - **动作**：`frontend/src/utils/imageSize.ts`（新）：RATIOS/档位预算/derive()；`ImageGenView.vue`（:71-90,:373-378）与 `PropertyPanel.vue`（:146-154,:971-976）接同一工具——比例选中时实时显示推导 WxH 预览；1K/1.5K 档+比例 → 红字禁提交；不选比例=现状档位模式
  - **文件**：`frontend/src/utils/imageSize.ts`（新）、`ImageGenView.vue`、`PropertyPanel.vue`
  - **依赖**：C3
  - **验证**：人工——16:9+2K 生成 → 下载图实宽高比 16:9；画布同参一致；不选比例回归现状

- [ ] **C5：测试与实测**
  - 单测见各步；人工测试点：规格 §3.5、§5.6 全过

## 功能联动点清单

| 触发 | 联动对象 | 预期 | 边界 |
|---|---|---|---|
| 比例选中 | WxH 预览变 | 重算 | 切档位重算；切「自定义」模式清比例 |
| 1K/1.5K+比例 | 提交按钮 | 红字禁用 | 切回 2K+ 自动恢复 |
| 比例参数变 | 预估 chip | 不变（比例不影响价，价=张数×单价） | 文案注明「预估与比例无关」 |
| 成员限额变（子计划 A 缩额） | 预估项目内剩余 | 变小 | 不限额显示「不限」 |
| estimate 请求 | 防抖 | 500ms 合并 | 参数未变不发 |

## 验证收口

- [ ] C1-C5 全绿；画布/生成页/画布属性面板三处预估一致
