# Runtime Sidecar 部署配置说明

> 最后更新：2026-06-05
> 适用范围：计划8 Python LangGraph sidecar 与 Java 后端联调、部署、验收。

---

## 一、服务端口

| 服务 | 默认地址 | 说明 |
|------|----------|------|
| Java 后端 | `http://localhost:8080` | 平台控制面、执行网关、runtime callback 接收端 |
| Python sidecar | `http://localhost:8090` | LangGraph 图执行面 |
| 前端 Vite | `http://localhost:5173` | 本地开发页面 |

---

## 二、Java 后端配置

默认使用 mock runtime，不依赖 sidecar：

```yaml
runtime:
  gateway:
    mode: mock
    sidecar-base-url: http://localhost:8090
    java-callback-base-url: http://localhost:8080
```

切换真实 sidecar：

```powershell
$env:RUNTIME_GATEWAY_MODE="sidecar"
$env:RUNTIME_SIDECAR_BASE_URL="http://localhost:8090"
$env:RUNTIME_JAVA_CALLBACK_BASE_URL="http://localhost:8080"
```

配置含义：

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| `runtime.gateway.mode` | `RUNTIME_GATEWAY_MODE` | `mock` | `mock` 使用 Java mock gateway；`sidecar` 调 Python sidecar |
| `runtime.gateway.sidecar-base-url` | `RUNTIME_SIDECAR_BASE_URL` | `http://localhost:8090` | Java 调 sidecar 的地址 |
| `runtime.gateway.java-callback-base-url` | `RUNTIME_JAVA_CALLBACK_BASE_URL` | `http://localhost:8080` | sidecar 回调 Java 的地址 |

---

## 三、Python sidecar 配置

安装依赖：

```powershell
cd E:\workspace\agent-platform\runtime-sidecar
python -m pip install -r requirements.txt
```

启动服务：

```powershell
python -m uvicorn app.main:app --host 0.0.0.0 --port 8090
```

checkpoint 存储目录：

```powershell
$env:RUNTIME_CHECKPOINT_DIR="E:\runtime-checkpoints"
```

未配置时，sidecar 会使用当前工作目录下的 `.runtime-checkpoints`。

---

## 四、健康检查与 readiness

当前健康检查：

```text
GET /health
```

期望响应：

```json
{
  "status": "UP",
  "service": "runtime-sidecar"
}
```

部署环境可先将 `/health` 作为 readiness 检查。后续如需要区分 liveness/readiness，可新增：

| 路径 | 用途 |
|------|------|
| `/health` | 进程存活 |
| `/ready` | checkpoint 目录可写、callback 地址可达、依赖初始化完成 |

---

## 五、日志、超时与并发

当前 sidecar 使用 uvicorn 标准日志输出，部署时建议由进程管理器或容器平台采集 stdout/stderr。

建议部署参数：

| 项目 | 建议 |
|------|------|
| 日志目录 | 由部署平台采集 stdout/stderr，或重定向到固定目录 |
| Java -> sidecar 超时 | 后续按工作流复杂度配置，短流程建议 30-60 秒 |
| sidecar -> Java callback 超时 | 当前 callback client 默认 30 秒 |
| LLM 调用超时 | 由 Java LLM provider 配置控制 |
| 并发限制 | 当前未做资源级限流，部署环境建议先以单实例低并发验收 |

---

## 六、联调验收命令

后端关键测试：

```powershell
cd E:\workspace\agent-platform\backend
mvn -q "-Dtest=SkillExecutorTest,AgentRouterTest,RuntimeDtoTest,WorkflowDefinitionAssemblerTest,SidecarRuntimeGatewayTest,RuntimeExecutionServiceTest,RuntimeNodeCallbackServiceTest,RuntimeCallbackControllerTest" test
```

sidecar 全量测试：

```powershell
cd E:\workspace\agent-platform\runtime-sidecar
python -m pytest -q
```

前端测试与构建：

```powershell
cd E:\workspace\agent-platform\frontend
npm test -- --run
npm run build
```

---

## 七、核心联调链路

1. 启动 Java 后端，设置 `RUNTIME_GATEWAY_MODE=sidecar`。
2. 启动 Python sidecar，确认 `GET /health` 返回 `UP`。
3. 前端登录管理员账号。
4. 创建或打开包含 `SKILL` / `AGENT_REF` 节点的工作流。
5. 点击运行。
6. 在执行监控页确认：
   - runtime event timeline 正常展示；
   - `SKILL` 节点有 `selectedSkillIds` 和 `stepOutputs`；
   - `AGENT_REF` 节点有 `agentName`、`selectedSkillIds` 和最终输出；
   - callback 失败时记录 `EXECUTION_FAILED`，并带 `failedNodeId`、`errorMessage`、`recoveryCheckpointRef`。

