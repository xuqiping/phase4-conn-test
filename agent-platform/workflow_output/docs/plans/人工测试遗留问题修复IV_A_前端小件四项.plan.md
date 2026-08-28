# IV-A · 前端小件四项（12x-2 / 17x-1 / 17x-2+2b / C-5）

> 规格 §3.1、§3.2、§4.2、§5.5。全部低风险热身项；A2/A3 同文件 UserPicker.vue 一次改完。

## 步骤

### A1 审计列表用户列显姓名+备注（12x-2）
- **目标**：列表用户列与详情弹窗同款 `username（operatorName）` + 备注 tag。
- **动作**（伪代码）：
  ```
  AuditLogView.vue 用户列 render:
    文本 = row.username（快照）+ (row.operatorName ? `（${operatorName}）` : '')
    row.operatorRemark 非空 → 追加 n-tag size=tiny（照详情弹窗 :38-46 样式）
  ```
- **文件**：`frontend/src/views/admin/logs/AuditLogView.vue`（1 个）
- **依赖**：无（后端 enrichOperatorMeta 已就绪）
- **验证**：vue-tsc 0；手动：审计页有姓名/备注用户行正确显示；已删用户行回落 username；remark 空不显 tag。

### A2 UserPicker 全选/反选（17x-1）
- **目标**：listbox 底部操作行全选/反选当前候选；已选/候选计数；chips 换行。
- **动作**（伪代码）：
  ```
  options.slice(0,20) → slice(0,50)
  listbox 尾部操作行:
    全选: modelValue = union(modelValue, options.map(id))
    反选: modelValue = xor(modelValue, options.map(id))
    计数: `已选 N / 候选 M`；M>加载上限 → 追加「当前候选 M 条」
    搜索进行中(seq 未 settle / loading) → 两按钮 disabled
  chips 容器: flex-wrap: wrap + 最大高度滚动
  ```
- **文件**：`frontend/src/components/common/UserPicker.vue`、`UserPicker.test.ts`（2 个）
- **依赖**：无
- **验证**：vitest run UserPicker.test.ts——新增用例：全选并入 / 反选翻转 / 换关键词后既有选择不丢 / chips 删除后全选重新加入 / loading 禁用。手动：邀请弹窗+批量充值页各验一遍。

### A3 邀请下拉出现时机（17x-2）
- **目标**：程序获焦（弹窗打开）不弹候选；用户点输入框/输入才弹。
- **动作**（伪代码）：
  ```
  @focus → 不再 openList（保留 focus 样式）
  @mousedown / @click on input → openList
  @input（有值变化）→ openList + doSearch
  其余（Esc/点外部/选中）关闭逻辑不变
  ```
- **文件**：同 A2 两个文件
- **依赖**：A2（同文件一次改）
- **验证**：vitest 用例「程序 dispatchEvent focus 不弹 / mousedown 弹 / 输入弹」；手动：打开邀请弹窗无候选闪现，点搜索框出现（L2 边界全档）。

### A4 候选接口权限对齐（17x-2b）
- **目标**：MANAGER 打开邀请弹窗不再 403。
- **动作**：`ProjectGroupService.searchCandidates`（:510）`requireOwner` → `requireRole(groupId, ROLE_MANAGER)`。
- **文件**：`backend/.../projectgroup/service/ProjectGroupService.java`（1 个）
- **依赖**：无
- **验证**：`mvn test -Dtest=ProjectGroup*`；curl：MANAGER 账号 GET candidates 200；MEMBER 403；组长/admin 200（L3）。

### A5 视频分辨率选择器整行（C-5）
- **目标**：分辨率选择器独占整行，各档完整可见。
- **动作**：PropertyPanel.vue:386-395 时长+分辨率两列 → 分辨率独立 `prop-panel__field` 整行（照 :381-385 比例先例）；时长保持原位。
- **文件**：`frontend/src/components/canvas/PropertyPanel.vue`（1 个）
- **依赖**：无（C-4 的参数保存联动在 C 轮补，此处只改布局）
- **验证**：vue-tsc 0；手动：视频节点面板 480p/720p/1080p/4K 完整可见可选（L5）。

## 联动边界（对照 master L1-L5）

L1 全选作用域/不丢已选/loading 禁用；L2 五档时机；L3 三角色；L4 回落快照/空不显；L5 窄面板不截断。

## 坑点

- A3 的 focus 样式类与 openList 解耦后，键盘 ↓ 首次进入仍须能开列表（focus 后按 ↓ = 用户交互信号，openList）。
- A5 与 B 轮 C-6 拖宽同文件——本 chunk 先 commit 再动 B。

## 完成标准

vitest 对应文件全绿 + vue-tsc 0 + `mvn test -Dtest=ProjectGroup*` 绿 + 手动 L1-L5 过 → commit `fix: 修复IV A 前端小件四项——审计列/UserPicker全选反选与时机/候选权限/分辨率整行`。
