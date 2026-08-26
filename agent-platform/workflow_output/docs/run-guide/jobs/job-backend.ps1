# backend（8080）作业进程——由 start-all.ps1 以独立隐藏进程拉起。
# env 加载与 mvn 必须同一窗口（0_快速启动.md §4 警告：禁嵌套包裹），日志窗口内 *> 重定向。
$ErrorActionPreference = 'Stop'  # 仅准备阶段；启动原生进程前切 Continue（PS5.1 把原生 stderr 包成 NativeCommandError，Stop 会秒杀作业）
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))))   # agent-platform 根
. "$root\local-dev-env.ps1"
# Ark 参考视频配置（存在才加载；公网地址有效时自动开放参考视频上传/资产库选择）
if (Test-Path "$root\local-media-reference-env.ps1") { . "$root\local-media-reference-env.ps1" }
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location "$root\backend"
$ErrorActionPreference = 'Continue'
& 'C:\Program Files\apache-maven-3.9.16\bin\mvn.cmd' spring-boot:run *> "$root\logs\backend.log"
