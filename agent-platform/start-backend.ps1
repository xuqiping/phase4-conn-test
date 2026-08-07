# 独立窗口启动 backend（8080）。由 Claude 会话外调用，进程不随会话退出。
# 用法：powershell -File start-backend.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
. "$root\local-dev-env.ps1"
$env:JAVA_HOME = 'D:\IT\java\jdk-17.0.20+8'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location "$root\backend"
& 'D:\IT\apache-maven-3.9.16\bin\mvn.cmd' spring-boot:run
