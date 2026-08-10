@echo off
REM ============================================================
REM  Agent Platform 备份环境模板（运维系统 OPS-FR-13~17）
REM  用法：复制本文件为 backup-env.bat（同目录，已 gitignore 不入库），填真实值。
REM  红线：真实密码/webhook 绝不提交进仓库——backup-env.bat 只存在服务器本地。
REM ============================================================

REM --- PostgreSQL ---
set "PG_BIN=D:\IT\postgresql\bin"
set "PG_HOST=localhost"
set "PG_PORT=5432"
set "PG_USER=postgres"
set "PG_DB=agent_platform"
set "PG_PASSWORD=在此填真实密码"

REM --- 备份根目录（全英文路径！中文路径 + 计划任务组合是乱码炸点） ---
set "BACKUP_ROOT=D:\backup\agent-platform"

REM --- 应用上传文件目录（backup-files.bat 镜像源） ---
set "UPLOADS_DIR=D:\IT\AI-Projects\AI-Projects\agent-platform\backend\uploads"

REM --- 异地副本目标（sync-offsite.bat；UNC 路径或已挂载盘符，须先手工跑通一次） ---
set "OFFSITE_TARGET=\\192.168.x.x\agent-platform-backup"

REM --- 告警 webhook（钉钉群机器人，与 P1 Alertmanager 同通道；留空则失败只写事件日志） ---
set "OPS_ALERT_WEBHOOK_URL="
