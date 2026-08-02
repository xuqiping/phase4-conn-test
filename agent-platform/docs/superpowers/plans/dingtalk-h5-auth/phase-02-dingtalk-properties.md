# Phase 2 — 钉钉配置属性

> 总路由：[README.md](README.md) · 上一：[Phase 1](phase-01-user-table-fields.md) · 下一：[Phase 3](phase-03-dingtalk-service.md)

**Goal：** 钉钉 AppKey/AppSecret/AgentId 配置属性，env 可覆盖。

**Files:**
- Create: `backend/src/main/java/com/superprogrammer/auth/dingtalk/config/DingTalkProperties.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Consumes: 无。
- Produces: `DingTalkProperties` Bean（`getEnabled()/getAppKey()/getAppSecret()/getAgentId()`），供 Phase 3 `DingTalkService` 注入。

---

- [ ] **Step 1: 写 `DingTalkProperties.java`**

```java
package com.superprogrammer.auth.dingtalk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dingtalk")
public class DingTalkProperties {

    /** 是否启用钉钉免登（默认关，配齐密钥再开） */
    private boolean enabled = false;

    /** H5 微应用 AppKey（= OAuth client_id） */
    private String appKey;

    /** H5 微应用 AppSecret */
    private String appSecret;

    /** 微应用 AgentId（记录用，OAuth 免登流程不强制） */
    private String agentId;
}
```

- [ ] **Step 2: 在 `application.yml` 追加配置块**（贴到文件末尾，缩进对齐已有顶层 key）

```yaml
dingtalk:
  enabled: ${DINGTALK_ENABLED:false}
  app-key: ${DINGTALK_APP_KEY:}
  app-secret: ${DINGTALK_APP_SECRET:}
  agent-id: ${DINGTALK_AGENT_ID:}
```

- [ ] **Step 3: 编译校验**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/superprogrammer/auth/dingtalk/config/DingTalkProperties.java backend/src/main/resources/application.yml
git commit -m "feat(auth): 钉钉免登配置属性 DingTalkProperties"
```

- [ ] **完成后：** 回 [README](README.md) 勾掉 Phase 2，开 Phase 3。
