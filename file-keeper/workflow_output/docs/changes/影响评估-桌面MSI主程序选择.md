# 影响评估：桌面 MSI 主程序选择

> 日期：2026-09-03
>
> 回滚锚点：`49a272c5`

## 问题

- `src-tauri` 同时包含桌面主程序 `file-keeper` 与 Office Worker `office_ooxml_worker`。
- Cargo 包未声明 `default-run`，Tauri 构建虽成功，却将 Worker 当作 MSI 主程序。
- WiX 清单中的主文件实际指向 `office_ooxml_worker.exe`，生成的安装包不可作为桌面客户端交付。

## 变更与影响

- 在 `src-tauri/Cargo.toml` 明确 `default-run = "file-keeper"`。
- 影响开发运行和 Tauri bundle 的默认二进制选择，不改变 Worker 的按需调用方式。
- 不改数据库、服务端接口、前端业务或安装包版本号。

## 验证与回滚

- 重新执行 `npm run tauri:build -- --bundles msi`。
- 检查构建日志与 WiX `main.wxs` 的 `Source` 必须指向 `file-keeper.exe`。
- 核对 MSI 文件存在、大小与 SHA-256。
- 如需回滚，恢复 `49a272c5`，但该状态不能用于双二进制 MSI 打包。

## 验证结果

- 构建退出码：`0`。
- Tauri 报告主应用：`src-tauri/target/release/file-keeper.exe`。
- WiX 主文件来源：`file-keeper.exe`，不再是 `office_ooxml_worker.exe`。
- MSI：`File Keeper_0.1.0_x64_zh-CN.msi`，大小 `7.27 MiB`。
- 安装包当前未签名；本机可安装，对外分发时 Windows 可能提示未知发布者。
