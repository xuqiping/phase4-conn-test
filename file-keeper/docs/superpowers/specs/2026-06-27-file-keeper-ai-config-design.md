# File Keeper AI 模型配置设计文档

> 文档版本：v1.0  
> 编写日期：2026-06-27  
> 适用范围：file-keeper 桌面端 + file-keeper/server 后端

---

## 1. 背景与目标

### 1.1 背景

file-keeper 目前已实现工作汇报模块的 AI 总结能力（`AiSummaryService`），但 AI Provider、API Key、模型等参数通过服务端环境变量配置：

```yaml
work-report:
  ai:
    provider: ${WORK_REPORT_AI_PROVIDER:qwen}
    api-key: ${WORK_REPORT_AI_API_KEY:}
    endpoint: ${WORK_REPORT_AI_ENDPOINT:}
    model: ${WORK_REPORT_AI_MODEL:}
```

桌面端只有一个 `aiEnabled` 开关，用户无法自行选择模型、填写自己的 API Key。

### 1.2 目标

- 让用户在桌面端自行配置 AI 模型参数（Provider、模型、API Key、Endpoint 等）。
- 配置保存在 file-keeper 服务端，API Key 加密存储。
- AI 能力作为独立商业授权模块管控，授权后可在各业务模块中使用。
- 优先用于工作汇报的 AI 总结，但设计为通用能力，方便后续扩展。

### 1.3 非目标

- 不复用 agent-platform 的 LLM 网关（两个项目独立）。
- 本次不涉及管理后台的 AI 配置页面（仅涉及给用户授权 AI 模块）。
- 不涉及 AI 流式输出、多轮对话、Function Calling 等高级能力。

---

## 2. 关键决策

| 决策点 | 选择 | 说明 |
|---|---|---|
| 配置归属 | 按 `user_id` | 一个用户可有多条 AI 配置，与 `report_configs` 同维度。 |
| 配置存储位置 | file-keeper 服务端数据库 | 用户填写后由后端加密落库，AI 调用仍由后端统一发起。 |
| 配置入口 | 桌面端全局 SettingsDialog | 放在「公共与基础设施」层，类似主题、快捷键设置。 |
| 授权模型 | 独立模块 `ai` | AI 能力单独授权，不绑定到具体业务模块。 |
| API Key 加密 | 复用 `CredentialEncryptor` | AES-GCM 加密，密钥为 `work-report.credential-key`。 |
| 无配置/失败处理 | 降级为简单拼接 | 保证报告等业务始终可生成。 |

---

## 3. 数据模型

### 3.1 新增表：`ai_configs`

```sql
CREATE TABLE ai_configs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(64) NOT NULL,
    api_key_enc VARCHAR(512),
    endpoint VARCHAR(256),
    max_tokens INT NOT NULL DEFAULT 2048,
    timeout_seconds INT NOT NULL DEFAULT 30,
    is_default BOOLEAN NOT NULL DEFAULT false,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_ai_configs_user_name UNIQUE (user_id, name)
);

CREATE INDEX idx_ai_configs_user_id ON ai_configs(user_id);
CREATE INDEX idx_ai_configs_user_default ON ai_configs(user_id, is_default) WHERE is_default = true;
```

**字段说明**

| 字段 | 说明 |
|---|---|
| `user_id` | 配置所有者 |
| `name` | 用户自定义名称，如「通义千问-日报」 |
| `provider` | `qwen` / `doubao` / `claude` |
| `model` | 模型名，如 `qwen-turbo` |
| `api_key_enc` | 加密后的 API Key，可为空（未配置时走降级） |
| `endpoint` | 自定义 endpoint，为空使用 Provider 默认 |
| `max_tokens` | 默认 2048 |
| `timeout_seconds` | 默认 30 |
| `is_default` | 是否为该用户默认配置 |
| `enabled` | 是否启用 |

### 3.2 改造表：`report_configs`

增加外键字段：

```sql
ALTER TABLE report_configs ADD COLUMN ai_config_id BIGINT;
```

用于指定该汇报配置使用哪条 AI 配置；为 `NULL` 时使用用户默认配置。

### 3.3 实体

新增 `com.superprogrammer.ai.entity.AiConfig`，继承 `BaseEntity`。

> 注：虽然当前首个消费者是工作汇报模块，但 AI 配置定位为通用能力，因此单独放在 `com.superprogrammer.ai` 包下，避免与 `workreport` 强耦合。

