# Windows Office COM Worker 技术尖峰

> 本文只记录 Chunk 0 的可行性证据和安全边界，不代表生产 Worker 已实现，也不承诺尚未实测的 Office 版本兼容性。

## 1. 当前结论

- 2026-08-25 在当前 Windows 测试机上，`Excel.Application`、`Word.Application`、`PowerPoint.Application` COM ProgID 均能创建独立自动化实例并正常 `Quit`。
- 三个应用报告主版本 `16.0`、Build `20416`；Click-to-Run 注册信息为 `VersionToReport=16.0.20416.20004`、`Platform=x64`、`ProductReleaseIds` 含 `ProPlus2019Retail`。
- 当前只能证明 Office 2019 x64 单一代际的 COM 创建可行。计划要求的“至少两代 Office”未满足，不能声称 Office 2021、Microsoft 365 或其他 Build 已兼容。
- 当前系统可找到 `dotnet` 命令，但 `dotnet --list-sdks` 无结果，即 .NET SDK 缺失；因此本 Chunk 不创建 .NET/Interop Worker，只保留路线与安全结论。

## 2. 生产 Worker 必须遵守的原则

- 禁止宏执行：启动后立即设置 `AutomationSecurity=msoAutomationSecurityForceDisable`，同时关闭应用级警告；绝不修改用户 Trust Center。
- 独立实例：每个受控 Worker 创建自己的 Office Application，不附着到用户已打开的 Office 实例。
- PID 归属：记录由本任务创建的 Office/子进程 PID、启动时间和任务 ID；只能终止匹配实例，禁止按 `EXCEL.EXE/WINWORD.EXE/POWERPNT.EXE` 名称批量结束进程。
- 超时退出：先请求取消和 `Quit`，等待受控宽限期；仍无响应时只终止已核验归属的 PID，并记录稳定错误码。
- 无界面交互：默认不可见，关闭弹窗；遇到密码、受保护视图、修复提示、Trust Center 阻断时返回问题码，不用固定坐标点击。
- 文件边界：输入只读，输出只写任务临时目录；路径、密码和正文通过 JSONL stdin 或受控内存传递，不放命令行和日志。
- 发布边界：COM 保存成功不等于任务成功，仍需重新打开、结构校验、哈希记录和原子发布。

## 3. 本机复现命令摘要

使用 PowerShell `Type.GetTypeFromProgID` + `Activator.CreateInstance` 分别创建三种 Application，设置不可见/禁宏/关闭警告，读取 `Version/Build` 后调用 `Quit` 和 `FinalReleaseComObject`。注册表证据来自：

```text
HKLM\SOFTWARE\Microsoft\Office\ClickToRun\Configuration
```

该探测不打开任何用户文档，也不验证宏、公式、母版或外链保真。

## 4. 尚未满足的检查点

- 缺第二代 Office 测试机。
- 缺真实无敏感 `.xlsm/.pptm` 宏样本复验。
- 缺签名、密码宏、外链、复杂母版的保存前后比较。
- 缺生产 Worker 的 PID、心跳、取消、超时和崩溃恢复实现。

因此检查点 0 保持未通过，等待补齐兼容机和真实样本后由用户确认。

## 术语表

| 术语 | 大白话 |
|---|---|
| COM | Windows 上让程序控制已安装 Office 的官方自动化接口 |
| ProgID | 用来找到某个 COM 应用的名字，例如 `Excel.Application` |
