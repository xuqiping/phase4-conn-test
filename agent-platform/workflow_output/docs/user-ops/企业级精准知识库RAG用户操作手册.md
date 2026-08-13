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

## 管理员：理解双写状态

- `RAG_OPENSEARCH_ENABLED=false`：文档继续只写旧 PG 索引链路，适合尚未部署 OpenSearch 的环境。
- 启用后：每个 C2 节点生成一次向量，先写 OpenSearch 检索副本，再完成 PG 索引任务。
- Bulk 中任一节点失败：任务进入既有退避重试，不会把文档误标为全部索引完成。
- 重试使用稳定 node ID，因此不会产生重复检索文档。

## 管理员：对账与权限变化

- 系统以 PG 为权威源，对 OpenSearch 检测缺失、孤儿、正文 Hash 漂移和 ACL 漂移。
- dry-run 只生成“需重建/需删除”清单，不修改数据。
- 权限、版本可见性或删除发生变化时，系统先清除该 KB 的检索副本，再通过幂等任务重建，避免旧权限泄漏。

## 管理员：索引运维页面

1. 进入“知识库”，打开管理员可见的“索引运维”Tab。
2. 选择知识库，点击“刷新状态”查看当前/上一快照及 read/write alias。
3. 输入系统登记的 snapshot ID，先点“对账 / 重建预检”；成功提示明确说明没有修改索引。
4. 确认新快照已建好后点“切换”，在风险弹窗中再次确认。
5. 如新链路异常，点“回滚”，确认后恢复上一已登记快照。

无法切换时检查：是否具备 `knowledge:manage`、snapshot ID 是否符合登记格式、OpenSearch 是否健康、是否勾选二次确认。

## 用户：问题如何被规划

- 带版本、日期、条款编号或引号原文的问题优先走精确检索，不额外调用 Query LLM。
- “比较/差异”会规划为多证据回答；“步骤/流程”会启用邻居与顺序检索；“列出全部”会提高覆盖预算。
- 普通语义问题才允许使用知识库配置中明确选择的 LLM 做分析；没有可用模型时不会静态调用其他模型。

每次检索都会在搜索阶段限制租户、知识库、ACL、ACTIVE 状态和目标版本；权限不足或版本已撤销的内容不会先召回再由页面隐藏。

系统会并行使用精确、关键词、向量和实体通道。某一通道故障时其余通道继续工作；不同通道按排名融合，不会把含义不同的原始分数直接相加。
