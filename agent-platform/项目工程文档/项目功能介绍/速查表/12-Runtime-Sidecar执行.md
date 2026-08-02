# 12 - Runtime Sidecar 执行

## 功能简介
独立 Python FastAPI 服务（LangGraph 引擎），接收后端下发的工作流定义，编译为 LangGraph 图、拓扑排序执行节点、SSE 流式回传事件、支持检查点(checkpoint)断点恢复。节点执行通过回调回后端获取真实结果。

## Sidecar (runtime-sidecar/app/)
- 入口：[main.py](../../runtime-sidecar/app/main.py) — FastAPI，`GET /health`、`POST /api/runtime/executions`（流式 SSE / 非流式）
- 执行器：[runtime_executor.py](../../runtime-sidecar/app/runtime_executor.py) — `iter_events`/`build_events`，事件流编排
- 图编译：[graph_compiler.py](../../runtime-sidecar/app/graph_compiler.py) — 拓扑排序、StateGraph、条件分支、JOIN 合并
- 节点解析：[node_runtime.py](../../runtime-sidecar/app/node_runtime.py) — `resolve_source` 识别 AGENT_REF/WORKFLOW_REF/SKILL/RETRIEVAL 来源
- 回调客户端：[callback_client.py](../../runtime-sidecar/app/callback_client.py) — 回调后端 `POST /api/runtime/callbacks/nodes/execute`
- 检查点：[checkpoint_store.py](../../runtime-sidecar/app/checkpoint_store.py) — 持久化执行状态
- 模型：[models.py](../../runtime-sidecar/app/models.py) — RuntimeNode/RuntimeEdge/WorkflowDefinition/ExecutionRequest/ExecutionEvent/ExecutionResult
- 测试：`runtime-sidecar/tests/`

## 后端 (backend) — `runtime` 包
- 配置：[RuntimeGatewayProperties.java](../../backend/src/main/java/com/superprogrammer/runtime/config/RuntimeGatewayProperties.java) — Sidecar 地址/超时
- 网关接口：[RuntimeGateway.java](../../backend/src/main/java/com/superprogrammer/runtime/service/RuntimeGateway.java)
  - 实现：[SidecarRuntimeGateway.java](../../backend/src/main/java/com/superprogrammer/runtime/service/SidecarRuntimeGateway.java)（真实 Sidecar）、[MockRuntimeGateway.java](../../backend/src/main/java/com/superprogrammer/runtime/service/MockRuntimeGateway.java)（本地 mock）
- 编排：[RuntimeExecutionService.java](../../backend/src/main/java/com/superprogrammer/runtime/service/RuntimeExecutionService.java)
- 回调：
  - [RuntimeCallbackController.java](../../backend/src/main/java/com/superprogrammer/runtime/controller/RuntimeCallbackController.java) — `POST /api/runtime/callbacks/nodes/execute`
  - [RuntimeNodeCallbackService.java](../../backend/src/main/java/com/superprogrammer/runtime/service/RuntimeNodeCallbackService.java) — 处理节点回调（调用 Agent/Skill/Retrieval）
- 组装：[WorkflowDefinitionAssembler.java](../../backend/src/main/java/com/superprogrammer/runtime/service/WorkflowDefinitionAssembler.java) — 把 DB 工作流转成下发定义
- DTO/模型：`runtime/dto/` ExecutionRequest、ExecutionResult、ExecutionEvent、RuntimeNode、RuntimeEdge、RuntimeNodeType、WorkflowDefinition、AgentDefinition、RuntimeNodeCallbackRequest/Response

## 前端 (frontend)
无直接调用（经后端转发，结果走对话 SSE / 执行监控）。

## 部署
详见 [项目相关配置说明/Runtime Sidecar部署配置说明](../项目相关配置说明/Runtime%20Sidecar部署配置说明.md)、[LangGraph+Agent+工作流运行说明](../LangGraph+Agent+工作流运行说明.md)

## 数据表
`executions`、`execution_logs`（见 [13-执行监控](13-执行监控.md)）