---

## 4. 后端设计

### 4.1 商业授权模块注册

1. `AuthConstants.java` 新增：

```java
public static final String MODULE_AI = "ai";
```

2. `AuthorizationService.MODULE_CODES` 列表加入 `MODULE_AI`。

3. 涉及 `module_code` CHECK 约束的表需同步更新（如 `user_module_entitlements`、`anonymous_device_trials.free_module_code` 等）。

### 4.2 Repository

新增 `com.superprogrammer.ai.repository.AiConfigRepository`，风格与现有 `ReportConfigRepository` 一致，使用 `JdbcTemplate`：

- `insert(config)`
- `update(config)`
- `findByIdAndUserId(id, userId)`
- `findByUserId(userId)`
- `findDefaultByUserId(userId)`
- `softDeleteById(id, updatedBy)`
- `clearDefaultByUserId(userId)` — 把该用户其他配置 `is_default` 置为 false

### 4.3 DTO

新增请求/响应 DTO（位于 `com.superprogrammer.ai.dto`）：

- `AiConfigCreateRequest`
- `AiConfigUpdateRequest`
- `AiConfigVO`（不返回 `apiKey`）

### 4.4 Service

新增 `com.superprogrammer.ai.service.AiConfigService`：

- CRUD、加密/解密 API Key。
- 设置默认配置时先 `clearDefaultByUserId`。
- 提供 `getEffectiveConfig(userId, aiConfigId)`：
  - 若 `aiConfigId` 非空，返回指定配置；
  - 若为空，返回用户默认配置；
  - 配置不存在、未启用、无解密 Key 时返回 `null`。

### 4.5 Controller

新增 `com.superprogrammer.ai.controller.AiConfigController`：

```java
@RestController
@RequestMapping("/api/client/ai-configs")
@RequiredArgsConstructor
public class AiConfigController {

    @GetMapping
    public R<List<AiConfigVO>> list(Authentication auth, @RequestParam String deviceId) { ... }

    @GetMapping("/{id}")
    public R<AiConfigVO> get(Authentication auth, @PathVariable Long id, @RequestParam String deviceId) { ... }

    @PostMapping
    public R<AiConfigVO> create(Authentication auth, @RequestBody @Valid AiConfigCreateRequest request, @RequestParam String deviceId) { ... }

    @PutMapping("/{id}")
    public R<AiConfigVO> update(Authentication auth, @PathVariable Long id, @RequestBody @Valid AiConfigUpdateRequest request, @RequestParam String deviceId) { ... }

    @DeleteMapping("/{id}")
    public R<Void> delete(Authentication auth, @PathVariable Long id, @RequestParam String deviceId) { ... }

    @PutMapping("/{id}/default")
    public R<AiConfigVO> setDefault(Authentication auth, @PathVariable Long id, @RequestParam String deviceId) { ... }
}
```

所有方法先校验 `MODULE_AI` 授权，再校验资源归属。

### 4.6 改造 `AiSummaryService`

废弃 `@Value` 注入的 provider/api-key/endpoint/model 环境变量，`AiSummaryService` 完全从 `AiConfigService` 读取用户配置：

```java
public String summarize(List<WorkLog> logs, List<FixedWorkItem> fixedWorkItems,
                        String reportType, Long aiConfigId, Long userId) {
    if (logs.isEmpty() && fixedWorkItems.isEmpty()) {
        return "";
    }

    AiConfigVO config = aiConfigService.getEffectiveConfig(userId, aiConfigId);
    if (config == null || !config.getEnabled()) {
        log.warn("未找到有效 AI 配置，使用降级策略");
        return fallbackSummary(logs, fixedWorkItems);
    }

    String prompt = buildPrompt(logs, fixedWorkItems, reportType);
    try {
        return callAiApi(prompt, config);
    } catch (Exception e) {
        log.error("AI 总结失败，降级为简单拼接", e);
        return fallbackSummary(logs, fixedWorkItems);
    }
}
```

`callAiApi` 根据 `config.getProvider()` 路由，使用 `config.getModel()`、`config.getEndpoint()`、`config.getMaxTokens()`、`config.getTimeoutSeconds()`。

### 4.7 改造 `WorkReportService.generate()`

