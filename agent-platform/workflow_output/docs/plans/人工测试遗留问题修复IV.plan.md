# 人工测试遗留问题修复IV · 实现计划（master）

> 规格：[specs/人工测试遗留问题修复IV设计.md](../specs/人工测试遗留问题修复IV设计.md)。2026-08-27。
> 上游：修复III 已完结（Plan A-F 全绿收尾）。本轮=三份人工测试问题文档未解决 14 项 + 1 顺带缺陷（候选权限不一致），7 项用户决策锁定（含公共池入组改 0）。

## chunk 索引与依赖

| chunk | 内容 | 依赖 | 分册 |
|---|---|---|---|
| A | ✅ 前端小件四项：审计列显姓名备注 / UserPicker 全选反选+下拉时机 / 候选权限对齐 / 分辨率整行（12x-2、17x-1、17x-2+2b、C-5） | — | [A_前端小件四项](人工测试遗留问题修复IV_A_前端小件四项.plan.md) |
| B | ✅ 画布交互四项：两段式点击 / 上游直显配色 / resize 热区+四边 / 面板拖宽（C-1/2/3/6） | — | [B_画布交互四项](人工测试遗留问题修复IV_B_画布交互四项.plan.md) |
| C | ✅ 画布保存与副本三项：自动保存补缺 / 新建即定型尺寸 / 副本完全独立（C-4/7/8） | — | [C_画布保存与副本三项](人工测试遗留问题修复IV_C_画布保存与副本三项.plan.md) |
| D | 项目组：默认 0+不限冻结 / 普通成员受限视图（17x-3/4） | — | [D_项目组默认额度与成员可见](人工测试遗留问题修复IV_D_项目组默认额度与成员可见.plan.md) |
| E | 按备注汇总统计（12x-1：独立视图+备注列+keyword） | — | [E_备注汇总统计](人工测试遗留问题修复IV_E_备注汇总统计.plan.md) |
| F | 文档收尾：三份问题文档勾销 + feature-map/user-ops「修复IV 增补」+ 测试方案 | A-E | [F_文档收尾勾销](人工测试遗留问题修复IV_F_文档收尾勾销.plan.md) |

**同文件交叉是本轮主要冲突面**：PropertyPanel.vue（A5→B2/B4→C1）、CanvasBoard.vue（B1→C1/C2）、ProjectGroupsView.vue（A 邀请区→D 全域）、UserPicker.vue（A2/A3）。**实施序 A→B→C→D→E→F 严格串行**，每 chunk 绿即 commit，避免同文件并行改。

## 技术坑点预判（全轮通用）

1. **VueFlow resize-control 样式覆盖**：库默认 `.vue-flow__resize-control.handle` 5px——覆盖需更高特异性选择器（带组件作用域前缀）或 `!important`；热区用 `::before` 绝对定位 inset 负值 + `pointer-events:auto`。只改四角视觉/热区，`variant="Line"` 四边是**新增控件**不是样式覆盖。
2. **四边 Line 与连线 Handle 命中冲突**：连线 Handle 10×10 在节点边缘中点（CanvasBoard.vue:1037-1042），resize Line 热区必须收在边缘**内侧**且 z-index 低于连线；实测点不中连线时启用 fallback（边线手柄仅 hover 贴近 12px 渐显）。
3. **blur 时序**：MentionTextarea 已有「blur 延迟关候选层」（:360-363）——新增失焦保存通知必须在延迟后判断「未点击候选行」再发，否则点候选=误触发保存+关层。候选层行用 `mousedown.prevent` 保焦点是既有手段，沿用。
4. **select 类 @update 频率**：比例/时长/分辨率是离散选择器，`update:model-value` 每次选中只发一次，无输入过程抖动；800ms 防抖兜底。不要用 watch 深比较（会把 C-8 副本 status 变更也误判为用户编辑）。
5. **addNode emit 与 undo 栈**：structure-changed→scheduleSave 是保存链（CanvasView:145），**不是** history push（applyHistoryState:940 才是）。补 emit 后必须验证：新增节点→undo→redo 链路不坏（现状新增是否可 undo 先测后改）。
6. **BigDecimal 0 落库**：`insertMemberRow` 收 null 时转 `BigDecimal.ZERO`，转换点收在 service 层（DTO 的 null 语义不动，避免动既有校验分支）；`updateQuota` null→BusinessException(400)。
7. **裁剪单点**：17x-4 裁剪只写在 getDetail 组装 VO 一处（ProjectGroupService:470-493），detail/overview 两入口自然同口径；**严禁**在 controller 层再裁一遍（双处漂移）。
8. **逐接口核对**：D 轮开工第一步=列出 ProjectGroupController 全部端点+requireRole 级别清单，确认只放宽 detail/overview 两个读口，其余不动。
9. **remark-summary 聚合**：llm_usage_logs 全表子查询 GROUP BY——单租户量级可接受，但必须 `LIMIT 1000` 兜底 + 「未填备注」桶（COALESCE）；LIKE 转义照 UserController:76-78。
10. **UserPicker 全选竞态**：搜索中（seq 未 settle）禁用全选/反选按钮；全选作用域=当前 options 快照，不追后续响应。
11. **前端 WIP 混提**：工作区有用户未提交改动（git status：DefaultChatStrategy/MediaGenTaskWorker/AppHeader/router/mediaTaskRestore/AssetProjectView 等）——B/C/D 涉及文件若与 WIP 重叠，先 `git diff` 逐文件过目，commit 显式 add 指定文件。
12. **localStorage 宽度兜底**：`parseInt` NaN/越界→回落 260；键名 `canvas.propPanel.width`。

