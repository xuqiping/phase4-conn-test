# -*- coding: utf-8 -*-
# 运维系统 Step 9：四个预置看板 JSON 生成器（全中文）
# 输出到仓库 项目工程文档/运维/grafana-dashboards/，部署时拷到 D:\IT\ops\grafana\dashboards\
import json, os, sys

DS = {"type": "prometheus", "uid": "prometheus"}
OUT = sys.argv[1]

_id = [0]
def nid():
    _id[0] += 1
    return _id[0]

def tgt(expr, legend="", ref=""):
    return {"datasource": DS, "expr": expr, "legendFormat": legend,
            "refId": ref or chr(65 + nid() % 26) + str(nid()), "range": True, "instant": False}

def ts(title, exprs, x, y, w=12, h=8, unit="short", desc="", min_v=None, max_v=None, thresholds=None):
    field = {"defaults": {"unit": unit, "custom": {"drawStyle": "line", "fillOpacity": 8, "showPoints": "never",
             "lineWidth": 1, "spanNulls": False}, "thresholds": {"mode": "absolute", "steps":
             [{"color": "green", "value": None}] + ([{"color": "red", "value": t} for t in (thresholds or [])])},
             "overrides": []}, "overrides": []}
    if min_v is not None: field["defaults"]["min"] = min_v
    if max_v is not None: field["defaults"]["max"] = max_v
    return {"id": nid(), "type": "timeseries", "title": title, "description": desc,
            "gridPos": {"x": x, "y": y, "w": w, "h": h}, "datasource": DS,
            "targets": [tgt(e, l) for e, l in exprs], "fieldConfig": field, "options": {
            "legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
            "tooltip": {"mode": "multi", "sort": "desc"}}}