```java
String aiSummary = Boolean.TRUE.equals(config.getAiEnabled())
    ? aiSummaryService.summarize(logs, completedFixedWork,
                                 config.getReportType(), config.getAiConfigId(), userId)
    : "";
```

### 4.8 环境变量处理

MVP 阶段**废弃** `work-report.ai.provider`、`api-key`、`endpoint`、`model` 四个环境变量，完全由用户在桌面端配置驱动。

`timeout-seconds`、`max-tokens` 可保留作为全局默认值，用户未填写单条配置时生效。部署文档需同步删除旧的环境变量说明，避免运维误配。

---

## 5. 桌面端设计

### 5.1 Store

新增 `file-keeper/src/stores/aiConfigStore.ts`：

```ts
export const useAiConfigStore = defineStore('ai-config', () => {
  const configs = ref<AiConfig[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function loadConfigs() { ... }
  async function saveConfig(config: AiConfigForm) { ... }
  async function deleteConfig(id: number) { ... }
  async function setDefault(id: number) { ... }

  const defaultConfig = computed(() => configs.value.find(c => c.isDefault))

  return { configs, loading, error, defaultConfig, loadConfigs, saveConfig, deleteConfig, setDefault }
})
```

**注意**：不配置 Pinia 持久化插件，避免 API Key 落本地磁盘。

### 5.2 API

新增 `file-keeper/src/api/aiConfig.ts`：

```ts
export function listAiConfigs(): Promise<AiConfig[]>
export function getAiConfig(id: number): Promise<AiConfig>
export function createAiConfig(config: AiConfigForm): Promise<AiConfig>
export function updateAiConfig(id: number, config: AiConfigForm): Promise<AiConfig>
export function deleteAiConfig(id: number): Promise<void>
export function setDefaultAiConfig(id: number): Promise<AiConfig>
```

### 5.3 SettingsDialog.vue

在全局设置弹窗中新增「AI 模型」Tab/分类：

- 未授权 `ai` 模块时不显示该 Tab。
- 显示 `AiConfigSettings.vue` 子组件。

### 5.4 AiConfigSettings.vue

功能：

- 配置列表展示：名称、Provider、模型、是否默认、启用状态。
- 新增/编辑表单：
  - 配置名
  - Provider 下拉（qwen / doubao / claude）
  - 模型输入框（选择 Provider 后自动填充默认模型名，可修改）
  - API Key 密码输入框（不回显，仅保存时上传）
  - 自定义 Endpoint（可选）
  - Max Tokens
  - Timeout
  - 启用开关
- 操作：保存、删除、设为默认。

### 5.5 ReportConfigForm.vue

移除工作汇报模块内的 AI 配置入口，仅保留：

- `aiEnabled` 开关
- 「使用 AI 配置」下拉框：
  - 「使用默认配置」
  - 用户已创建的各条 AI 配置

未授权 `ai` 模块时，以上两个控件不显示。

### 5.6 类型与国际化

- 新增 `file-keeper/src/types/aiConfig.ts`
- `locales/zh-CN.ts` 和 `en.ts` 增加 `settings.aiConfig` 命名空间

---

## 6. 安全设计

| 风险 | 措施 |
|---|---|
| API Key 泄露 | 数据库存储加密；VO 不回显；前端不持久化到本地。 |
| 越权访问他人配置 | Controller 每次校验资源 `user_id` 与当前登录用户一致。 |
| 未授权使用 AI | 所有 AI 接口先校验 `MODULE_AI` 授权。 |
| AI 服务不可用 | 超时、异常、空响应均降级为简单拼接。 |
| 敏感配置被枚举 | 列表接口只返回当前用户配置，不暴露他人数据。 |

---

## 7. 测试策略

### 7.1 后端

- `AiConfigRepositoryTest`：CRUD、默认配置切换、用户隔离。
- `AiConfigServiceTest`：加密解密、设置默认、未授权异常。
- `AiSummaryServiceTest`：正常调用、无配置降级、API 异常降级、超时降级。
- `WorkReportServiceTest`：按 `aiConfigId` 选择配置、按默认配置选择。
- `AiConfigControllerTest`：未授权 AI 模块返回 403；授权后正常 CRUD。
- Flyway 迁移脚本在 H2/PostgreSQL 下验证。

### 7.2 桌面端

