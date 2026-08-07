# 开发进度5 — Phase4 运行验证（模型供应商全URL与类型化改造）

> 第 5 轮（2026-08-06）：Playwright 冒烟 19/19 + category 联动 API 验证 + V60 DB 验证 + 第二 AI 交叉审查（0🔴/2🟠/8🟡）+ 审查修复 commit `1453a3d`。

## 一、环境准备

- 后端启动首败：Flyway `Validate failed: Migration checksum mismatch for migration version 60`（V60 手工跑迁移时补登的 checksum=NULL 与文件实际 CRC32 不符）。修复：`mvn org.flywaydb:flyway-maven-plugin:9.22.3:repair`（补登 checksum=-1365934745）→ 启动成功。**教训：手工补登 flyway_schema_history 后，首次真实启动前跑一次 flyway:repair 对齐 checksum，否则 validate 必炸**。
- 前端 5173 既有进程；后端 8080 本轮起（JAVA_HOME=jdk-17 + local-dev-env 等效环境变量）。
- Playwright：无 playwright-mcp 工具，复用全局 `@playwright/mcp` 自带 playwright + ms-playwright 缓存 chromium-1140（`executablePath` 直指，期望的 1229 未下载）。脚本：[phase4_provider_smoke.mjs](../../docs/测试方案/phase4_provider_smoke.mjs)。

## 二、Playwright 冒烟：19/19 通过（截图 [phase4-shots/](phase4-shots/)）

| 组 | 断言 | 结果 |
|---|---|---|
| 登录+列表 | admin 登录；徽标含「视频」、无「对话+向量/媒体」旧类 | ✅ 01 |
| CHAT 弹窗 | placeholder=`/chat/completions`；协议可见 | ✅ 02 |
| EMBEDDING | placeholder=`/embeddings`；**ANTHROPIC 禁选**（disabled class 实证） | ✅ 03 |
| VIDEO | 协议隐藏；placeholder=`/api/v3/contents/generations/tasks` | ✅ 04 |
| IMAGE | 协议隐藏；点测试=info「生图 provider 尚未接入」且**未发请求**（request 监听实证） | ✅ 05 |
| 软警告 | CHAT 填 `https://api.openai.com/v1` 保存 → 创建成功 + warning「疑似 base URL」同现（不拦截） | ✅ 06 |
| VIDEO 真探测 | seedance 行点测试 → **「连接成功（任务端点可达，鉴权通过）· 134ms」**（真 ctaigw） | ✅ 07 |
| 视频生成页 | 模型选择器（cdance · Cdance2.0）+ 无 MEDIA 旧措辞 | ✅ 08 |
| console | 浏览器零 error | ✅ |

冒烟脚本三轮迭代的坑（已修在脚本里）：登录按钮文本是「登 录」带空格→class 选择器；naive 下拉关闭后选项残留 DOM→`:visible` 过滤；消息断言须按关键字过滤最新一条（残留消息干扰）；provider 名带 `Date.now()` 后缀幂等。

## 三、联动 + 迁移验证（API/DB 直连）

- **category 联动（plan 联动清单第 1 条）**：建 CHAT provider（模型 phase4-m 进 chat 可用列表 ✅）→ PUT 改 VIDEO + reload → chat 列表消失 ✅、视频目录出现 ✅ → 清理。
- **V60 迁移 DB 实证**：seedance 行 `category=VIDEO` + endpoint 完整 URL `https://ai.ctaigw.cn/v1/contents/generations/tasks` ✅；备份表 `llm_providers_bak_v60` 1 行 ✅。
- 测试数据已全部清理（llm_providers 仅余 seedance 1 行），配置已 reload。

## 四、第二 AI 交叉审查（对抗式，8 维度无沉默）

- **安全四重点逐项核查通过**：附件 IDOR 无旁路 / providerId 不可被提交方指定（请求体无该字段，反查仅限 ACTIVE VIDEO）/ 探测话术不泄 key / AES 链路零改动。
- **spec 漂移**：无少做；「多做」= attachments 多模态（SeedDance v2 有意扩范围，已归档）；「做偏」= plan 性能节「指纹缓存不变」与单槽实现矛盾（→ F5 已修）。
- 发现 **2🟠 + 8🟡**，修复 6 项（commit `1453a3d`）：

| 级 | 问题 | 修复 |
|---|---|---|
| 🟠 F1 | n-upload 非受控：切模型缩略图残留、提交载荷丢失（照文生视频计费）；同名文件删除错位 | 三 n-upload `v-model:file-list` 受控化 + `UploadFileInfo.id` 关联，onModelChange 双清 |
| 🟠 F2 | 附件 readAllBytes 全量进堆后才查大小（3×50MB×4 线程 ~2GB 堆 OOM 面） | 提交侧按 meta.size 预检 400 即拒 + readAsDataUri 读流前先查 meta.size；上限表单一真相 |
| 🟡 F3 | kind 校验归一化、落库原始值 → worker/Ark 映射错位 | 归一化后落库 |
| 🟡 F4 | Ark cancelled/expired 落 default→RUNNING 死轮询 | 显式映射 FAILED |
| 🟡 F5 | WebClient 单槽缓存多 provider 每轮重建 | ConcurrentHashMap<指纹, WebClient> |
| 🟡 F6 | 探测端点不校验 category（CHAT provider 误测得误导性「成功」） | testConnection 加 VIDEO 门 |

- **记档 backlog（未修）**：F7 multipart 60MB 全局放大且无配额（建议 per-user 配额另案）｜F8 前端能力广告 1080p/4K 超服务端 media.max-res=720p 默认（报错可读，选项与服务端上限不对齐）｜F9 下载 maxInMemorySize=16MB vs 放开的 15s/4K（放开后大成片确定性 DOWNLOAD_FAILED，建议流式落盘改造）｜F10 userId=null 任务静默丢弃 attachments（当前不可达死路径，未来系统调用方须改显式 FAILED）。

## 五、复验

- 后端 media 57 测绿 + 前端 vitest 206 绿 + vue-tsc 0 错。
- 修复后重启后端重跑冒烟：18/19（VIDEO 探测遇本地 DNS UDP 超时抖动）→ API 直连重试 **3/3 通**（586/75/69ms）= 实证网络瞬断非回归。
- `check_docs.py`：FAIL=0（仅模板 INFO）。

## 六、结论与留项

- **放行判断**：改造主体（FR-001~006）运行验证通过，可进 Phase5 候判。性能节无目标（plan 声明不验；实测探测 69-586ms）。
- **留项（人工/后续）**：plan 整体验证的「对话选 CHAT 模型聊一句 / RAG embed 链路 / 画布节点选模型运行 / 资产库拆分场回显」本地无 CHAT/EMBEDDING provider 未验（云端或补配后验）；F7-F10 backlog；User-Ops 未产（改造类，判定不产）；功能 README 未单产（收口进速查表 18）。
- **运维提醒**：本地 dev 库目前仅 seedance 1 行 provider——对话/记忆/RAG 功能本地不可用属预期；云端须有 CHAT+EMBEDDING 行（速查表18 记忆/judge 模型段）。
