# WinServer 2019 部署前置条件

> 目标：把 agent-platform（backend + frontend + runtime-sidecar）部署到 Windows Server 2019。
> 本文档列全量前置软件 + 验证命令 + Windows 特定坑。开发机（Win10 build 19045）已按此实测，目标机 WinServer 2019（build 17763）同 x64 NT 内核族，移植置信度高。
> 创建：2026-06-18。所有版本号 = 开发机实测值，目标机用同档或更高。

---

## 一、前置软件总表

| # | 软件 | 实测版本 | 用途 | 必须 | Windows 特定说明 |
|---|------|---------|------|------|----------------|
| 1 | OS | WinServer 2019 (build 17763) x64 | 运行环境 | ✅ | 同内核族 Win10 可直接迁移 |
| 2 | PostgreSQL | **16.14**（EDB 安装包，MSVC 构建） | 主库 + pgvector 向量库 | ✅ | **必须 EDB 版**（MSVC），pgvector dll 须匹配；版本固定 16.x |
| 3 | pgvector | **0.8.2**（`vector.v0.8.2-pg16.zip`） | 向量检索（HNSW） | ✅ RAG 必须 | 官方零 Windows Release，用社区预编译 |
| 4 | Redis | **≥5.0**（推荐）；当前 dev 3.0.504 ⚠️ | JWT 黑名单 + RAG 可见集缓存 | ✅ | Windows 无官方版，见 §四风险 |
| 5 | JDK | **17.0.x LTS**（实测 17.0.19 Microsoft-13877129） | backend 运行 | ✅ | Temurin/Microsoft OpenJDK 均可 |
| 6 | Maven | **3.9.x**（实测 3.9.16） | backend 构建 | 构建期 | 或用 CI 产出 jar，生产机可免 |
| 7 | Node.js | **18+ LTS**（实测 24.11.1） | frontend 构建 | 构建期 | Vite 5 需 18+；构建后产物静态托管 |
| 8 | Python | **3.10+**（实测 3.11.9） | runtime-sidecar | ⚠️ 可选 | `mode=mock` 时不需要 |
| 9 | Git | 任意（实测 2.54） | 拉代码 | 部署期 | |
| 10 | VC++ 2015-2022 Redistributable (x64) | 已装（`vcruntime140.dll` 在） | PG/Redis dll 运行时依赖 | ✅ | 缺则 PG/Redis 起不来 |
| 11 | nginx（或 Spring Boot 自托管 dist） | 任一 | 前端静态 + 反代 `/api`、`/ws` | ✅ | Windows 版 nginx 可用；或 Java 直接 serve |

> **构建期 vs 运行期**：Maven/Node/Git 只在打包时用。生产机可只装 JRE 17 + PG + Redis + nginx（+ 可选 Python），跑预编译 jar + 预构建 dist。

---

## 二、安装顺序

```
1. 系统更新 + 装 VC++ 2015-2022 x64 Redistributable
2. 装 PostgreSQL 16 (EDB)，记住 superuser 密码
3. 装 pgvector（见 §三，须 PG 服务停→放文件→启）
4. 建库 agent_platform + 跑全部 Flyway 迁移（V1..V17）
5. 装/部署 Redis（见 §四）
6. 装 JDK 17（设 JAVA_HOME）+ Maven（构建 backend，或直接拿 jar）
7. 装 Node 18+（构建 frontend，或直接拿 dist）
8. (可选) 装 Python 3.10+ + 装 runtime-sidecar 依赖
9. (可选) 装 nginx，配反代
10. 注册 Windows 服务（NSSM/WinSW）：backend / Redis / (sidecar) / nginx
```

---

## 三、PostgreSQL 16 + pgvector（最关键，坑最多）

### 3.1 PostgreSQL

- 装 **EDB 官方安装包**（EnterpriseDB），非 zip/portable。理由：pgvector 社区 dll 按 EDB/MSVC 构建匹配。
- 版本 **16.x**（开发机 16.14）。**不要跨大版本**（pgvector dll 按主版本绑 ABI）。
- 安装时勾选：Server、Command Line Tools。记住端口（默认 5432）+ superuser（postgres）密码。
- 设 `shared_preload_libraries` 暂不需要（pgvector 不强制）。
- **DB 编码必须 UTF8**（实测 `SHOW server_encoding` = UTF8），中文内容依赖。

