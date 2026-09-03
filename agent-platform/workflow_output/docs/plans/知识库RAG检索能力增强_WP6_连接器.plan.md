---
description: "C6 连接器生态（URL 站点/S3/WebDAV 定时同步）的实现计划（WP6）"
created-date: 2026-09-03
---

# Implementation Plan for WP6：连接器

> 上级索引：[知识库RAG检索能力增强.plan.md](知识库RAG检索能力增强.plan.md)｜规格：[§8 C6](../specs/知识库RAG检索能力增强设计.md)

## 坑点预判（WP6 内）

| 坑 | 规避 | 验证 |
|---|---|
| **SSRF 变体**：首跳公网但 302 重定向进内网；DNS rebinding（域名解析两次结果不同） | 复用 `assertFetchSafe`（media 侧已防首跳）；重定向**手动跟随**：每跳重新过 assertFetchSafe（禁自动跟随）；域名解析后固定 IP 连接（解析一次用到底） | 单测：内网直连拒/重定向内网拒/每跳校验；渗透用例 localhost/169.254.169.254/0.0.0.0 |
| 爬虫死循环/黑洞（A→B→A、日历无限翻页） | 已访 URL set 去重 + 深度 ≤2 + 单轮 ≤50 页 + 总字节上限（如 200MB）四重闸 | 单测上限触发停止 |
| 凭证加密密钥管理（密钥哪来） | 复用应用主密钥体系（实施时核实现有加密设施——billing/LLM 密钥已加密存储的通道），无则 AES-GCM 密钥走环境变量；密文永不入日志/trace/异常消息 | 代码扫描无明文；日志断言 |
| 双节点并发认领同连接器 | 既有模式照抄：job 行 FOR UPDATE SKIP LOCKED 认领（IndexJobWorker 同款） | 并发单测 |
| 同步风暴打爆索引队列/LLM 计费 | 单轮 ≤50 文档入队；索引队列既有背压；同步产生的 embed/LLM 计费归连接器创建者；每连接器错峰（cron 抖动 ±10min） | 计量单测 |
| 中文/编码 URL（WebDAV/S3 key 百分号编码、Content-Type 乱码） | external_id 统一存原始未编码路径；下载时编码；文件名从响应头/URL 解码失败回退 external_id 尾段 | 中文路径单测 |
| 源删误删本地（规格默认 ISOLATED） | 默认「源删同步删」=false→ISOLATED；开关显式开启才硬删（且硬删走既有文档删除治理链，非直删 DB） | 单测两模式 |
| 同步文档与手工文档混淆 | connector_docs 映射表隔离；文档列表「🔌」徽标+external_id；手工删除同步文档→下轮不再重建（映射行标记手工删除） | 单测删除后不复活 |

## 实现步骤

- [x] **Step 1：数据层与凭证加密**（代码+测试全落，测试 4/4）
  - **目标**：两表落库；凭证密文存取
  - **动作**：①迁移 `V1xx__knowledge_connectors.sql`（connectors + connector_docs，规格 §8.1 DDL）；②实体/Mapper；③`ConnectorCryptoService`：AES-GCM 加解密 config（加密设施核实复用，见坑点表）；④Controller CRUD（仅 owner/canManage，@AuditLog 增删改）；⑤config 明文校验（endpoint 格式/类型必填字段结构）
  - **文件**：迁移 ×1、`connector/ConnectorCryptoService.java`、实体 ×2、Mapper ×2、`KnowledgeConnectorController.java`、Test ×2
  - **依赖**：无｜**验证**：单测——密文落库无明文/解密回读 ✅/权限 403 ✅/审计落库（@AuditLog 切面既有机制，注解挂齐+集成验证留 Phase4）
  - **实现注（偏离）**：①**无 ConnectorCryptoService**——核实既有 AES-GCM 设施=`llm/service/AesEncryptService`（billing/LLM 密钥同款主密钥 llm.encryption.secret，自带生产态弱密钥 fail-fast SEC-FR-074），直接注入复用，不新建重复轮子；②V175 对规格 DDL 修正：补 house 审计列（updated_by/updated_at/deleted @TableLogic——连接器有 status/last_sync 更新语义）+**超前落 `sync_on_source_delete`**（坑点表「源删误删」开关，默认 false=ISOLATED；趁建表免 Step3 再迁移）+connector_docs 补 `manual_deleted`（手工删不复活）与 tenant_id；③权限=**KB 治理级 isOwnerOrAdmin**（复用 KnowledgeBaseService 公有方法；canManage 授予位不含连接器——同 KB 改名/删除口径，比计划「owner/canManage」收紧）；④config 以 Map<String,Object> 进出（非裸 JSON 串）：明文态做类型结构校验（URL_SITE seedUrl/S3 endpoint+bucket+AK+SK/WEBDAV baseUrl+username；http(s) 形状）→Jackson 序列化→AES-GCM；**凭证只写不读**——VO 零 config 字段，重配=整表单重提交（update config=null=保留原密文）；⑤类型创建后不可变（external_id 语义随类型，换源=新建）；cron 用 Spring `CronExpression.isValidExpression` 六段校验；⑥删除=逻辑删（映射行随连接器失活不 CASCADE——FK CASCADE 仅硬删兜底；本地文档孤儿化保留）；⑦测试 4 条（计划 ×2）：密文落库断言不含 endpoint/AK/SK 任何明文+真 AES 实例解密回读+默认值口径/非 owner 403 零 insert/结构校验四态/类型不可变+config null 保留密文——AES 用真实实例非 mock 自证；⑧坑：ErrorCode.getCode() 返 int（403/422）非枚举名；R 包在 `common.result` 非 `common.response`。

