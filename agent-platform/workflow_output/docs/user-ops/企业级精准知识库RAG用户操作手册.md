# 企业级精准知识库 RAG 用户操作手册

> 本手册随 Phase 3 每一步持续更新。当前已实现 P0、P1 与 P2 Step 1；尚未实现的管理页面不会提前写成可操作功能。

## 管理员：配置 OpenSearch 连接基座

当前步骤是部署配置能力，尚无前端表单。管理员在后端运行环境设置以下变量后重启服务：

1. 设置 `RAG_OPENSEARCH_ENABLED=true`。
2. 设置 `RAG_OPENSEARCH_URL`，例如 `https://opensearch.example.com:9200`。
3. 如集群启用认证，设置 `RAG_OPENSEARCH_USERNAME` 与 `RAG_OPENSEARCH_PASSWORD`。
4. 保持 `RAG_OPENSEARCH_TLS_VERIFICATION=true`；生产环境不要关闭证书校验。
5. 可按需设置 `RAG_OPENSEARCH_CONNECT_TIMEOUT`、`RAG_OPENSEARCH_REQUEST_TIMEOUT`，例如 `3s`、`10s`。
6. 重启后访问平台现有 Actuator 健康检查入口，查看 `openSearchHealthIndicator`：
   - `UNKNOWN / disabled`：功能未启用，不会尝试连接；
   - `UP / up`：集群可连接；
   - `DOWN / down`：连接、认证、TLS 或超时存在问题。

### 常见异常与恢复

- 显示 `disabled`：确认启用变量已进入 Java 进程环境并重启。
- 显示 `down`：检查地址、端口、网络、认证和证书；健康详情不会显示密码。
- 生产环境无域名证书：应先配置可信证书或反向代理，不要长期关闭 TLS 校验。

## 当前限制

- P2 Step 2 已具备版本化物理索引和 read/write alias 基座，但管理页面尚未开放。
- 索引名由系统根据 KB、snapshot、pipeline 生成，管理员不能输入物理索引名，也不应直接在 OpenSearch 中手工切换 Alias。
- 双写、重建和管理控制面将在后续 Step 中依次开放。
