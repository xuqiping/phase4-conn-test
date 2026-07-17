# 运行时 Bug 核查

> 记录线上（云服务器部署）运行时暴露的问题。每条含：现象、根因（代码级证据）、影响、验证方法、修复方案、状态。

---

## RB-001 — 发消息后记忆为空 + 轮询接口 15s 超时风暴（死亡螺旋）

**发现日期**：2026-06-30
**环境**：云服务器部署（`117.72.25.74`），后端 Spring Boot 单实例
**严重级别**：P0（服务可用性致命——单次网络抖动即可拖垮整个 JVM）
**状态**：⏳ 待修复（根因已定位，待上云验证 + 改 P0 项）

### 现象

1. chat 发送消息后，顶部「记忆记录中…」状态条转完停转，但记忆面板**始终为空**（user_memories 未落任何行）。
2. 过了很长时间（约一个 access token TTL = 15 分钟）后，开始**持续报错**：
   - `GET /api/chat/memories/status` → `timeout of 15000ms exceeded`
   - `GET /api/chat/memories/incident` → `timeout of 15000ms exceeded`
   - 两个轮询请求每 3s 一次，不断重复，不停止。
3. 后端日志刷屏（DEBUG 级 security filter）：
   ```
   [io-8080-exec-85] Securing GET /api/chat/memories/incident
   [io-8080-exec-88] AnonymousAuthenticationFilter Set SecurityContextHolder to anonymous
   [io-8080-exec-90] Securing GET /api/chat/memories/status
   ...（85/87/88/89/90/91/92/93/96 多线程同一毫秒窗口涌入）
   ```
   关键：请求**全部匿名**（无有效 JWT），且多个 Tomcat 线程同一时刻在处理这两个 URL = 请求积压后一次性拿到线程。

### 根因（三个，代码级证据已确认）

#### 根因 ① — LLM/WebClient 调用无超时，线程永久挂死（元凶）