### 3.2 pgvector（HNSW 维度坑）

- **官方 pgvector 无 Windows Release**（GitHub releases 为空 `[]`，只有 source tag）。用社区预编译：`andreiramani/pgvector_pgsql_windows`。
- 选 **`vector.v0.8.2-pg16.zip`**（tag `0.8.2_16.1`）。匹配 PG16，ABI 在 16.x 内稳定（16.1 dll 跑 16.14 OK）。
- 下载（GitHub 直连可，无需代理）：`https://github.com/andreiramani/pgvector_pgsql_windows/releases/download/0.8.2_16.1/vector.v0.8.2-pg16.zip`

**安装步骤**：

```
1. services.msc 停 postgresql-x64-16
2. 解压 zip 到 PG 安装根目录（覆盖）：
   - lib\vector.dll               → <PG>\lib\
   - share\extension\vector.control + vector--*.sql → <PG>\share\extension\
3. 启 postgresql-x64-16
4. psql: CREATE EXTENSION vector;
5. 验证: SELECT extversion FROM pg_extension WHERE extname='vector';  -- 期望 0.8.2
```

### 3.3 ⚠️ HNSW 维度硬限（必须知道）

**pgvector HNSW 索引硬限 ≤2000 维**。Doubao embedding = 2048 维 > 2000 → `vector(2048)` 建索引必报错：

```
ERROR: column cannot have more than 2000 dimensions for hnsw index
```

**解法（项目已采用）**：所有向量列用 **`halfvec(2048)`** + `halfvec_cosine_ops`（halfvec HNSW 上限 4000 维，2048 容得下，存储减半，召回损失可忽略）。V17 迁移已据此修正。**若换 embedding 模型，须确认其维度 ≤2000 用 vector，或 ≤4000 用 halfvec，否则改维度或换索引策略**。

验证 HNSW 建成：

```sql
-- 建个临时表测
CREATE TEMP TABLE t (emb halfvec(2048));
INSERT INTO t SELECT g, (SELECT array_agg(random()) FROM generate_series(1,2048))::halfvec(2048) FROM generate_series(1,10) g;
CREATE INDEX ON t USING hnsw (emb halfvec_cosine_ops);  -- 成功即 OK
```

---

## 四、Redis（Windows 最大风险点）

**Redis 官方不支持 Windows**。开发机现用 MSOpenTech **3.0.504**（2015 年移植、微软已归档、版本远落后 Lettuce 6.x 客户端目标）。功能上 JWT 黑名单 + RAG 可见集的基础 SET/GET/EXPIRE 能跑，但**生产可靠性/维护性差**。

**生产四选一（按推荐序）**：

| 方案 | 版本 | 评价 |
|------|------|------|
| **WSL2 + 官方 Redis 7** | 7.x | 最干净，Server 2019 支持 WSL2（需装 WSL2 内核更新）。生产首选 |
| Memurai | 7.x 兼容 | Windows 原生 Redis 兼容，开发免费、商用付费 |
| tporadowski/redis 移植 | 5.0.14 | 比当前新，社区维护，免费 |
| 维持 MSOpenTech 3.0.504 | 3.0.504 | ⚠️ 能跑但老，不推荐生产 |

**校验**：`redis-cli ping` → `PONG`；`redis-cli INFO server | findstr redis_version`。

---

## 五、运行时网络出口

- **LLM API 全国内**：Doubao（`ark.cn-beijing.volces.com`）、DeepSeek、GLM（`open.bigmodel.cn`）、Kimi——**生产机无需代理**。
- **GitHub 代理（127.0.0.1:7890）仅构建期**：拉 npm/pip 包或 GitHub 源时用。运行时不用。
- 注意：开发实测时代理 7890 曾挂，但 pgvector dll GitHub **直连可下**。npm/pip 装依赖时若直连慢再挂代理。

---

## 六、配置项（部署时设）

backend `application.yml` / 环境变量：

