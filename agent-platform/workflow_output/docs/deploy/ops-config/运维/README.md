# 运维目录说明

> **可执行脚本（.bat）在 `agent-platform/scripts/ops/`**，不在本目录。
> 原因：Windows 计划任务（SYSTEM 账户）解析不了中文路径（实测报「系统找不到指定的路径」），
> 凡是要被计划任务/双击直接执行的 bat 一律放英文目录 `scripts/ops/{backup,deploy,winsw,alertmanager}/`。
> 本目录只放**模板与文档**（复制到服务器后使用的配置模板 yml/xml/ini、看板 JSON+生成器、手册/规程）。

## 本目录内容

| 子目录/文件 | 内容 |
|---|---|
| `prometheus/` | prometheus.yml + alert-rules.yml（部署拷到 E:\IT\ops\prometheus\） |
| `alertmanager/` | alertmanager.yml.example + dingtalk-config.yml.example（模板零密钥） |
| `grafana/` | custom.ini + provisioning（数据源/看板目录） |
| `grafana-dashboards/` | 四看板 JSON + gen_dashboards.py 生成器 |
| `winsw/` | 7 个服务定义 xml 模板 |
| `backup/` | （脚本已迁 scripts/ops/backup/）恢复演练见下方规程 |
| `恢复演练规程.md` | 每季恢复演练步骤 |
| `运维手册.md` | **运维统一入口**：巡检/告警处置/runbook/备份一览 |

## scripts/ops/ 内容（可执行脚本）

| 脚本 | 用途 |
|---|---|
| `backup/backup-pg.bat` | PG 每日备份（计划任务 02:00） |
| `backup/backup-files.bat` | uploads 镜像（计划任务 02:30） |
| `backup/sync-offsite.bat` | 异地周同步（待配 OFFSITE_TARGET） |
| `backup/backup-env.example.bat` | 机密模板；真实 backup-env.bat 本地 gitignore |
| `deploy/deploy.bat` | 一键发布+回滚（服务器上用） |
| `winsw/install-services.bat` | 监控五组件一键注册服务 |
| `alertmanager/配置钉钉告警.bat` | 中文引导填钉钉 webhook |