[OpenAICompatibleProvider.java:31](../../../backend/src/main/java/com/superprogrammer/llm/provider/OpenAICompatibleProvider.java#L31) 构造 `WebClient.builder()` **未设任何 timeout**：

```java
this.webClient = WebClient.builder()
        .baseUrl(normalized)
        .defaultHeader("Authorization", "Bearer " + apiKey)
        .defaultHeader("Content-Type", "application/json")
        .build();   // ← 无 responseTimeout，底层 HttpClient 无 connect/response timeout
```

- chat（[L48-54](../../../backend/src/main/java/com/superprogrammer/llm/provider/OpenAICompatibleProvider.java#L48-L54)）与 embed（[L98-104](../../../backend/src/main/java/com/superprogrammer/llm/provider/OpenAICompatibleProvider.java#L98-L104)）均 `.bodyToMono(String.class).block()`，**无 Duration 限制 = 无限阻塞**。
- ClaudeProvider 同构问题（`WebClient.builder()` 无 timeout）。
- 后果：云服务器到 `ark.cn-beijing.volces.com`（doubao）的连接一旦 stall / 慢 / DNS 抖动 / egress 防火墙丢包，调用线程**永不释放**。本地直连快不暴露，上云必犯。

#### 根因 ② — memoryTaskExecutor 用 CallerRunsPolicy，慢任务回灌 servlet 线程

[MemoryTaskExecutorConfig.java:25](../../../backend/src/main/java/com/superprogrammer/chat/config/MemoryTaskExecutorConfig.java#L25)：

```java
executor.setCorePoolSize(4);
executor.setMaxPoolSize(8);
executor.setQueueCapacity(100);
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());  // ← 致命
```

- 记忆异步处理（`processMemory`）经 `memoryTaskExecutor.execute(...)` 提交（[ChatSessionService.java:277/472/528](../../../backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java#L277)）。
- processMemory 内含：extract（1 次 LLM）→ 每条 fact 并行 embed（N 次 LLM）→ judge（#blocks 次 LLM）→ 落库。
- 当 8 线程 + 100 队列全满（云上 LLM 慢 → 任务堆积），`CallerRunsPolicy` 让**调用方 servlet 线程亲自同步执行 processMemory** → Tomcat 线程被钉死在整段 LLM 调用上。
- Tomcat 默认线程池（200）被逐步吃光。

#### 根因 ③ — extractFacts 静默吞异常，记忆为空不可见

[MemoryService.java:126-135](../../../backend/src/main/java/com/superprogrammer/chat/service/MemoryService.java#L126-L135)：

```java
public List<ExtractedFact> extractFacts(...) {
    try {
        ...
        return judge.extract(userMessage, assistantResponse, existingKeys);
    } catch (Exception e) {
        log.warn("记忆抽取失败: {}", e.getMessage(), e);
        return List.of();   // ← 失败当空，processMemory 写 0 条，用户无感
    }
}
```

- LLM extract 调用因根因①挂死/超时失败 → catch → 返回空 list → processMemory 无 fact 可写 → `finally` 把 inflightMemoryTasks 计数 -1 → **状态条停转**，但记忆面板空。
- 真错误仅 warn 日志，前端无 incident 弹窗（incident 机制只在 applyClean 写库失败时触发，extract 阶段失败不走它）。

### 死亡螺旋演进（精确对应现象）

```
发消息
 └ async processMemory 提交 memoryTaskExecutor
    └ extract/embed/judge 调 LLM → 云上连接慢/stall → .block() 挂住（根因①）
       ├ 记忆条：finally 计数归零 → 停转 ✓（extract 已吞异常 → 空面板 ✓ 根因③）
       └ mem-task 线程被钉死 → 池(8)+队列(100)逐渐填满
          └ 满后 CallerRunsPolicy → servlet 线程亲自跑 → Tomcat 池耗损 ✓（根因②）
             └ ~15 分钟后 access token 过期（JWT 15min TTL）
                └ 轮询带过期 token → 后端匿名 ✓（日志 AnonymousAuthenticationFilter 吻合）
                   └ 但 Tomcat 线程已被挂死 LLM 调用吃光
                      └ 轮询请求在 connector 排队等不到线程
                         └ axios 15s 先超时（返回的不是 401，是 timeout）
                            └ request.ts:72 只认 401 才跳登录 → 超时不触发
                               └ 不跳登录 → 每 3s 继续轮询 → 风暴 → 死亡螺旋 ✓
```

**辅助证据**：
- 前端 [request.ts:30](../../../frontend/src/api/request.ts#L30) `timeout: 15000`（吻合 15s）。
- 前端 [request.ts:43-46](../../../frontend/src/api/request.ts#L43-L46)：token 取不到就不带 Authorization → 后端匿名。
- 前端 [request.ts:72-75](../../../frontend/src/api/request.ts#L72-L75)：401 → `redirectToLogin`；注释吹「自动刷新 token」但**代码无 refresh 调用**，直接清 storage 跳登录。超时（无 response.status）走「其他错误」分支，不跳登录 → 风暴不停止。
- 后端 [SecurityConfig.java:50](../../../backend/src/main/java/com/superprogrammer/auth/security/SecurityConfig.java#L50) `/api/chat/memories/**` 走 `anyRequest().authenticated()`，匿名**本应秒返 401**——它却 15s 超时，反证线程被耗尽、请求在 connector 排队（而非端点逻辑慢）。
- `incident()` 端点不碰 DB（只 `ConcurrentMap.remove`），它都超时 → 进一步排除 DB 池问题，坐实 **Tomcat 线程级饥饿**。

### 为什么本地不犯、云上必犯

本地开发机直连 volces 快，`.block()` 秒回，线程不积累。云服务器到 `ark.cn-beijing.volces.com` 的网络（DNS / egress 防火墙 / 区域路由 / 运营商抖动）任何一次 stall，无超时的 `.block()` 直接把线程钉死。**无超时 = 单次网络抖动即可击穿整个 JVM 可用性**。

### 验证方法（上云确认）

SSH 到云服务器，执行：

```bash
# 1. 确认 LLM/embed 挂（最关键）—— 应大量命中
grep -E "LLM调用失败|embedding 调用失败|记忆抽取失败|anchor embed 失败|classify 失败" runtime.out | tail -50

# 2. 看 Tomcat 线程号是否逼近 200（io-8080-exec-N 的 N）
grep -oE "io-8080-exec-[0-9]+" runtime.out | sort -u | tail

# 3. 从云服务器实测到 LLM 的连通性与延迟
curl -w "\nDNS=%{time_namelookup}s CONNECT=%{time_connect}s TOTAL=%{time_total}s\n" \
     -o /dev/null -s --max-time 10 \
     https://ark.cn-beijing.volces.com/api/v3/chat/completions

# 4. 端口/DNS 基础连通
curl -v --max-time 5 https://ark.cn-beijing.volces.com
```

判定：
- 命中「LLM调用失败/embedding 调用失败/记忆抽取失败」→ 根因①③坐实。
- `io-8080-exec-N` 的 N 接近 200 → 根因②线程耗尽坐实。
- curl 卡住/延迟极高 → 云→LLM 网络问题坐实。

### 修复方案

| 优先级 | 改动 | 治 | 位置 |
|---|---|---|---|
| **P0** | WebClient 加超时：底层 `reactor.netty.http.client.HttpClient` `.responseTimeout(Duration.ofSeconds(30))` + `.option(CONNECT_TIMEOUT_MILLIS, 10000)`；`.block(Duration)` 限时 | 根因①——LLM 不再永久挂线程 | OpenAICompatibleProvider / ClaudeProvider |
| **P0** | memoryTaskExecutor 拒绝策略 `CallerRunsPolicy` → `AbortPolicy`（捕获 RejectedExecutionException 记 incident）或 `DiscardOldestPolicy`，**绝不回退 servlet 线程** | 根因②——记忆异步任务永不阻塞主链 | MemoryTaskExecutorConfig |
| **P1** | extractFacts 失败别静默：catch 内 `memoryIncidents.put(userId, "记忆抽取失败："+msg)` 写 incident，前端轮询弹窗可见 | 根因③——空记忆可见化 | MemoryService.extractFacts |
| **P1** | request.ts 把「连续超时」也纳入失效判定（如连续 N 次 timeout/网络错 → 视同登出），或单独提示「网络异常」而非默认业务错误刷屏 | 死亡螺旋——超时也能跳出 | frontend/src/api/request.ts |
| **P2** | LlmGateway 失败重试有界 + 熔断（Resilience4j 或简易计数熔断），避免雪崩 | 韧性 | llm 模块 |

### 关联

- 速查表 [09-个人记忆与冲突解决.md](../速查表/09-个人记忆与冲突解决.md) 记忆异步链路（ASYNC fire-and-forget + 前端 3s 轮询 status/incident）。
- 记忆 `reference_mybatis_script_halfvec_escape`（V33 生产回归范式：mock 单测全过、真 PG/真部署才暴露）。

---
