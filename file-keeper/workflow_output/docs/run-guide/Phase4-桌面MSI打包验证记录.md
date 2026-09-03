# Phase 4：桌面 MSI 打包验证记录

> 日期：2026-09-03

## 构建命令

```powershell
cd E:\workspace\file-keeper
npm run tauri:build -- --bundles msi
```

## 问题与修复

- 首次构建因 Cargo 包包含 `file-keeper` 与 `office_ooxml_worker` 两个二进制，Tauri 错把 Worker 作为 MSI 主程序。
- 在 `src-tauri/Cargo.toml` 增加 `default-run = "file-keeper"` 后重新构建。

## 验证证据

- 前端 `vue-tsc && vite build`：通过。
- Rust Release：通过，主应用为 `src-tauri/target/release/file-keeper.exe`。
- WiX `main.wxs`：`Source` 指向 `file-keeper.exe`。
- MSI 路径：`src-tauri/target/release/bundle/msi/File Keeper_0.1.0_x64_zh-CN.msi`。
- 文件大小：`7,618,560` 字节（`7.27 MiB`）。
- SHA-256：`54B2A110AD4CB977A6F2916D2E79FD5CCCBA4C2049E8B9036E9BEDDDF96F56FD`。
- Authenticode：`NotSigned`。

## 非阻断警告

- Tauri 建议未来不要让 bundle identifier 以 `.app` 结尾；本次 Windows MSI 不受影响。
- 前端存在动态/静态重复导入提示。
- Rust 有 13 条未使用代码或返回值警告。
- 安装包未做代码签名，Windows 可能显示未知发布者。

## 本轮边界

- 已完成构建产物和主程序来源验证。
- 未自动安装或卸载 MSI，避免覆盖当前机器已有安装版；安装后的完整 GUI 验收需另行执行。