def stat(title, exprs, x, y, w=6, h=4, unit="short", desc="", mappings=None, thresholds=None):
    defaults = {"unit": unit, "thresholds": {"mode": "absolute", "steps":
                [{"color": "green", "value": None}] + ([{"color": "red", "value": t} for t in (thresholds or [])])}}
    if mappings: defaults["mappings"] = mappings
    return {"id": nid(), "type": "stat", "title": title, "description": desc,
            "gridPos": {"x": x, "y": y, "w": w, "h": h}, "datasource": DS,
            "targets": [dict(tgt(e, l), instant=True, range=False) for e, l in exprs],
            "fieldConfig": {"defaults": defaults, "overrides": []},
            "options": {"colorMode": "value", "graphMode": "area", "justifyMode": "auto",
                        "orientation": "auto", "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                        "textMode": "auto"}}

def dash(uid, title, desc, panels, tags=None):
    return {"uid": uid, "title": title, "description": desc, "tags": tags or ["运维系统"],
            "timezone": "browser", "schemaVersion": 39, "version": 1, "editable": True,
            "refresh": "30s", "time": {"from": "now-6h", "to": "now"},
            "annotations": {"list": []}, "links": [], "panels": panels,
            "templating": {"list": []}}

UP_MAP = [{"type": "value", "options": {"0": {"text": "离线", "color": "red"}, "1": {"text": "在线", "color": "green"}}}]

# ============ 1. 系统总览 ============
system = dash("ops-system-overview", "系统总览", "主机资源 + JVM + Tomcat/连接池 一览（30s 自动刷新）", [
    stat("组件在线状态", [("up", "{{job}}")], 0, 0, 6, 4, "none", "四个抓取目标在线情况；sidecar 进程未启动时显示离线属预期", UP_MAP),
    stat("主机 CPU 使用率", [("100 * (1 - avg(rate(windows_cpu_time_total{mode=\"idle\"}[2m])))", "")], 6, 0, 6, 4, "percent", "", None, [85]),
    stat("主机内存使用率", [("100 * (1 - windows_memory_available_bytes / windows_memory_physical_total_bytes)", "")], 12, 0, 6, 4, "percent", "", None, [90]),
    stat("D 盘剩余空间", [("100 * windows_logical_disk_free_bytes{volume=\"D:\"} / windows_logical_disk_size_bytes{volume=\"D:\"}", "")], 18, 0, 6, 4, "percent", "低于 15% 触发磁盘告警（与健康检查同阈值）", None, []),
    ts("主机资源趋势", [
        ("100 * (1 - avg(rate(windows_cpu_time_total{mode=\"idle\"}[2m])))", "CPU 使用率"),
        ("100 * (1 - windows_memory_available_bytes / windows_memory_physical_total_bytes)", "内存使用率"),
        ("100 * process_cpu_usage{job=\"backend\"}", "后端进程 CPU"),
    ], 0, 4, 12, 8, "percent", min_v=0, max_v=100),
    ts("磁盘剩余空间趋势", [
        ("100 * windows_logical_disk_free_bytes / windows_logical_disk_size_bytes", "磁盘 {{volume}}"),
    ], 12, 4, 12, 8, "percent", "剩余空间占比，低于 15% 需清理", 0, 100),
    ts("JVM 堆内存", [
        ("sum(jvm_memory_used_bytes{job=\"backend\", area=\"heap\"})", "堆已用"),
        ("sum(jvm_memory_max_bytes{job=\"backend\", area=\"heap\"})", "堆上限"),
    ], 0, 12, 12, 8, "bytes", "已用持续贴近上限说明堆吃紧（告警线：使用率>85%/10min）"),
    ts("GC 暂停（次/秒）", [
        ("sum(rate(jvm_gc_pause_seconds_count{job=\"backend\"}[1m]))", "GC 次数/秒"),
    ], 12, 12, 12, 8, "ops", "次数突增 + 堆高位 = 内存压力信号"),
    ts("Tomcat 活跃会话", [
        ("tomcat_sessions_active_current_sessions{job=\"backend\"}", "当前活跃会话"),
    ], 0, 20, 8, 8, "none"),
    ts("数据库连接池", [
        ("hikaricp_connections_active{job=\"backend\"}", "活跃连接"),
        ("hikaricp_connections_pending{job=\"backend\"}", "等待连接线程"),
    ], 8, 20, 8, 8, "none", "等待>0 说明连接池打满"),
    ts("应用线程池", [
        ("sum by (name)(executor_active_threads{job=\"backend\"})", "活跃线程 {{name}}"),
    ], 16, 20, 8, 8, "none", "各执行器活跃线程数（含记忆池/审计池/索引池）"),
])

# ============ 2. 接口质量 ============
api = dash("ops-api-quality", "接口质量", "QPS / P95 / P99 / 4xx5xx 按接口（uri 模板归一，不含 actuator）", [
    ts("QPS（按接口）", [
        ("sum by (uri)(rate(http_server_requests_seconds_count{job=\"backend\", uri!~\"/actuator.*\"}[1m]))", "{{uri}}"),
    ], 0, 0, 24, 8, "reqps"),
    ts("P95 延迟（按接口）", [
        ("histogram_quantile(0.95, sum by (uri, le)(rate(http_server_requests_seconds_bucket{job=\"backend\", uri!~\"/actuator.*\"}[5m])))", "{{uri}}"),
    ], 0, 8, 12, 8, "s"),
    ts("P99 延迟（按接口）", [
        ("histogram_quantile(0.99, sum by (uri, le)(rate(http_server_requests_seconds_bucket{job=\"backend\", uri!~\"/actuator.*\"}[5m])))", "{{uri}}"),
    ], 12, 8, 12, 8, "s"),
    ts("4xx 速率（按接口）", [
        ("sum by (uri)(rate(http_server_requests_seconds_count{job=\"backend\", status=~\"4..\"}[1m]))", "{{uri}}"),
    ], 0, 16, 12, 8, "reqps", "含 401/403/404 等；登录类 401 高峰看安全态势看板"),
    ts("5xx 速率（按接口）", [
        ("sum by (uri)(rate(http_server_requests_seconds_count{job=\"backend\", status=~\"5..\"}[1m]))", "{{uri}}"),
    ], 12, 16, 12, 8, "reqps", "服务端错误，告警线：占比>5%/5min"),
    ts("5xx 错误率", [
        ("100 * sum(rate(http_server_requests_seconds_count{job=\"backend\", status=~\"5..\"}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count{job=\"backend\"}[5m])), 0.001)", "5xx 占比"),
    ], 0, 24, 24, 6, "percent", "超过 5% 持续 5 分钟触发告警（Step 10）", 0, None, [5]),
])

# ============ 3. AI 核心链路 ============
ai = dash("ops-ai-core", "AI 核心链路", "LLM 调用/Token/延迟 + 工作流终态 + 索引队列 + 记忆管线（无数据=该链路尚未被调用，非故障）", [
    ts("LLM 调用速率（提供商/模型）", [
        ("sum by (provider, model)(rate(llm_calls_total[5m]))", "{{provider}} / {{model}}"),
    ], 0, 0, 12, 8, "reqps"),
    ts("LLM 调用结果", [
        ("sum(rate(llm_calls_total{result=\"success\"}[5m]))", "成功"),
        ("sum(rate(llm_calls_total{result=\"fail\"}[5m]))", "失败"),
        ("sum(rate(llm_calls_total{result=\"cancel\"}[5m]))", "取消"),
    ], 12, 0, 12, 8, "reqps", "三终态互斥正好一次；单 provider 失败率>30%/5min 触发告警"),
    ts("LLM 成功率", [
        ("100 * sum(rate(llm_calls_total{result=\"success\"}[5m])) / clamp_min(sum(rate(llm_calls_total[5m])), 0.001)", "成功率"),
    ], 0, 8, 12, 8, "percent", min_v=0, max_v=100),
    ts("LLM 平均耗时（提供商/模型）", [
        ("sum by (provider, model)(rate(llm_latency_seconds_sum[5m])) / clamp_min(sum by (provider, model)(rate(llm_latency_seconds_count[5m])), 0.001)", "{{provider}} / {{model}}"),
    ], 12, 8, 12, 8, "s"),
    ts("Token 消耗速率", [
        ("sum by (provider, model)(rate(llm_tokens_total{direction=\"in\"}[5m]))", "{{provider}}/{{model}} 输入"),
        ("sum by (provider, model)(rate(llm_tokens_total{direction=\"out\"}[5m]))", "{{provider}}/{{model}} 输出"),
    ], 0, 16, 12, 8, "ops"),
    ts("工作流执行结果（后端编排）", [
        ("sum(rate(workflow_executions_total{status=\"SUCCESS\"}[5m]))", "成功"),
        ("sum(rate(workflow_executions_total{status=\"FAILED\"}[5m]))", "失败"),
    ], 12, 16, 12, 8, "reqps"),
    ts("sidecar 图执行结果", [
        ("sum by (result)(rate(sidecar_graph_executions_total[5m]))", "{{result}}"),
    ], 0, 24, 12, 8, "reqps", "result=SUCCESS/FAILED/WAITING_*/CANCEL"),
    ts("sidecar 图执行 P95 耗时", [
        ("histogram_quantile(0.95, sum by (le)(rate(sidecar_graph_execution_duration_seconds_bucket[5m])))", "P95"),
    ], 12, 24, 12, 8, "s"),
    ts("节点失败（按类型）", [
        ("sum by (node_type)(rate(sidecar_node_failures_total[5m]))", "{{node_type}}"),
    ], 0, 32, 12, 8, "reqps"),
    ts("索引队列深度", [
        ("knowledge_index_queue_depth{job=\"backend\"}", "待索引任务数"),
    ], 12, 32, 12, 8, "none", "积压>1000 持续 10min 触发告警；大批量导入期看趋势不看单点"),
    ts("记忆管线平均耗时", [
        ("sum(rate(memory_pipeline_duration_seconds_sum[5m])) / clamp_min(sum(rate(memory_pipeline_duration_seconds_count[5m])), 0.001)", "平均耗时"),
    ], 0, 40, 12, 8, "s"),
    ts("记忆处理事故（每小时）", [
        ("sum(increase(memory_incidents_total[1h]))", "事故数"),
    ], 12, 40, 12, 8, "none", "线程池拒绝/LLM 失败等记忆异步处理事故"),
    ts("媒体任务提交速率（视频/生图/剪辑）", [
        ("sum by (kind)(rate(media_task_submitted_total[5m]))", "{{kind}}"),
    ], 0, 48, 12, 8, "reqps"),
    ts("媒体任务终态结果", [
        ("sum by (kind)(rate(media_task_terminal_total{result=\"success\"}[5m]))", "{{kind}} 成功"),
        ("sum by (kind)(rate(media_task_terminal_total{result=\"fail\"}[5m]))", "{{kind}} 失败"),
    ], 12, 48, 12, 8, "reqps", "终态正好一次/次（重试按次计）；失败率>50%/15min 触发告警"),
    ts("媒体任务端到端平均耗时（含排队）", [
        ("sum by (kind)(rate(media_task_duration_seconds_sum[5m])) / clamp_min(sum by (kind)(rate(media_task_duration_seconds_count[5m])), 0.001)", "{{kind}}"),
    ], 0, 56, 24, 8, "s", "创建→终态含排队等待；生图同步路径≈纯生成耗时，视频含轮询"),
])

# ============ 4. 安全态势 ============
sec = dash("ops-security", "安全态势", "登录失败趋势 / 限流锁定 / 未授权访问 / 权限缺口 / 错误日志", [
    ts("登录趋势", [
        ("sum(rate(auth_login_total{result=\"success\"}[5m]))", "登录成功"),
        ("sum(rate(auth_login_total{result=\"fail\"}[5m]))", "登录失败"),
    ], 0, 0, 24, 8, "reqps", "失败突增=爆破信号；单 IP 失败>10 次/min 触发告警（Step 10）"),
    ts("登录失败率", [
        ("100 * sum(rate(auth_login_total{result=\"fail\"}[5m])) / clamp_min(sum(rate(auth_login_total[5m])), 0.001)", "失败率"),
    ], 0, 8, 12, 8, "percent", min_v=0, max_v=100),
    ts("账号锁定 / 注册限流", [
        ("sum by (scope)(rate(auth_login_locked_total[5m]))", "锁定 {{scope}}"),
        ("sum(rate(auth_register_rate_limited_total[5m]))", "注册限流命中"),
    ], 12, 8, 12, 8, "reqps", "scope=account(5次锁15min)/ip(20次/h封)"),
    ts("未授权访问（401/403 按接口）", [
        ("sum by (uri)(rate(http_server_requests_seconds_count{job=\"backend\", status=~\"401|403\"}[5m]))", "{{uri}}"),
    ], 0, 16, 12, 8, "reqps"),
    stat("未加权限注解的端点数", [("security_endpoints_unguarded{job=\"backend\"}", "")], 12, 16, 12, 8, "none", "启动扫描结果，必须恒为 0；>0 说明有新端点漏配 @RequirePermission", None, [1]),
    ts("应用错误/警告日志速率", [
        ("sum by (level)(rate(logback_events_total{job=\"backend\", level=~\"error|warn\"}[5m]))", "{{level}}"),
    ], 0, 24, 24, 8, "ops", "error 持续非零需排查；审计明细在系统内「日志中心」页查询"),
])

os.makedirs(OUT, exist_ok=True)
for name, d in [("system-overview", system), ("api-quality", api), ("ai-core", ai), ("security", sec)]:
    p = os.path.join(OUT, name + ".json")
    with open(p, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
    print("written", p, len(d["panels"]), "panels")
