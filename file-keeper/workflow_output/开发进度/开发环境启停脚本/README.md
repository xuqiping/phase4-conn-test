# 开发环境启停脚本

## 用途

在 Windows 开发机上一键管理 File Keeper 核心环境：Redis/Memurai、PostgreSQL、Java 后端和 Tauri 桌面端。

## 使用

```powershell
powershell -ExecutionPolicy Bypass -File .\启动.ps1
powershell -ExecutionPolicy Bypass -File .\关闭.ps1
powershell -ExecutionPolicy Bypass -File .\重启.ps1
```

启动脚本会先检查 `6379/5432/8088/1420` 是否已监听，已运行组件不重复启动。管理后台暂不在脚本范围内。

若当前 PowerShell 尚未继承用户级 `FILE_KEEPER_DB_PASSWORD`，脚本会在启动 Java 后端前主动读取该用户级变量。

## 安全边界

- 后端和 Tauri 只关闭启动脚本记录的 PID 树。
- JWT 临时密钥只通过子进程环境继承，不写入状态文件或日志。
- 数据库密码只从进程环境或 Windows 当前用户环境传给后端，不输出、不写入项目文件。
- 临时 PID 状态文件已加入 `.gitignore`。
