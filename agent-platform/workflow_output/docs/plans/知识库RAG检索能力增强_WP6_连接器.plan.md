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

- [ ] **Step 1：数据层与凭证加密**
  - **目标**：两表落库；凭证密文存取
  - **动作**：①迁移 `V1xx__knowledge_connectors.sql`（connectors + connector_docs，规格 §8.1 DDL）；②实体/Mapper；③`ConnectorCryptoService`：AES-GCM 加解密 config（加密设施核实复用，见坑点表）；④Controller CRUD（仅 owner/canManage，@AuditLog 增删改）；⑤config 明文校验（endpoint 格式/类型必填字段结构）
  - **文件**：迁移 ×1、`connector/ConnectorCryptoService.java`、实体 ×2、Mapper ×2、`KnowledgeConnectorController.java`、Test ×2
  - **依赖**：无｜**验证**：单测——密文落库无明文/解密回读/权限 403/审计落库

- [ ] **Step 2：SPI 与三连接器**
  - **目标**：URL_SITE/S3/WebDAV 可枚举可下载
  - **动作**：①`KnowledgeConnector` SPI（list(cursor)/fetch(doc)/close，规格 §8.2）；②`UrlSiteConnector`：种子 URL+同域深度 ≤2；HTML 链接提取（jsoup 或既有解析器复用）；**手动跟随重定向每跳 assertFetchSafe**；后缀白名单（html/htm/md/pdf/txt/docx/xlsx）；③`S3Connector`：AWS SDK v2（依赖核实——项目可能已有 S3 兼容客户端；无则新增最小依赖并过依赖安全检查）；ListObjectsV2 prefix 增量+etag；④`WebDavConnector`：PROPFIND depth 1 递归+etag；⑤下载限速 1 req/s+总字节闸
  - **文件**：`connector/KnowledgeConnector.java`、`UrlSiteConnector.java`、`S3Connector.java`、`WebDavConnector.java`、pom.xml（如需）、Test ×3
  - **依赖**：Step 1｜**需人工介入**：测试用 S3 桶/WebDAV 地址与凭证｜**验证**：单测——SSRF 三用例/去重/上限/etag 增量/mock 服务器爬取

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