- [x] **Step 2：SPI 与三连接器**（代码+测试全落，后端全量 2894/2894）
  - **目标**：URL_SITE/S3/WebDAV 可枚举可下载
  - **动作**：①`KnowledgeConnector` SPI（list(cursor)/fetch(doc)/close，规格 §8.2）；②`UrlSiteConnector`：种子 URL+同域深度 ≤2；HTML 链接提取（jsoup 或既有解析器复用）；**手动跟随重定向每跳 assertFetchSafe**；后缀白名单（html/htm/md/pdf/txt/docx/xlsx）；③`S3Connector`：AWS SDK v2（依赖核实——项目可能已有 S3 兼容客户端；无则新增最小依赖并过依赖安全检查）；ListObjectsV2 prefix 增量+etag；④`WebDavConnector`：PROPFIND depth 1 递归+etag；⑤下载限速 1 req/s+总字节闸
  - **文件**：`connector/KnowledgeConnector.java`、`UrlSiteConnector.java`、`S3Connector.java`、`WebDavConnector.java`、pom.xml（如需）、Test ×3
  - **依赖**：Step 1｜**需人工介入**：测试用 S3 桶/WebDAV 地址与凭证（真实源留 Phase4 手测）｜**验证**：单测——SSRF 三用例 ✅/去重 ✅/上限 ✅/etag 增量 ✅/mock 服务器爬取 ✅
  - **实现注（偏离）**：①SPI 落 `KnowledgeConnectorSpi`（包 `knowledge/connector/`）：list() 返**全量条目清单**非 cursor 流（cursor 增量交 worker 对账本差分——URL 站点/PROPFIND 本就无游标语义，S3 continuation 只是分页细节）；②**urlGuard 策略注入**（生产=SsrfGuard，测试 mock 源站绑 127.0.0.1 必被 loopback 拒→放行 guard 才能测爬取）——SSRF 语义另用真 SsrfGuard 直测五渗透字面量（localhost/169.254.169.254/0.0.0.0/10.0.0.5/file 协议）；③**SafeHttpFetch**（URL_SITE/WebDAV 共用）：`followRedirects(NEVER)` 手动跟 ≤3 跳、**每跳 Location 再过 guard**（自动跟随=校验过首跳 302 直进内网，坑点表头号变体，测试②锁死）；非 2xx 异常只带状态码不带响应体（防内网信息回显）；④UrlSiteConnector 深度语义：深度到顶页**仍提取本页链接为条目**（深度 3 的 pdf 挂深度 2 页照样同步）只是不再下钻；同域=scheme+authority 全等；etag=ETag 或 `LM:`Last-Modified；⑤WebDavConnector：jsoup **xmlParser**（HTML 解析器会小写化/重排树）+**命名空间前缀标签 CSS select 不匹配**（`d:response`≠`response`）→findByLocalName 递归本地名收集+childText/isCollection 无条件递归（getetag 嵌 propstat/prop 三层）；目录=子树任意 collection 元素；⑥S3Connector：**AWS SDK v2 2.54.11（2026-09 最新稳定线）**+url-connection 传输（pom 排除 apache/netty client 控树；SDK 默认 service-load 到残缺 apache5→**必须显式 `.httpClient(UrlConnectionHttpClient.create())`**）；endpointOverride+forcePathStyle 支持 MinIO/OSS/COS；分页闸 50 页兜底；限速/字节闸 SDK 路径手动施加（不经 SafeHttpFetch）；⑦FetchLimiter：1 req/s 默认+200MB 总闸，同步阻塞 sleep（worker 单线程顺序拉取）；⑧坑：SsrfGuard.validate 返 void 不能直接方法引用作 Predicate 须包 lambda；测试 +9（计划 ×3）：SafeHttpFetch 2+UrlSite 3（含成环去重/页数闸/fetch 字节）+WebDav 2+S3 2；⑨真实 S3 桶/WebDAV 源验证留 Phase4 手测（mock 源站覆盖协议解析层）。

