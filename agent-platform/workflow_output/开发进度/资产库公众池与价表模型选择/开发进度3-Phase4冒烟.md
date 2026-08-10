# Phase 4 浏览器冒烟验证（2026-08-11）

## 环境

- 分支：`beifen`，HEAD `882a2646`。
- PostgreSQL 16 / Redis / runtime-sidecar / Vite 均已运行；后端使用当前工作区重新执行 `mvn spring-boot:run`。
- Flyway 最新成功版本为 V93，V88 已在真实 PostgreSQL 执行。
- 浏览器：Codex in-app Browser 的 Playwright API；账号 `admin`、`vis_test`、`newuser`。

## 已通过路径

1. 管理员发布：发布弹窗固定“官方发布 / 开放使用”；发布后公共卡片显示“官方发布、直接使用”。
2. 普通 OWNER 发布：可选择“开放使用”或“申请后使用”；以审批模式发布后公共池可见摘要。
3. 申请状态机：`newuser` 申请后显示“等待审批”；OWNER 批准后显示“使用”；撤销后显示“重新申请”，直接访问详情返回 403。
4. 公共只读：获批用户进入详情显示“浏览者 / 公共项目”，不显示分类编辑、上传、新建等写控件。
5. 成员授权：普通 OWNER 可按用户名远程搜索 `newuser` 并邀请为浏览者；被邀请账号“共享给我（1）”立即出现项目；移除时有 L1 失权确认。
6. 复制：`newuser` 将官方公共项目中的图片资产复制到自己的 `Phase4复制目标`，目标项目出现独立资产 v1，源项目不变。

## 发现的问题

### P0：公共媒体与复制副本文件访问 403

- 复现：`newuser` 打开管理员官方公共项目（5 个图片资产）时连续出现 5 次 `403 · Request failed with status code 403`；复制其中一张到自己的项目后，目标项目仍出现 1 次 403。
- 数据证据：复制资产 `assets.id=145, project_id=9` 的当前版本仍引用源文件 `0afea9f1-...jpeg`，该文件 `stored_files.owner_user_id=1`（admin），目标项目 owner 为 `newuser(id=4)`。
- 代码边界：`SecurityEndpointRegistry` 明确 `/api/files/**` 仅文件 owner/admin；复制复用 fileId，未给公共 ACL 或副本 owner 建立可读关系。
- 影响：公共项目虽然能列出资产，真实图片/视频/音频预览、下载及复制后的媒体使用仍可能失败，违背“获批/OPEN 后可只读使用、复制后可独立创作”。

### P1：价表候选未排除已有配置

- 复现：管理员“价表配置 → 新增价表”下拉仍列出 `deepseek-chat`、`glm-5.1`、`Cdance2.0` 等当前价表已有模型。
- 数据证据：现有 `pricing_rule` 7 行的 `provider_id` 全为 null；候选过滤仅将 `provider_id != null` 的规则加入排除集合，因此历史价表全部漏过。
- 影响：UI 不满足“只显示未配置全局模型”，且后端按 `providerId+model` 判重会允许历史 null-provider 规则旁再建一条重复身份。

### P2：审批列表不显示用户名

- 复现：OWNER 的审批弹窗显示“用户 #4”，而非 `newuser`。
- 代码证据：`PublicAccessRequestVO` 只有 `applicantId`；前端 `PublicAccessDialog.vue` 固定拼接 `用户 #${applicantId}`。
- 影响：项目 OWNER 难以识别申请人，审批操作可用但可读性不足。

## 结论

- 公众池发布、申请/批准/撤销、公共 VIEWER、成员授权和复制事务主链路已跑通。
- 因 P0 直接阻断公共媒体实际使用，本轮 **不满足 Phase 4 放行条件**，应返回 Phase 3 修复 P0；P1、P2 同轮收口后再复测。
- `2x. 资产库和无限画布.md`、`7x_积分系统.md` 暂不标记“已解决”。

## 本轮测试数据

- `asset_projects.id=8`：`Phase4审批项目`，OWNER=`vis_test`，审批模式；申请记录 id=1 当前为 `REVOKED`。
- `asset_projects.id=9`：`Phase4复制目标`，OWNER=`newuser`；复制资产 id=145。
- 管理员项目 id=6 已发布为官方 OPEN；用于验证公共卡片和复制。
