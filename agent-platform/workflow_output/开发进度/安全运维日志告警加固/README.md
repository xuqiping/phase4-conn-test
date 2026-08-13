# 安全运维日志告警加固 · README

> 11x 需求落地。规格 `docs/specs/安全运维日志告警加固.md`；计划 `docs/plans/安全运维日志告警加固*.plan.md`；测试方案 `docs/测试方案/安全运维日志告警加固测试方案.md`。

## 用户地图（B 类）
- **谁用**：系统管理员/运维。
- **场景**：攻击与滥用实时可见（风险大盘+钉钉）、高危自动封 IP/锁号、事件处置留痕、阈值热调。
- **效益**：登录暴破/撞库/注入/越权/外带/计费欺诈/Prompt 注入/Token 盗号/特权变更 8 类检测全覆盖；误伤一键关（自动处置总闸）。
- **操作手册**：`docs/user-ops/安全管理用户操作手册.md`。

## 技术说明（A 类）
- **热路径**：SecurityGateFilter（黑名单/全局限流/注入扫描）+ @RateLimit 注解 + BanService 双保险踢下线。降级=放行。
- **冷路径**：业务咽喉 publishEvent → SecurityMonitorWorker（专用池+MDC 透传）→ 9 规则（Redis 窗口计数，故障不命中）→ SecurityEventService（5min 去重落库）→ AutoResponder（severity 矩阵）+ AlertRouter（钉钉）。
- **表**：V104（security_events 半年 / ip_blacklist / login_attempts 30 天 / users+ban_reason,locked_until / 3 权限）。
- **日志**：四通道 180 天（网安法 21 条）；security 独立 JSON 通道。
- **代码导览**：`docs/feature-map/安全加固.feature-map.md`。

## 进度文件
开发进度1（P1 地基）/ 2（P2 拦截层）/ 3（P3+P4）。

## 部署必做
1. ip2region_v4.xdb 手工放 `backend/src/main/resources/`（~11MB，GitHub lionsoul2014/ip2region）。
2. 钉钉群建机器人 → 「安全规则」页配 webhook URL + 加签密钥。
3. D 盘日志预留 ≥25GB。
4. 反代部署时确认 `security.ip.trust_proxy`（可信代理白名单）。
5. V104 随 Flyway 启动自动执行（3 权限自动授 admin）。
