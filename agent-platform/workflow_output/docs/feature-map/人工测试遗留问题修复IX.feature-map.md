# 人工测试遗留问题修复IX · Feature Map

> IX-1 思考强度三档（9x）+ IX-2 画布连线保留开关（2x 增补④）。2026-08-31，分支 feat/gsl-port。
> commit：A1 `2e2da33` / A4 `0c6d40c7` / A5 `3d9019f6` / B1 `fb0e3981` / B2+B3 `2152c81b`。IX-3 联网搜索零开发（2026-07-19 已收），只补部署清单（运维手册）。

## IX-1 思考强度

### 代码位置

| 层 | 文件 | 作用 |
|---|---|---|
| 枚举/配置 | `llm/dto/ThinkingLevel.java` | OFF/STANDARD/DEEP 三档枚举 |
| | `llm/config/LlmThinkingProperties.java` | `llm.thinking.*`：预算 2048/8192、预扣系数 2/4 |
| 声明 | `llm/dto/ThinkingSpec.java` | config jsonb `thinking` 节解析（toggle/effort/models 白名单） |
| 透传 | `chat/dto/ChatRequest.java`（@Pattern）→ `chat/service/ChatSessionService.java`（resolveThinkingLevel×2 站点）→ `engine/context/ExecutionContext.java` → `engine/strategy/DefaultChatStrategy.java` → `llm/dto/LlmRequest.java` | 三跳透传，null=零参数现状 |
| Provider | `llm/provider/ClaudeProvider.java`（ANTHROPIC 三档+budget clamp+max_tokens 只抬不降）、`llm/provider/OpenAICompatibleProvider.java`（applyThinkingParam 声明制） | 真正发参数的地方 |
| 组装 | `llm/config/LlmConfig.java` + `llm/LlmGateway.java`（用户级） | 构造时解析一次缓存 |
| 能力下发 | `llm/controller/UserLlmController.java` + `llm/dto/AvailableModelVO.java` | `/models/available` 增 thinkingLevels |
| 计费 | `billing/service/LlmBillingService.java`（holdChat 8 参重载）+ `llm/LlmGateway.java:91/:203` | HOLD 预扣按档位放大 |
| 前端 | `api/llm.ts`/`api/chat.ts`（类型）→ `stores/chat.ts`（全链透传）→ `views/ChatView.vue`（🧠 选择器+档位回落）+ `utils/storage.ts`（CHAT_THINKING_LEVEL） | 用户面 |

### 调用链（大白话）

请求带 `thinkingLevel:"DEEP"` → 会话服务校验白名单塞进上下文 → 策略层转给 LlmRequest → Claude 家：`thinking:{type:enabled,budget_tokens:8192}`+max_tokens 抬到 ≥9216；OpenAI 家先查供应商 config 里有没有 `thinking` 声明——没声明一个参数不发（glm 忽略思考也不误伤）。模型列表接口告诉前端这个模型有哪些档，前端没收到档位就不渲染下拉。发消息同时 HOLD 预扣把预估输出 ×4，答完按实际用量多退少补。

**踩坑批注**：① Anthropic 硬约束 budget≥1024 且 max_tokens>budget，否则 400——clamp+只抬不降；② 10+ 记忆内部 `disableThinking(true)` 调用方靠优先级链（thinkingLevel>disableThinking>无）零改动保命；③ config 每请求解析=白耗 token，构造时解析一次随 provider 缓存。

## IX-2 画布连线保留开关

### 代码位置

| 文件 | 作用 |
|---|---|
| `components/canvas/canvasClipboard.ts` | `crossEdges` 恒收集（恰一端在选中集的非组边）+ `remapCrossEdges` 单侧重映射（悬挂防护/平行边不 dedup/剥会话 class） |
| `utils/canvasPrefs.ts`（新） | singleton ref `keepLinksOnCopy`（localStorage `canvas.keepLinksOnCopy`，默认开，非法回落 true） |
| `components/canvas/CanvasBoard.vue` | 工具条 ⛓ 按钮 + `pasteSubgraph` 粘贴时点判定 |
| `views/CanvasView.vue` | `onCloneNode` 同开关门（关=副本零边） |

### 调用链（大白话）

Ctrl+C 时把「跨出选中集的边」也存进剪贴板（不管开关）→ Ctrl+V 那一刻看 ⛓ 开关：开 → 集内端换新 id、集外端保原 id，副本连回原上下文；关 → 只粘诱导边。创建副本走同一开关。**「恒收集+粘贴时点判定」**= 复制后切开关，按粘贴当下所见生效——所见即所得。

**踩坑批注**：① 复制后外部节点被删 → 粘贴时按存活集丢边，不产 vue-flow 渲染断裂的悬挂边；② 边快照浅拷贝+剥 class，防选中态高亮永久烤进新边（VII Y1 同款）；③ 平行边不去重（Q4 拍板：去重=丢用户结构）；④ 开关默认开=修复VI「连线克隆」现状延续，只有显式关才变。

## IX-3 联网搜索 Tavily 开关制（修复IX+，2026-08-31 增补）

> 用户拍板：配 key + 开开关走 Tavily；未配/key 错/关 → SearXNG Docker。开发 2026-07-19 的联网链路上改路由方式，commit `273cf10b`（后端）/`9f5e0884`（前端）。

### 代码位置

| 文件 | 作用 |
|---|---|
| `system/service/SystemSettingService.java` | 新 `search.tavily.enabled`（默认 false）；**getActiveSearchProvider 改派生**：开关开→"tavily"、否则→"builtin"；旧 active-provider 手选删除 |
| `search/config/SearchConfig.java` → `search/service/WebSearchService.java` | 路由消费派生值；key 错/空的运行时兜底靠既有降级链（tavily 不可用/空/抛 → builtin 重试） |
| `system/controller/SystemSettingController.java` + `WebSearchSettingsVO/UpdateRequest` | +tavilyEnabled；activeProvider 派生回显（派生收在 service，防 @WebMvcTest 切片缺 bean） |
| `components/settings/WebSearchSettingsTab.vue` + `api/system.ts` | 「Tavily 启用」开关 + 「当前路由」派生标签；下拉/serper/bing 输入撤 |

智能对话与画布 chat 都发 `req.webSearch` → 同一 ChatSessionService → **一处改两处生效**。

**踩坑批注**：① controller 别直接注入 @Component 的 SearchConfig——@WebMvcTest 切片没这 bean，context 直接挂（Legacy404Test 实测）；② ClaudeProvider thinking 节 `Map.of` 无序，JSON key 序不稳，锁序断言的测试会随机挂——要定序用 LinkedHashMap。

## 测试锚点

后端 mvn 2706→2712/2712（Chunk A/B 后：新增 ThinkingSpecTest 5、ClaudeProviderTest +4、OpenAICompatibleProviderTest +5、LlmBillingServiceTest +2、DefaultChatStrategyTest +2、SearchConfigTest 3、SystemSettingServiceTest +3）；前端 vitest 988→994/994（canvasClipboard +5、canvasPrefs 4 新、CanvasBoard +6、WebSearchSettingsTab 6 新）、vue-tsc 0 错。