- [ ] **Step 3：ConnectorSyncWorker**
  - **目标**：定时增量同步入既有解析索引管线
  - **动作**：①@Scheduled 轮询（fixedDelay 可配）+FOR UPDATE SKIP LOCKED 认领到期连接器；②流程：list 增量（etag 对比 connector_docs）→新增/变更→fetch→走**现有**文档上传解析索引管线（类型白名单/大小限制全复用）→更新映射行；源端消失→按「源删同步删」开关 ISOLATED 或治理删除；③结果摘要（新增/更新/删除/错误计数）写 last_sync_summary；连续 3 轮错误→status=ERROR；④cron 抖动错峰
  - **文件**：`connector/ConnectorSyncWorker.java`（新）、`KnowledgeDocumentService`（复用上传入口不改动或最小适配）、Test ×2
  - **依赖**：Step 2｜**验证**：单测——增量三态/并发认领/错误上限/限速；集成：mock 站点两轮同步（首轮 10 文档、次轮 1 改 1 增 1 删）

- [ ] **Step 4：前端 ConnectorPanel**
  - **目标**：连接器可视管理
  - **动作**：①KB 详情「连接器」Tab：列表（类型/状态/最近同步摘要）+新建弹窗（类型切换表单：URL=种子地址；S3=endpoint/bucket/prefix/AK/SK；WebDAV=地址/账号/密码；cron 预设每小时/每天/每周）；②启停/立即同步/删除（二次确认）；③ERROR 红标+错误摘要展开；④文档列表「🔌」徽标+external_id tooltip；⑤api/knowledge.ts 扩展
  - **文件**：`ConnectorPanel.vue`（新）、`KnowledgeView.vue`（挂 Tab）、`DocumentManager.vue`（徽标）、`src/api/knowledge.ts`、Test ×1
  - **依赖**：Step 1-3｜**验证**：vitest 表单分支/状态渲染；手测建 URL 连接器→立即同步→文档出现→可检索

- [ ] **Step 5：运维入口与收尾**
  - **目标**：出问题能查能修
  - **动作**：①连接器详情：最近 N 轮同步历史（时间/计数/错误摘要）；「立即同步」即手动重试入口；②错误摘要脱敏（内网地址/凭证替换为 ***）；③HELP 文档一段（user-ops 手册增补，Phase 5 收尾一起做）
  - **文件**：`ConnectorPanel.vue`、后端查询接口 ×1
  - **依赖**：Step 4｜**验证**：手测错误连接器（坏地址）→ERROR 状态+脱敏摘要+修好恢复

## 联动点（WP6 专属细化）

| 触发 | 联动 | 边界 |
|---|---|---|
| 同步文档新版本（etag 变） | 文档新版本链路（版本+1，旧版可回溯） | 引用旧版本的会话答案不回改；检索取最新 ACTIVE |
| 源端删除 | ISOLATED（默认）/治理删除（开关） | ISOLATED 不召回；源恢复→下轮自动复活；开关切换不追溯已处理项 |
| 连接器删除 | 本地文档**保留**（孤儿化，徽标变普通文档） | 映射行级联删；文档归手工管理 |
| 手工删除同步文档 | 下轮不复活 | 映射行标记 deleted；源端未变则跳过 |
| 保密库挂连接器 | 同步正常 | 成员不可见列表细节（保密矩阵现状）；owner 管理 |

## 验证汇总

- [ ] 单测新增 ~12（SSRF/加密/增量/并发/限速为主）
- [ ] 依赖安全：新增 SDK 过 mvn 依赖检查（无已知 CVE）
- [ ] 手测剧本：静态站点两轮同步全链路→检索命中；S3 增量；错误连接器运维路径