## 安全检查清单（P3 逐项验证）

- [ ] updateQuota 拒 null：null→400 话术「不限额度已停用」；@AuditLog(member_quota) 既有不动（D 轮）
- [ ] getDetail 放宽后逐接口核对清单落地（D 轮，§D3 步骤 1）
- [ ] candidates requireOwner→requireRole(MANAGER)：只放宽读候选，写权限不变（A 轮）
- [ ] remark-summary 端点 usage:view 权限 + LIMIT + LIKE 转义（E 轮）
- [ ] 三 mapper keyword 扩 remark 均转义（E 轮）
- [ ] 审计列/成员列表新增显示均为纯文本渲染，无 v-html（A/D 轮）

## 运维考量清单（7 类落字）

| 类 | 决策 | 说明 |
|---|---|---|
| 可观测性 | **做** | 池批准落 0 记 INFO（gid/uid）；updateQuota 拒 null WARN（含操作人）；沿用既有 traceId/MDC |
| 配置开关 | **不做** | 行为修复轮无高危灰度需求；17x-3 冻结=代码语义，回滚代码即恢复「不限」写入，无需开关 |
| 可回滚 | **做** | **无 DB 迁移无数据订正**，全部改动代码级可回滚；localStorage 宽度键可清；前端对空值兜底显示 |
| 限流/熔断/降级 | **不做** | remark-summary 单机单查询 LIMIT 兜底，无第三方依赖新增 |
| 运维入口 | **做** | 组长调额=人工修数口（拒 null 不影响数值调整）；既有审计 member_quota 动作覆盖新拒绝路径；对账模板不受影响（无新流水类型） |
| 告警阈值 | **不做**（无告警系统） | 拒 null WARN 落日志，接告警后续再说 |
| 容量/性能 | **后续再说** | users.remark 无索引；当前量级（users<10⁴、聚合 LIMIT 1000）可接受，慢了再加索引/限时段 |

## 联动点清单（L1-L14，分册含边界档位）

| # | 触发→联动 | 关键边界 |
|---|---|---|
| L1 | UserPicker 全选/反选→chips | 反选=对当前候选翻转；换关键词→作用域=新候选集、既有选择**不丢**；chips 删除后全选可重新加入；搜索中禁用 |
| L2 | 弹窗打开/点搜索框/输入/Esc/点外部 | 打开**不**弹；点框/输入才弹；Esc+外部关；重开不残留关键词与下拉 |
| L3 | MANAGER 打开邀请弹窗→候选 200 | MEMBER 打开仍 403（requireRole 拦）；组长/admin 原样 |
| L4 | 审计列表用户列 | 已删用户回落 username 快照；operatorRemark 空→不显 tag |
| L5 | 分辨率改选→整行完整可见 | 与 C-4 保存联动（C 轮）；窄面板也不截断 |
| L6 | 点媒体区 | 未选中→仅选中不开；已选中→开 Lightbox；空白点→取消选中（回到两段第一段）；点节点非媒体区→仅选中 |
| L7 | 选中媒体节点→上游全部直显 | 切节点不残留折叠态；>50 截断提示；类型色条区分 |
| L8 | 选中→8 手柄显；拖角=宽高、拖边=单轴 | 未选中无手柄；min 160/64；连线 Handle 命中优先于边线手柄；resize 落库不回归 |
| L9 | 拖面板左缘→实时变宽 | 松手持久化；刷新恢复；非法值回落 260；拖拽中画布无选择框/误选 |
| L10 | 新增节点/文本失焦/参数改→自动保存 | 与 taskId 即时保存链独立不打架；保存徽标状态机不回归；undo/redo 含新增节点 |
| L11 | 新建 image/video 节点→320×320 | 三路（调色板/拖入/快速加）一致；文本类不变；定型分支不再覆盖（用户手拉过仍不覆盖） |
| L12 | 创建副本→产物即显+success | 入库=新资产不判重命中原件；再生成=全新任务；删原件副本产物仍显（文件引用在） |
| L13 | 邀请不填/池批准/调额 null | 分别=落 0 / 落 0+提醒话术 / 400；存量 null 显「不限（遗留）」行为不变；建组组长行仍 null；预估与提交按 quota−used=0 拦 |
| L14 | MEMBER 打开组详情 | 见成员 tab 受限列+本人行全显；流水/审批/设置仍 403；组长/admin 全显不回归；非成员 403 |

## 验证总闸

- 后端：`mvn test`（全量基线 2550+ 不红；chunk 内 `-Dtest=...` 先行）
- 前端：`vue-tsc` 0 error + vitest（改动组件对应 *.test.ts 全绿，基线 800+ 不红）
- 实测：curl 走 admin123 账号体系（本地实测环境要点见 memory）；17x-3 全链路 + 17x-4 三角色矩阵必做
- 每 chunk 绿即 commit（中文 message，显式 add 指定文件，防 WIP 混提）

## 术语表

- 热区（hit-area）：看不见但点了算数的扩大点击范围。
- provide/inject：父组件放函数、子孙直接取用，免逐层传参。
- splitter：拖它改变相邻区域宽度的分隔条。
- 防抖（debounce）：连续触发只认最后一次，期间不执行。
- 冻结遗留态：老数据保持旧语义（NULL=不限），但任何新写入不可再产生该态。
- 逻辑删除（deleted）：行打删除标记不物理删，查询自动过滤。
