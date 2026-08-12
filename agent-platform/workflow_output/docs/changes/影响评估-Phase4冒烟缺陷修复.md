# 影响评估：Phase 4 冒烟缺陷修复

- 日期：2026-08-11
- 回滚锚点：`9d99eda`
- 规模：小型兼容性修复；不删除接口，不改变端口，不引入新表；新增一个可回滚索引迁移。

## 变更目标

1. 资产版本引用的文件应服从资产项目读取 ACL：OWNER/成员、OPEN 公共访问、已批准公共访问均可读取；撤销、移出公共池或移除成员后立即失权。复制副本因目标项目归属而可继续读取同一 fileId。
2. `provider_id=null` 的既有全局价表应占用同名模型，不能再次出现在“未配置模型”候选，也不能绕过后端重复校验。
3. OWNER 审批列表返回并显示申请人的 `username`，不再只显示内部用户 ID。

## 调用链与引用点

### 文件访问 ACL

- 前端：`AssetCard` / `AssetDetailDrawer` → `/api/files/{fileId}`。
- 后端：`FileController#get` → `FileStorageService#loadPath` → `FileSharedAccessGrantor` 列表。
- 新增资产域 grantor，复用 `AssetMapper` 单 SQL 检查 `asset_versions → assets → asset_projects`，并联动成员表、公众池模式和审批状态。
- 受影响联动：成员移除、公众池移出、审批撤销、复制副本保留；均必须回归。

### 价表候选

- `PricingConfigView` → `/api/billing/config/pricing/available-models` → `PricingConfigService#availablePricingModels`。
- `createPricingRule` 的重复校验需与候选过滤保持一致：provider 专属规则按 `(providerId, model)`；全局规则按 `model` 占用所有 provider 候选。

### 审批用户名

- `AssetPublicAccessController#list` → `AssetPublicAccessService#listForOwner` → `AssetPublicAccessRequestMapper`。
- Mapper 只联查 `users.id/username`，不读取 password/email；前端类型和 `PublicAccessDialog` 使用 `applicantUsername`，缺失时保留 ID 兜底。

## 回归范围

- 后端：文件 owner/admin 原有安全边界、无资产引用文件仍 403、成员/OPEN/APPROVED 放行、撤销/移出拒绝；价表候选及创建判重；审批状态机和列表权限。
- 前端：审批用户名、四状态动作矩阵、公共详情只读、媒体预览、复制目标项目预览。
- 文档：资产 Feature Map/User-Ops、计费 Feature Map/User-Ops、Phase 4 验证记录、变更记录。

## 运维与安全

- 不放宽匿名访问；仍须登录，且文件必须存在于请求者可读的资产项目引用链中。
- grantor 异常继续 fail-closed；单次文件读取新增一次存在性计数 SQL。实库确认 `asset_versions.file_id` 尚无索引，因此新增 V94 部分索引，避免文件预览时扫描版本表。
- 不改变已有健康检查、日志、监控或启动方式。

## 回滚

- 代码和文档按本次独立 commit 回滚到 `9d99eda`。
- V94 仅新增 `asset_versions(file_id)` 部分索引；回滚时可 `DROP INDEX idx_asset_version_file`，无不可逆数据写入。
