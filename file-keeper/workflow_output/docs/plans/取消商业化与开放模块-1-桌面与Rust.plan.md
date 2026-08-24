# 子计划 1：桌面端与 Rust

> 依赖：[主计划](取消商业化与开放模块.plan.md)。只含伪代码。

## Step 1：锁定桌面端新访问行为（RED）【进行中】

- [ ] **目标**：用失败测试定义“本地免登录、服务端需登录”。
- **动作**：
  - 新增 App 访问模式测试：未登录时 files/processes/clipboard 可切换；截图快捷入口不因授权拒绝。
  - 新增工作汇报测试：未登录不挂载业务组件，展示可聚焦的登录按钮；按钮具备可访问名称。
  - 扩展设置测试：AI Tab 始终显示；未登录显示登录引导且不加载 AI 配置。
  - 先运行目标测试，确认因现有商业门禁而失败。
- **文件**（≤20）：
  - `src/components/__tests__/appAccessMode.test.ts`：新访问规则测试。
  - `src/components/__tests__/settingsDialog.test.ts`：AI 登录引导测试。
  - `src/components/__tests__/appScreenshot.test.ts`：截图免授权测试。
  - `src/App.vue`、`src/components/SettingsDialog.vue`：仅作为被测对象，本步不改实现。
- **依赖**：无。
- **验证**：运行上述 Vitest 文件，失败原因必须是“仍依赖商业授权”，而非测试环境错误。

## Step 2：拆出独立设备身份能力（RED→GREEN）

- [ ] **目标**：保留设备记录/禁用所需 `deviceId`，消除服务端模块对商业 Store 的依赖。
- **动作**：
  - 先写 `deviceStore` 测试：首次创建设备身份、重复读取稳定、登录后注册/心跳、禁用错误透传。
  - 从 `commercialAuth.ts` 提取设备类型、持久化和注册请求到独立 `device.ts`。
  - 新建 `deviceStore`，伪代码：`ensureIdentity → registerAuthenticatedDevice → expose deviceId`。
  - `authStore.login/restoreSession` 成功后初始化设备；logout 只清会话，不删除设备身份。
  - `workReportStore`、`aiConfigStore` 改从 `deviceStore` 读取设备身份；未登录明确抛“未登录”。
- **文件**（≤20）：
  - `src/api/device.ts`（新建）、`src/stores/deviceStore.ts`（新建）。
  - `src/api/__tests__/device.test.ts`（新建）、`src/stores/__tests__/deviceStore.test.ts`（新建）。
  - `src/stores/authStore.ts`、`src/stores/workReportStore.ts`、`src/stores/aiConfigStore.ts`。
  - `src/stores/__tests__/authStore.test.ts`、`src/stores/__tests__/workReportStore.test.ts`。
  - `src/api/commercialAuth.ts`、`src/stores/commercialAuthStore.ts`：移出设备职责，暂留兼容代码。
- **依赖**：Step 1。
- **安全检查**：设备 ID 不作为身份认证替代物；所有 HTTP 请求仍带 JWT。
- **验证**：目标 Store/API 测试通过；全局搜索确认业务 Store 不再导入 `commercialAuthStore`。

## Step 3：移除桌面商业门禁和 UI（GREEN）

- [ ] **目标**：实现 Step 1 的访问规则并停止新客户端调用商业授权接口。
- **动作**：
  - `App.vue` 删除授权状态、免费模块选择、`canUseTab/moduleTitle/showFreeModuleSelector` 和授权 watcher。
  - 本地 Tab 直接切换；工作汇报区按 `authStore.isAuthenticated` 在业务组件与登录引导间切换。
  - 应用挂载后直接幂等启动剪贴板监听；卸载时停止。
  - `authStore` 删除匿名初始化、登录授权初始化和 refresh 失败后的匿名回退。
  - Settings/ReportConfig 中 AI 可用性改为登录态，不再检查 `ai` 权益。
  - 删除不再使用的商业 UI 组件和对应测试；保留商业 API/Store 文件到兼容清理阶段，但不得有运行时引用。
  - 更新中英文文案，去掉“未授权/试用/免费模块”，补“请先登录”。
- **文件**（≤20）：
  - `src/App.vue`、`src/stores/authStore.ts`。
  - `src/components/SettingsDialog.vue`、`src/components/work-report/ReportConfigForm.vue`。
  - `src/components/EntitlementStatus.vue`（删除）、`src/components/FreeModuleSelector.vue`（删除）。
  - `src/components/__tests__/entitlementStatus.test.ts`（删除）、`src/components/__tests__/freeModuleSelector.test.ts`（删除）。
  - `src/components/__tests__/authDialog.test.ts`、`src/components/__tests__/aiConfigSettings.test.ts`。
  - `src/locales/zh-CN.ts`、`src/locales/en.ts`。
- **依赖**：Step 2。
- **无障碍**：登录引导按钮可键盘聚焦，含明确文本/aria-label；禁用态不只靠颜色表达。
- **验证**：Step 1 测试转绿；桌面端全量测试与 build 通过；`rg` 确认生产代码无商业 UI 引用。

## Step 4：移除 Rust 命令模块门禁

- [ ] **目标**：原生命令不再需要 signed entitlement，同时保留业务输入校验。
- **动作**：
  - 先调整/补充命令层测试，验证无授权状态参数时仍执行到业务校验路径。
  - 从剪贴板、进程、截图、工作汇报命令签名中删除 `SignedEntitlementState` 参数和 `require_module` 调用。
  - `main.rs` 停止 manage 授权状态、停止注册 signed entitlement invoke 命令。
  - `commands/auth.rs` 暂留为废弃兼容源码，不再接入运行链路；后续物理清理阶段删除。
  - 核对 Tauri 前端调用参数与 Rust 新签名一致。
- **文件**（≤20）：
  - `src-tauri/src/main.rs`。
  - `src-tauri/src/commands/clipboard.rs`、`processes.rs`、`process_management.rs`、`screenshot.rs`、`work_report.rs`。
  - `src-tauri/src/commands/auth.rs`：标记废弃并保留自有签名验证测试，运行链路不再引用。
  - `src/api/clipboard.ts`、`src/api/process.ts`、`src/api/processes.ts`、`src/api/screenshot.ts`、`src/api/rustWorkReport.ts`：核对参数，不做无关重构。
- **依赖**：Step 3。
- **安全检查**：不得删除路径存在性、区域尺寸/溢出、PID/句柄、剪贴板敏感内容等校验。
- **验证**：`cargo test` 通过；桌面 invoke API 测试通过；`rg "require_module" src-tauri/src/commands` 仅允许出现在废弃 auth 文件内部。

## Step 5：子计划 1 回归与提交

- [ ] **目标**：桌面与 Rust 形成可回滚存档点。
- **动作**：执行桌面测试/build、Rust 测试；人工验证未登录本地功能、登录引导、剪贴板监听、截图和进程操作。
- **文件**：本子计划已修改文件，不新增实现文件。
- **依赖**：Step 1-4。
- **验证**：测试输出无失败；记录命令和结果；提交信息说明“取消客户端商业门禁但保留登录型服务端入口”。

## 术语表

| 术语 | 大白话 | 简单案例 |
|---|---|---|
| 设备身份解耦 | 把设备编号从商业授权代码搬到独立位置 | 工作汇报仍能带 `deviceId`，但不查权益 |
| 幂等 | 重复执行也只产生一次有效结果 | 连续启动剪贴板监听不会开两个线程 |
| RED→GREEN | 测试先失败，再通过最小实现变绿 | 未登录剪贴板测试先被门禁拦截，删门禁后通过 |
