# 日志系统 · 功能 README

> Phase 3 功能完成收尾时产出。
> 关联：[../../0_推进计划/3_日志系统.md](../../0_推进计划/3_日志系统.md)（规格权威，无 PRD）· [../../docs/plans/日志系统.plan.md](../../docs/plans/日志系统.plan.md) · [../../docs/feature-map/日志系统.feature-map.md](../../docs/feature-map/日志系统.feature-map.md) · [../../docs/user-ops/日志系统用户操作手册.md](../../docs/user-ops/日志系统用户操作手册.md)

## 受众类型
- [ ] A 技术类
- [ ] B 用户类
- [x] **C 两者**：traceId/脱敏/异步透传是纯技术基建；但「审计日志中心」页面是管理员可见的查询界面

---

## 一、用户地图
- **谁会用**：平台管理员（持 `system:audit:read` 权限，默认 admin 角色）。
- **什么场景下用**：① 安全追溯——「谁改了角色权限 / 谁删了知识库 / 谁充了值」；② 故障排查——拿 traceId 在审计行与日志文件间互查；③ 登录风控——按 IP/用户名筛登录失败记录。
- **带来什么效益**：敏感操作从「无据可查」变「1 秒内可查到完整审计行（谁/何时/对象/结果/IP）」；排障从「翻三个文件肉眼对时间」变「grep 一个 traceId 出全链」。
- **谁用不到**：普通用户（页面不可见、API 403）；想看实时日志流的运维（Grafana 三视图属 P2，待运维窗口）。

## 二、技术说明
- **职责**：① traceId 全链路（micrometer-tracing，含异步线程池与 Python sidecar）；② 日志双格式四通道（console 彩色 / app·error·sql JSON 文件）；③ 五类敏感信息事件层打码；④ 敏感操作审计落库 + 管理员查询页。
- **关键接口 / 入口**：`GET /api/audit/logs`（审计查询）；`@AuditLog(module, action)`（新敏感写操作埋点）；`MdcContextTaskDecorator`（新线程池必挂）；`LogMasker`（新敏感类别加正则）。
- **依赖**：micrometer-tracing-bridge-otel（BOM 托管）、logstash-logback-encoder **7.4（钉版，BOM 不管）**、sidecar structlog 24.4.0。
- **部署 / 配置注意**：`LOG_SQL_ENABLED`（默认 OFF，开才产 sql.log）；`TRACING_SAMPLING_PROBABILITY`（默认 1.0，生产可降）；生产须建 `agent_app` 非超管 DB 账号，V78 REVOKE 才生效；日志落盘目录被 `.gitignore logs/` 忽略（前端源码目录已加例外）。
- **排障要点**：日志没 traceId → 查 actuator 依赖与 Filter 顺序；异步日志断链 → 查该池是否挂 CompositeTaskDecorator；审计查不到 → 看后端 WARN「审计落库失败(已丢)」的 droppedCount；sidecar 断链 → 找 `trace_parent_missing=True` 标记。
- **未完成部分**：P2 集中日志栈（VictoriaLogs+Alloy+Grafana 三视图）与 P3 告警（Step 13/14）需服务器运维窗口，可整体降级跳过（grep JSON 文件排障）。

## DoD 勾选状态
- [x] plan 步骤全勾（Step 1-12；13/14 属运维窗口未启动）
- [x] 安全检查清单全过（PII 红线/脱敏/REVOKE/权限）
- [x] 自动化测试绿（后端 1069 仅 2 预存红、前端 297、sidecar 5）
- [x] Feature Map / User-Ops / README 已产出
- [ ] Phase 4 验收（grep traceId 全链、审计 1 秒可查、卡号打码实测）
