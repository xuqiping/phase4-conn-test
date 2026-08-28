# IV-C · 画布保存与副本三项（C-4 自动保存补缺 / C-7 新建即定型 / C-8 副本完全独立）

> 规格 §5.4、§5.7、§5.8。同文件：CanvasBoard.vue / CanvasView.vue / PropertyPanel.vue / MentionTextarea.vue / nodeClone.ts。

## 步骤

### C1a 新增节点触发保存（C-4 缺口 1）
- **目标**：调色板/拖入/快速加节点（无连线分支）三路新增都进自动保存。
- **动作**（伪代码）：
  ```
  CanvasBoard.addNode（:591-609）尾部 → emit('structure-changed')
  CanvasView.confirmQuickAdd 无连线分支（:2575-2586）→ scheduleSave()（有连线分支已有 :2583）
  ```
- **文件**：CanvasBoard.vue、CanvasView.vue（2 个）
- **依赖**：B1 已 commit（同文件 CanvasBoard）
- **验证**：手动：调色板加节点→徽标转「保存中→已保存」→刷新节点在；**undo/redo 回归**——先测现状（新增可否 undo），补 emit 后行为不劣化；vitest CanvasBoard.test 补 emit 断言。

### C1b 文本失焦保存（C-4 缺口 2）
- **目标**：节点文本输入 blur + 名称框 blur 触发保存。
- **动作**（伪代码）：
  ```
  MentionTextarea: 既有 onBlur 延迟关候选层（:360-363）之后，若「未点击候选行」→ emit('blur-committed')
  PropertyPanel 各文本绑定处（prompt/description/synopsis 等 6 处）:
    @blur-committed → emit('data-changed')
  PropertyPanel.onRenameBlur（:1163-1172）: 查重后追加 emit('data-changed')
  ```
- **文件**：MentionTextarea.vue、PropertyPanel.vue（2 个）
- **依赖**：A5/B2/B4 已 commit（同文件 PropertyPanel）
- **验证**：vitest MentionTextarea/PropertyPanel 补用例（blur 触发 data-changed / 点候选行不误触）；手动：输入文本→不点别处直接关页签→重进文本在（L10 关键档）。

### C1c 参数变更保存（C-4 缺口 3）
- **目标**：比例/时长/分辨率/audioMode 变更即保存。
- **动作**（伪代码）：
  ```
  PropertyPanel :384/:389/:393/:608 四处 v-model 直绑 →
    :value + @update:model-value="写入 node.data + emit('data-changed')"
  （与既有离散选择器 :140-:300 同模式；禁 watch 深比较——会误判 C-8 副本 status）
  ```
- **文件**：PropertyPanel.vue（1 个）
- **依赖**：C1b 同文件
- **验证**：vitest PropertyPanel 用例（改分辨率→data-changed 触发一次）；手动：改比例→刷新保持。

### C2 新建即定型尺寸（C-7）
- **目标**：image/video 节点新建即 320×320。
- **动作**（伪代码）：
  ```
  CanvasBoard.addNode: type in (image, video) → data 预置 { width: 320, height: 320 }
  定型分支（updateNodeData:989-999）保留不删（存量画布无值节点仍走）
  types/canvas.ts:107-114 注释更新
  ```
- **文件**：CanvasBoard.vue、types/canvas.ts（2 个）
- **依赖**：C1a 同文件
- **验证**：vitest CanvasBoard 用例（三路新建均 320×320，文本节点仍 200 自适应）；手动 L11：调色板/拖入/快速加三路+生成结果不再跳变尺寸。

### C3 副本完全独立（C-8，决策 3）
- **目标**：副本带产物显示但与库/任务链彻底脱钩。
- **动作**（伪代码）：
  ```
  nodeClone.ts RESET_KEYS 调整:
    新增: 'assetId','assetName','assetVersion','taskId'
    移除(改为保留): 'fileId','previewUrl','coverPreviewUrl','outputText'
    保留清除: mediaTaskId/startedAt/finishedAt/errorMsg/assetHasUpdate/changeLog/localizeWarning
  status: (previewUrl || outputText) 存在 → 'success'，否则 → 'idle'
  firstFrameNodeId 保留原值（结构引用）
  ```
- **文件**：nodeClone.ts（1 个）
- **依赖**：无
- **验证**：vitest nodeClone.test.ts 重写断言（assetId/taskId 已清、产物四件保留、status 两分支、firstFrameNodeId 保留）；手动 L12 四档：副本即显产物+success 徽标 / 副本入库=新资产（不命中「已入库」）/ 副本再生成=新任务新图 / 删原件副本产物仍显。

## 联动边界（对照 master L10-L12）

L10 与 taskId 即时保存链（CanvasView:1329-1332）独立并存——C1a 的 structure-changed 走防抖、taskId 走即时，互不覆盖；L12 判重脱钩后组产出 tab「已入库」回填不受影响（按任务行 taskId 查，节点 data 无 taskId 不参与）。

## 坑点

- C1b 候选层行必须 `mousedown.prevent`（既有手段）防 blur 抢跑——否则点候选=触发保存+关层双误。
- C2 预置 320×320 后，用户未手拉过的**存量**节点定型仍由 :989-999 兜住；新增节点永不触发该分支（height 已是 number）——行为等效，勿删分支。
- C3 副本 status='success' 会带出上游面板/预估等下游逻辑——均为只读展示，无写路径，回归靠 vitest + L12 手动。

## 完成标准

vitest（CanvasBoard/PropertyPanel/MentionTextarea/nodeClone）全绿 + vue-tsc 0 + 手动 L10-L12 过 → commit `fix: 修复IV C 画布保存与副本三项——自动保存补缺/新建定型尺寸/副本完全独立`。
