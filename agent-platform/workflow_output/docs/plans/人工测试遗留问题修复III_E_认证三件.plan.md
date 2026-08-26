# Chunk E · 认证三件（12x-2/3/4）

> 规格 §10。后端 auth/ + 前端 admin/个人中心。

## E1. 账号锁定可见 + 管理员解锁（12x#2）

- 文件：`auth/service/AuthService.java`（登录锁定话术）、`auth/controller/UserController.java`（unlock 端点）、`views/admin/UserManageView.vue`（解锁按钮）
- 伪代码：
  ```
  登录: 命中账号锁(且为带密码尝试) → 错误体 {code, msg:"账号已锁定，将于 MM-dd HH:mm 自动解锁，可联系管理员提前解锁", data:{lockedUntil}}
        未锁账号维持 SEC-FR-001 固定话术（防爆破侧不放松）
  PUT /api/users/{id}/unlock: @RequirePermission(user:update) + @AuditLog(user:unlock)
        仅 status=LOCKED/locked_until 非空行可解锁 → 清 locked_until + Redis 失败计数 + status→ACTIVE
        BANNED/DISABLED 行返回 400「封禁/禁用请用启用动作」（语义分离，前端不显解锁按钮）
  前端: 锁定行「解锁」按钮（确认弹窗显示锁定到期时间）；登录页错误透传 lockedUntil 文案
  ```
- 验证：IT——锁 30s 内登录显解锁时间；unlock 后立即可登；BANNED 行 unlock 400；审计行落。单测话术分支。

## E2. 姓名编辑与全站 displayName（12x#3）

- 文件：`auth/entity/User.java`（name 已有）、资料接口（个人中心 profile 所在 controller/service）、`views/SettingsView.vue`（资料设置加姓名输入 ≤64 字）、`views/admin/UserManageView.vue`（姓名列+行内编辑）、`frontend/src/utils/displayName.ts`（新）
- 伪代码：
  ```
  个人中心 PUT profile: name 可改（trim，≤64）
  管理端: 姓名列+编辑（同备注交互）；keyword 搜索已匹配 name（用户列表 SQL 确认补 name 条件）
  displayName(user) = user.name?.trim() || user.username
  替换面（分批，本 chunk 只换高频处）: 项目组成员表、审计用户列、产出用户列、@提及候选、组邀请列表
  ```
- 验证：IT keyword 含 name 命中；vue-tsc；手工个人中心改名→成员表即显新名。

## E3. UserPicker 统一选人组件 + 备注批量（12x#4）

- 文件：`frontend/src/components/common/UserPicker.vue`（新）、后端用户搜索接口（复用用户列表 keyword，确认 name/remark 均匹配——12x-1 已含 remark，E2 补 name）、替换三处：`views/ProjectGroupsView.vue`（邀请成员/批量拉组）、`views/admin/WalletAdminView.vue`（批量充值选人）、备注展示：`ProjectGroupsView.vue` 成员表、`views/admin/logs/AuditLogView.vue` 详情、产出用户列（F 联动）
- 伪代码：
  ```
  UserPicker: props{multiple, modelValue}; 远程搜索 debounce 300ms keyword→用户列表接口(分页取前20)
    行渲染: displayName · username · 备注 n-tag(悬浮全文, 空则不显)；已选 chips 可移除；键盘上下选/回车确认(Esc 关)
    a11y: role=listbox/option, aria-activedescendant
  ```
- 验证：vitest 搜索渲染/chips 移除/键盘导航；手工三处替换后按备注「A 班」筛出全班→批量充值/拉组。

## 验证收口

- 后端 `mvn test` + IT；前端 vue-tsc/vitest；审计字典若加 user:unlock 动作码→四改同步（字典完整性单测把关）。