- `aiConfig.test.ts`：API 层结构。
- `aiConfigStore.test.ts`：状态管理。
- `SettingsDialog.test.ts`：未授权 AI 不显示 Tab；授权后显示。
- `ReportConfigForm.test.ts`：未授权 AI 不显示 AI 选项；授权后可选择配置。

### 7.3 管理后台

- `UserDetailView.vue` 的 `moduleOptions` 追加 `{ code: 'ai', name: 'AI 能力' }`。
- 后端授权接口已通用，通常无需额外改动。

---

## 8. 验收标准

- [ ] 桌面端「设置」中新增「AI 模型」页签，未授权 AI 模块时不显示。
- [ ] 用户可创建、编辑、删除多条 AI 配置，可设置默认配置。
- [ ] API Key 在服务端加密存储，前端不回显。
- [ ] 工作汇报配置中可选择使用哪条 AI 配置，未指定时使用默认配置。
- [ ] AI 总结调用使用用户配置的 Provider/模型/Endpoint/Key。
- [ ] AI 调用失败时降级为简单拼接，报告仍可生成。
- [ ] 后端 AI 配置接口和 AI 总结接口均校验 `MODULE_AI` 授权。
- [ ] 单元测试覆盖核心逻辑。

---

## 9. 风险与注意事项

| 风险 | 说明 |
|---|---|
| 用户填错 API Key | 调用失败走降级，需在 UI 提示「AI 调用失败，已使用基础模式」。 |
| Provider 默认 endpoint 变更 | 代码中维护默认 endpoint，允许用户覆盖。 |
| 多设备登录同一账号 | 配置按 user_id，多设备共享，符合预期。 |
| 匿名用户 | 匿名用户无 AI 模块授权，看不到 AI 设置，工作汇报不使用 AI。 |
| 环境变量配置废弃 | 需确认是否保留全局兜底，并在部署文档中更新。 |

---

## 10. 涉及文件清单

### 后端

| 文件 | 动作 |
|---|---|
| `file-keeper/server/src/main/java/com/superprogrammer/security/AuthConstants.java` | 新增 `MODULE_AI` |
| `file-keeper/server/src/main/java/com/superprogrammer/authorization/service/AuthorizationService.java` | `MODULE_CODES` 加入 AI |
| `file-keeper/server/src/main/java/com/superprogrammer/ai/entity/AiConfig.java` | 新增 |
| `file-keeper/server/src/main/java/com/superprogrammer/ai/repository/AiConfigRepository.java` | 新增 |
| `file-keeper/server/src/main/java/com/superprogrammer/ai/service/AiConfigService.java` | 新增 |
| `file-keeper/server/src/main/java/com/superprogrammer/ai/controller/AiConfigController.java` | 新增 |
| `file-keeper/server/src/main/java/com/superprogrammer/ai/dto/AiConfigCreateRequest.java` | 新增 |
| `file-keeper/server/src/main/java/com/superprogrammer/ai/dto/AiConfigUpdateRequest.java` | 新增 |
| `file-keeper/server/src/main/java/com/superprogrammer/ai/dto/AiConfigVO.java` | 新增 |
| `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/AiSummaryService.java` | 改造 |
| `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/WorkReportService.java` | 改造 |
| `file-keeper/server/src/main/resources/db/migration/Vxxx__add_ai_config.sql` | 新增 |
| `file-keeper/server/src/main/resources/db/migration/Vxxx__add_report_config_ai_config_id.sql` | 新增 |

### 桌面端

| 文件 | 动作 |
|---|---|
| `file-keeper/src/types/aiConfig.ts` | 新增 |
| `file-keeper/src/api/aiConfig.ts` | 新增 |
| `file-keeper/src/stores/aiConfigStore.ts` | 新增 |
| `file-keeper/src/components/AiConfigSettings.vue` | 新增 |
| `file-keeper/src/components/SettingsDialog.vue` | 新增 AI Tab |
| `file-keeper/src/components/work-report/ReportConfigForm.vue` | 移除 AI 配置入口，改为选择配置 |
| `file-keeper/src/locales/zh-CN.ts` | 新增文案 |
| `file-keeper/src/locales/en.ts` | 新增文案 |

### 管理后台

| 文件 | 动作 |
|---|---|
| `file-keeper/admin-web/src/views/UserDetailView.vue` | `moduleOptions` 追加 AI |

---

*本设计基于方案 1：file-keeper 自管 AI 配置，AI 能力作为独立商业授权模块。*
