# 联网搜索 · 功能 README

> 受众：B 类（用户 + 技术）。用户地图在前，技术说明在后。
> 双引擎手动开关方案，仅 CHAT 模式。实现见 [plan.md](../../docs/plans/联网搜索.plan.md)。

## 一、用户地图（这是什么 / 怎么用）

### 解决什么问题
LLM 训练有截止日期，问"今天日期""最新版 X"这类**时效问题**会答错或编造。
联网搜索让 AI 在回答前**先上网查**，拿到真实网页结果当依据，并标出引用来源 `[n]`，点击可跳原文。

### 怎么开
聊天输入框旁有 `🌐 联网：开/关` 下拉（默认关）。开 → 这条消息及之后该会话都联网。
关 → 走纯模型作答，零行为变化。

### 开了之后看到什么
- AI 回答里出现 `[1][2]` 引用编号；
- 回答下方 `📎 引用来源` 区，联网来源渲染为 `🌐 标题` 蓝色外链，点击新窗打开；
- 没查到内容时，AI 仍照常作答（不会卡住或报错）。

### 两个引擎（用户视角）
| 引擎 | 谁用 | 区别 |
|---|---|---|
| 外部（Tavily/Serper/Bing）| 后台配了对应 key | 快、质量高 |
| 自建 SearXNG | 没配任何 key 时兜底 | 自己部署的搜索聚合器，免费 |

用户不用关心走哪个——**没 key 自动降级自建，自建也挂就纯聊天**，永远不崩。

### 后台运维（管理员）
设置 → 联网搜索 tab：
- 总开关（出问题可一键关停不发版）
- 默认 provider 下拉
- 默认结果数（top-N 1~10）/ 单次超时
- 三家外部 key 输入（AES 加密存，不回显明文）
- 「测试连通」按钮：实时调一次搜 "test" 看命中数 + 各 provider 可用性

---

## 二、技术说明（怎么实现的）

### 模块结构（`com.superprogrammer.search`）
```
search/
├── dto/            SearchResult / SearchOptions（@Value 不可变 VO）
├── provider/       WebSearchProvider 接口 + 4 实现
│   ├── TavilyProvider / SerperProvider / BingProvider（外部，WebClient+AES key）
│   └── BuiltInSearchProvider（SearXNG 元搜索 + Jsoup 抽正文）
├── client/         SearxngClient（SearXNG JSON API + 直抓正文 + SSRF 校验）
├── util/           SanitizeUtil（sanitizeText + assertPublicUrl）/ ContentExtractor（自实现 Readability）
├── config/         SearchConfig（读 system_settings 默认值 → 组装 SearchOptions）
└── service/        WebSearchService（路由 + 降级链 + 审计日志）
```

### 关键设计
- **可插拔 provider**：学 `LlmProviderInterface` 范式，新增 provider 加 `@Component` 即可，service 按 `getName()` 建 map 路由。
- **降级链**（`WebSearchService`）：总开关关→空；query sanitize+截断500；active 不可用→builtin；外部空→builtin；全空→空（触发零结果分支）。外部重试 1，builtin 不重试。
- **provider 契约**：`search()` 失败返空**不抛**，异常类型不打架；service 把"空结果"当"可能失败"触发 builtin 兜底。
- **注入 CHAT**（`ChatSessionService.resolveWebSearch`）：开关门控 session>request>全局 search.enabled；零结果注入"未检索到"提示仍调 LLM；非空注 evidence system context（编号[n]顺延 KB 之后避免撞号）+ 收集 web citation。
- **CITATION 复用**：web citation 复用 `StreamEvent.CITATION`，`CitationVO` 加 `url`+`snippet` 字段；前端按 url 有无区分外链 vs KB 引用。前端**累积合并**多帧 CITATION（KB + web 不互盖）。
- **安全**：query 去 control char + 截断 500；抓正文 SSRF 校验（拒私有/环回/链路本地段，解析所有 A/AAAA 防绕过）；正文 sanitize 防 prompt 注入 + 不可信内容分隔符包裹；key AES 加密不回显。

### 数据库
- **V44**：`chat_sessions` 加 `web_search_enabled BOOLEAN`（会话级开关，null=继承默认关）。
- 无独立搜索结果表（不落库，无 N+1）。

### 配置（system_settings `search.*`）
| key | 默认 | 说明 |
|---|---|---|
| search.enabled | false | 总开关 |
| search.active-provider | builtin | 当前生效 provider（白名单） |
| search.max-results | 5 | top-N（1~10） |
| search.timeout-ms | 10000 | 单次整体超时 |
| search.tavily/serper/bing.api-key | - | AES 加密 |

`search.searxng.base-url` 走 `@Value`（application.properties，部署期配置，**不在 admin 页**——SearXNG URL 是 Docker 部署地址，非运行时变更项）。

### 运维埋点
- 每次搜索一条 INFO 审计：`traceId / query(脱敏80) / provider(含 fallback 标记) / 结果数 / 耗时ms`；
- 降级 WARN、重试 DEBUG；
- 总开关 `search.enabled` 可关停不发版。

### 人工依赖（部署）
- [ ] SearXNG Docker 部署 + `formats: [json]` + base_url 写 `search.searxng.base-url`
- [ ] Tavily key 申请（免费 1000 次/月）/ Serper / Bing（可选）
- [ ] jsoup 1.18.3 升级时复核 CVE

### 与 plan 偏差
- **SearXNG base-url 保留 @Value**（plan Step7 列在 admin 页）：降为部署期配置，因 SearXNG URL 是 Docker 地址非运行时项；admin 页通过 `providerAvailability.builtin` 只读展示其可用性。
- **web 开关用 2 态（开/关）**而非 plan 的"克隆 ragPref 3 态"：web 无全局继承概念（默认关即 opt-in），3 态无业务价值。
- **BuiltIn 并发抽正文用固定线程池 5** 而非 plan 写的 `Flux.merge`（Step2 既有，接口同步）。

### 留扩展点
- Agent / Workflow 模式未接（`WebSearchService` 可被 workflow 检索节点复用）。
- query→结果缓存本期不做。
- 告警阈值（失败率 > 30%）未接告警系统。