| 配置 | 默认 | 生产须改 |
|------|------|---------|
| `DB_HOST/DB_PORT/DB_NAME` | localhost:5432/agent_platform | 生产库地址 |
| `DB_USER/DB_PASSWORD` | postgres / aa64221886 | **改强密码**（dev 密码勿上生产） |
| `REDIS_HOST/REDIS_PASSWORD` | localhost / 空 | 生产 Redis 地址 + **设密码** |
| `JWT_SECRET` | 配置值 | **改随机长串** |
| `runtime.gateway.mode` | mock | 工作流走 sidecar 时设 sidecar |
| `RUNTIME_SIDECAR_BASE_URL` | http://localhost:8090 | sidecar 地址 |
| `-Dfile.encoding` | — | **必加 UTF-8**（Windows 默认 GBK，文档解析+中文 SQL 依赖） |
| `app.files.storage-dir` | uploads/workflow-inputs | 文档上传目录，须可写 |

JVM 启动参数示例：`-Dfile.encoding=UTF-8 -Xms512m -Xmx2g`。

---

## 七、Windows 服务注册（NSSM）

生产把各进程注册为开机自启 Windows 服务。用 **NSSM**（Non-Sucking Service Manager）：

```powershell
# backend
nssm install agent-platform-backend "C:\Program Files\Java\jdk-17\bin\java.exe" "-Dfile.encoding=UTF-8 -jar agent-platform.jar"
nssm set agent-platform-backend AppDirectory "D:\app\agent-platform"
nssm set agent-platform-backend AppEnvironmentExtra "DB_PASSWORD=xxx" "JWT_SECRET=xxx"

# redis（若用原生 Windows 移植）
nssm install agent-platform-redis "C:\Redis\redis-server.exe"

# sidecar（可选）
nssm install agent-platform-sidecar "C:\Python311\python.exe" "-m uvicorn app.main:app --host 0.0.0.0 --port 8090"

# nginx（自带 nginx.exe 服务化或 NSSM）
```

---

## 八、一键验证清单（装完跑）

```bash
# PG + pgvector
psql -h localhost -U postgres -d agent_platform -c "SELECT version();"            # 16.x
psql ... -c "SELECT extversion FROM pg_extension WHERE extname='vector';"          # 0.8.2
psql ... -c "SELECT count(*) FROM pg_tables WHERE tablename LIKE 'knowledge%';"   # ≥8（RAG 表）

# Redis
redis-cli ping                                                                     # PONG

# Java
java -version                                                                      # 17.x

# frontend 产物
ls frontend\dist\index.html                                                        # 存在

# backend 起来后
curl http://localhost:8080/api/...                                                  # 200
```

---

## 九、已知 Windows 部署风险汇总

| 风险 | 严重度 | 处置 |
|------|--------|------|
| Redis on Windows 无官方支持 | 🔴 高 | WSL2/Memurai，勿用 3.0.504 上生产 |
| pgvector HNSW ≤2000 维 | 🟡 中 | 已用 halfvec(2048) 解决（§3.3） |
| pgvector dll 须匹配 PG16 EDB | 🟡 中 | 用 v0.8.2-pg16 社区包，勿跨版本 |
| JVM 默认编码 GBK | 🟡 中 | 启动加 `-Dfile.encoding=UTF-8` |
| 多进程 Windows 服务编排 | 🟡 中 | NSSM 注册，比 systemd 麻烦但可行 |
| V12–V17 迁移未提交 git | 🟡 中 | 部署前先提交，干净库按序执行 |
| dev 库密码硬编码 yml | 🔴 高 | 生产必改强密码 + 走环境变量 |

---

## 相关文档

- 整体架构：`项目工程文档/项目整体说明.md`
- RAG 落地计划：`项目工程文档/计划/计划10-企业级RAG知识库.md`
- RAG 设计：`项目工程文档/设计/后续其他功能设计/企业级RAG向量库知识库设计v6.md`
- Sidecar 部署配置：`项目工程文档/项目功能介绍/项目相关配置说明/Runtime Sidecar部署配置说明.md`
- 数据库设计：`项目工程文档/数据库设计文档.md`
