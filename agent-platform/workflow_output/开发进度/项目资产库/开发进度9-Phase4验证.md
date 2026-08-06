# 开发进度9 · Phase4 运行验证（项目资产库）

> SDD Phase 4（Run + Review + 性能）。承接 [开发进度8.md](开发进度8.md)（S14 文档收尾）。
> 验证日期：2026-08-05。结论：**✅ 放行 Phase 5**。

## 一、环境就绪

- 后端启动 Flyway 自动跑 **V56–V59**（55→59，4 个迁移，0.127s），6 张 asset 表 + `asset:write` 权限 seed（gated admin）落地。
- DB 验证：`asset_projects / asset_project_members / assets / asset_versions / asset_role_links / asset_bindings` 全建；`asset:write` 仅 admin 角色。
- 前后端 + sidecar 全起（8080 / 5173 / 8090），vue-tsc 绿。

## 二、关键路径 E2E（API 层，38/38 绿）

脚本 [phase4_asset_e2e.py](../../docs/测试方案/phase4_asset_e2e.py) + [phase4_asset_bridge_sec.py](../../docs/测试方案/phase4_asset_bridge_sec.py)。

- 建项目 / 默认五桶 / 列表 / 详情
- 建 PROMPT 资产 / 矩阵计数（cells+typeTotals）/ 双轴筛选（type×role 命中）
- 成员邀请 VIEWER / **双层授权**（平台 `asset:write` + 项目成员）viewer 无 `asset:write` 直访=403
- 状态机 LOCK / UNLOCK / ARCHIVE（默认列表隐藏归档）/ 取消归档
- 版本时间线 + 新建 v2 / 一致性包保存 / 转让 owner（L1）
- 删除项目级联软删（L4）/ 删后 404
- **L5 节点入库**真链：canvas-import 首次 created / 重复入库三态（mode 空→duplicate、NEW_VERSION→v2）
- **L6 库→画布引用**：resolve 当前版 + 锁定 v1（版本隔离）/ usages 含 PRODUCED 绑定
- **安全向量**：viewer 无权直访=403、user2（asset:write 但非成员）写/读/邀请=403（数据层 `requireWrite`/`loadAccessible` 兜底）、resolve 不存在 asset=404 不泄露

## 三、User-Ops UI 验证（Playwright，截图存档）

- 项目列表 / 新建项目弹窗 / 项目卡片（5 叙事角色计数）
- 项目详情矩阵页（顶栏类型×左栏角色双轴 + 计数徽标）/ 空态双引导
- 资产详情抽屉（状态机 定稿/归档/解锁 + 版本时间线 + 一致性包 + 使用记录）
- 画布 @ 引用：文本节点提示词输入 @ → 无祖先显「无可引用祖先节点（无连线可达 / 画布成环）」graceful 不崩

## 四、审查发现 + 修复（第二 AI cavecrew-reviewer，1🔴+2🟠+10🟡）

修复 1 阻断 + 2 重要 + 3 次要（runtime 9/9 验证，[phase4_asset_review_fixes.py](../../docs/测试方案/phase4_asset_review_fixes.py)）：

| 级 | 问题 | 修复 |
|---|---|---|
| 🔴 | `AssetScriptService.readScriptBody` 读 `body` 键，但 SCRIPT 规范是 `{"synopsis":...}`（bridge/前端新建剧本一致）→ UI/入库剧本 breakdown 全报「正文不能为空」，测试用 `{"body"}` 掩盖 | 改 synopsis 优先、body 回退；6 处测试 fixture 改 synopsis + 加 legacy body 回退用例 |
| 🟠 | REFERENCE 绑定从不落库（`resolve` 没调 `recordReference`），文档承诺双向追溯但「使用记录」只有 PRODUCED | `ResolveRequest` 加 `canvasId/nodeId`；带上下文时 `resolve` 落 REFERENCE；AssetPicker 传 canvasId+nodeId（CanvasView `:canvas-id`）+ 2 后端测 |
| 🟠 | 一致性包清空不可能：前端空串→null，后端 null=「不改」，与文档「空串=清空」矛盾 | 后端 `mergeStringField`：null=不改 / 空串=移除键 / 非空=覆盖（3 字段）；前端空串原样传 + 测试 |
| 🟡 | FIX-B 不完整：状态机返 meta-only 后 fileId 也丢，文件类下载失效 | doAction 同 content 一并保留 fileId + 测试 |
| 🟡 | content 无 JSON 校验：非 FIX-A 客户端传纯文本→500（被误判 409） | `validateContentJson` 前置 readTree，`create` + `createVersion` 两入口校验→400 |
| 🟡 | FIX-B 保留分支无测试 | 加 content/fileId=null mock 用例断言旧值存活 |

**记档 backlog（6 个🟡，预存边界，非阻断）**：删资产留旧 PRODUCED 绑定（re-import 重复检测歧义）/ `create` mediaType 校验 `forCreate` 死参（直访 API 可建无 fileId 文件类）/ `ensureRoleLink` select-then-insert 并发竞态 / `changeRole` 忽略 `@Version` 返回（并发静默 no-op）/ ShareDialog 邀请候选 200 上限 / `asset:write` 兼读门命名模糊 / 通用桶删除时资产失根边界。

## 五、性能评测（120 资产，30 次，达标）

| 接口 | p50 | p95 | p99 | 目标 |
|---|---|---|---|---|
| 项目资产列表 size=24 | 10.3ms | 18.7ms | 30.9ms | 首屏<1s ✅ |
| 矩阵计数 count | 23.7ms | 32.5ms | 33.2ms | — |
| 矩阵筛选 PROMPT+人物 | 28.1ms | 37.1ms | 44.2ms | <300ms ✅ |
| 搜索 q='古风' | 7.8ms | 31.9ms | 33.6ms | <500ms ✅ |
| 项目详情 | 6.4ms | 26.8ms | 29.0ms | — |
| 项目列表 | 5.8ms | 30.0ms | 33.0ms | — |

部分索引 `idx_asset_matrix(project_id,media_type,updated_at) WHERE deleted=0` + 角色走 `asset_role_links` 关系表生效，p99 全 <45ms。

## 六、质量门禁（全绿）

- 后端 asset 包 **75/75**（原 72 + SCRIPT legacy 1 + REFERENCE 2）
- 前端 **vue-tsc 0 错** + vitest **38/38**（asset 32→36 + AssetPicker REFERENCE + FIX-B 保留 + ConsistencyPack 空串 + SCRIPT 包 synopsis）
- runtime E2E **38 + 9** 全绿

## 七、出口条件（Phase 4 → 5）

- [x] 应用跑通，核心路径 API+UI 亲眼验证
- [x] User-Ops 关键页验证通过 + 2 阻断 bug 修复 + 截图
- [x] 快速启动速查表已产出 + 补资产库部署要点（[快速启动速查表.md](../../docs/run-guide/快速启动速查表.md)）
- [x] 性能评测达标记录
- [x] 第二 AI + 人 review，阻断/重要/相关次要全修，6 个边界次要记档
- [x] 测试全绿；修复待 commit（用户授权后）

**结论：放行 Phase 5（发布迭代）**。剩真人手测（L1-L10 全反向边界 + 各上传类型真文件 + 画布连线 @ 真插值）可并行进 P5。
