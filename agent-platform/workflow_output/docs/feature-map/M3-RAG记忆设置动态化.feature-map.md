# M3 RAG 记忆设置动态化 · Feature Map

> 功能-代码速查表。原待办 #14(按检索模式动态显隐)+ #19(entities 计数可配)。

## 一句话
后台「记忆设置」tab 按检索模式只露相关旋钮(动态显隐),并把 LLM 抽 entities 词袋的数量上限从代码硬编码搬进可配置 JSON。

## 技术原理(大白话)
- **动态显隐** = 顶部选检索模式 → 下方 v-if 按模式显隐对应旋钮组。像手机「飞行模式」开了才显蓝牙/WiFi 细项,关了就收起来,不让无关选项堆满屏幕。
- **entities 计数可配** = 记忆入库时 LLM 抽「召回关键词」(如女儿→孩子/小孩/家人/啊闪)。原来抽多少个、抽哪几类,写死在代码(totalMax=20)。现在搬进设置页一个 JSON 配置,管理员可调。改完老数据靠既有「重抽关键词」按钮吃新配。

## 代码速查
| 功能点 | 文件:行 | 说明 |
|---|---|---|
| entities 配置 record | [MemoryEntitiesConfig.java](../../../backend/src/main/java/com/superprogrammer/system/dto/MemoryEntitiesConfig.java) | 7 字段 + defaults() + normalized()(兜底归一:min>max 互换、clamp、totalMax 上调到合计) |
| KV 常量 + 读取 + 写回 | [SystemSettingService.java](../../../backend/src/main/java/com/superprogrammer/system/service/SystemSettingService.java) `RAG_MEMORY_ENTITIES_CONFIG` / `getMemoryEntitiesConfig` / `updateMemoryEntitiesConfig` | 单 JSON key,Jackson 反序列化,损坏回退默认 + 告警 log |
| Judge 读配置 + prompt token 化 | [MemoryConflictJudge.java](../../../backend/src/main/java/com/superprogrammer/chat/service/internal/MemoryConflictJudge.java) `extract` / `batchExtractEntities` / `applyEntitiesConfig` | EXTRACT_PROMPT + BATCH_ENTITIES_PROMPT 硬编码数字改命名 token {TOTAL_MAX}/{VARIANT_MIN-MAX}/{HYPERNYM_MIN-MAX} |
| readEntities 动态截断 | MemoryConflictJudge.java `readEntities(JsonNode, int totalMax)` | 原 `>=20` 改读 config.totalMax |
| Controller 并入 | [SystemSettingController.java](../../../backend/src/main/java/com/superprogrammer/system/controller/SystemSettingController.java) `getRagMemorySettings` / `updateRagMemorySettings` | entitiesConfig 进 RagMemorySettings VO/Update(非新端点) |
| Flyway seed | [V45__seed_memory_entities_config.sql](../../../backend/src/main/resources/db/migration/V45__seed_memory_entities_config.sql) | 默认 JSON = V38 硬上限(零行为变更) |
| 前端 API | [api/system.ts](../../../frontend/src/api/system.ts) `MemoryEntitiesConfig` / `RagMemorySettings.entitiesConfig` | TS 接口镜像 |
| 前端 tab 动态显隐 | [RagMemorySettingsTab.vue](../../../frontend/src/components/settings/RagMemorySettingsTab.vue) | 4 模式 :disabled → v-if;entities 卡片仅 LLM_KEY/VECTOR_KEYWORD 显;min/max NInputNumber 联动约束 |

## 表注解
| 表 | 用途 | 本功能改动 |
|---|---|---|
| system_settings | 全局 KV 配置表 | 新增 1 行 key=`rag.memory.entities-config`,value=JSON。无 schema 变更(纯 TEXT value),V45 seed |

## 模式→显隐映射
| 检索模式 | 显隐的旋钮 |
|---|---|
| LLM_FULL_CONTEXT(默认) | 全量阈值 + 标签语言 |
| EMBEDDING_VECTOR | (无额外,仅常驻 3 项) |
| VECTOR_KEYWORD | 关键词召回块阈值 + 关键词通道上限 + entities 计数卡片 |
| LLM_KEY | LLM_KEY 粗筛候选数 + LLM_KEY 精排开关 + entities 计数卡片 |
| 常驻(所有模式) | 记忆总开关 + 处理模式 + 检索模式 + 3 运维按钮(回填/重抽/清理) |

## 配置字段语义
| 字段 | 默认 | 含义 |
|---|---|---|
| totalMax | 20 | entities 总词数硬上限(Java 截断 + prompt 指引) |
| variantMin/Max | 1 / 3 | 同义变体词数量区间(女儿→孩子/小孩/闺女) |
| properNounMin/Max | 1 / 5 | value 专有名词数量区间(人名/地名/品牌) |
| hypernymMin/Max | 5 / 10 | 上位词数量区间(家人/工作/居住,决定泛问召回) |

## 依赖关系
- 改 entities-config 数值 → **下次** extract/batchExtractEntities 吃新值;**老数据不自动重抽**,需手动点「重抽关键词」端点(已存在,天然联动)。
- 默认值 = V38 硬上限 → 存量行为零变更(灰度安全)。
