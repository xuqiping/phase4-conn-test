# Git 自动同步任务说明

本仓库（`e:\workspace`）已与 GitHub 远程仓库绑定，并通过 Windows 任务计划实现每日自动同步。本文档说明配置、运行机制、注意事项和常用维护命令。

---

## 一、配置概览

| 项目 | 内容 |
| --- | --- |
| 本地工作目录 | `E:\workspace` |
| 远程仓库 | `https://github.com/xuqiping/AI-Projects.git` |
| 默认分支 | `main` |
| 代理设置 | 仅对 `github.com` 走 `http://127.0.0.1:7890` |
| 凭据存储 | Windows Credential Manager（首次推送已缓存） |
| Git 用户 | `ap <195363085@qq.com>` |

---

## 二、关键文件

| 文件 | 作用 |
| --- | --- |
| [.gitignore](.gitignore) | 忽略 Python 缓存、IDE 配置、敏感文件、日志、`*application*.*` |
| [daily-sync.ps1](daily-sync.ps1) | 每日同步脚本：`pull --rebase` → `add -A` → `commit` → `push` |
| [.git-sync.log](.git-sync.log) | 同步日志，超过 1MB 自动轮转为 `.git-sync.log.old` |

---

## 三、同步流程

每次任务触发时，[daily-sync.ps1](daily-sync.ps1) 会按顺序执行：

1. **拉取远程更新**：`git pull --rebase --autostash`
   - `--rebase` 保持线性历史
   - `--autostash` 自动暂存未提交的改动避免冲突
   - 失败时自动 `git rebase --abort` 避免留下半成品状态
2. **暂存所有改动**：`git add -A`（遵循 `.gitignore`）
3. **提交（仅当有改动时）**：`git commit -m "Auto sync: 年-月-日 时:分"`
4. **推送**：`git push`

任意一步失败都会写入日志并以 `exit 1` 退出，下一天会重新尝试。

---

## 四、Windows 任务计划

| 项目 | 值 |
| --- | --- |
| 任务名称 | `WorkspaceGitSync` |
| 触发时间 | 每天 10:25 |
| 运行用户 | `Administrator` |
| 登录类型 | `InteractiveToken`（无需保存密码） |
| 运行级别 | `LeastPrivilege`（普通权限） |
| 错过补跑 | `StartWhenAvailable` 已开启 |
| 电池模式 | 不阻止运行 |
| 最长执行时间 | 30 分钟 |

---

## 五、三件必须知道的事

1. **代理软件必须保持运行**
   本地代理（Clash/V2Ray 等监听 `127.0.0.1:7890`）若关闭，10:25 的同步会因为连不上 GitHub 而失败。该次失败不会自动重试，但下一天的运行会把累积改动一起推送。

2. **10:25 时电脑必须已开机并登录**
   任务采用 `InteractiveToken`（不需要存密码），代价是仅在已登录会话里执行。如果当天没开机，已开启的 `StartWhenAvailable` 会让任务在你下一次开机并登录后尽快补跑一次。

3. **不要把敏感文件放进仓库**
   `.gitignore` 已忽略 `.env`、`credentials.json`、`*.key`、`*.pem` 等常见敏感文件，但请避免把含密码、API Key 的文件以其他名字放在仓库内。GitHub 的公开仓库一旦泄露密钥需要立即吊销并轮换。

---

## 六、常用维护命令（PowerShell）

```powershell
# 立即手动触发一次同步（用于验证）
schtasks /Run /TN WorkspaceGitSync

# 查看任务详情、下次运行时间、上次结果码
schtasks /Query /TN WorkspaceGitSync /V /FO LIST

# 临时停用 / 重新启用
schtasks /Change /TN WorkspaceGitSync /DISABLE
schtasks /Change /TN WorkspaceGitSync /ENABLE

# 修改触发时间（例如改为 09:30）
schtasks /Change /TN WorkspaceGitSync /ST 09:30

# 完全删除任务
schtasks /Delete /TN WorkspaceGitSync /F

# 查看最近 30 行同步日志
Get-Content E:\workspace\.git-sync.log -Tail 30

# 实时跟踪日志（任务运行期间观察）
Get-Content E:\workspace\.git-sync.log -Tail 50 -Wait
```

---

## 七、故障排查

| 现象 | 排查方向 |
| --- | --- |
| 日志显示 `pull failed` 且包含 `Failed to connect` | 代理未启动；检查 `127.0.0.1:7890` 是否可用：`curl -m 5 -sI -x http://127.0.0.1:7890 https://github.com` |
| 日志显示 `Authentication failed` | Credential Manager 凭据失效；在终端手动 `git push` 一次让 GCM 重新登录 |
| 任务上次结果码 `267011` 或 `0x800710E0` | 表示运行时账号未登录（错过执行）；下次登录后会自动补跑 |
| 任务上次结果码 `0` | 成功 |
| 任务从未运行（`上次运行时间: 1999/11/30`） | 任务刚创建尚未触发；可用 `schtasks /Run` 手动测试 |
| pull 时出现合并冲突 | 自动同步会 `rebase --abort` 退出，需要手动解决冲突后再 push |

---

## 八、修改/重建脚本

如需调整同步逻辑（如换分支、加签名、跳过某些目录），编辑 [daily-sync.ps1](daily-sync.ps1) 即可，任务计划无需重建（任务调用的是脚本路径而非内嵌命令）。

如需重新创建任务计划，可在 PowerShell 里运行：

```powershell
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument '-WindowStyle Hidden -ExecutionPolicy Bypass -File E:\workspace\daily-sync.ps1' `
    -WorkingDirectory 'E:\workspace'
$trigger = New-ScheduledTaskTrigger -Daily -At '10:25'
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable `
    -DontStopIfGoingOnBatteries -AllowStartIfOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 30)
$principal = New-ScheduledTaskPrincipal -UserId 'Administrator' `
    -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName 'WorkspaceGitSync' `
    -Description 'Daily git sync for E:\workspace to GitHub' `
    -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force
```
