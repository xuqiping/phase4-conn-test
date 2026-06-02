# 网站+手机App系统开发流程建议

> 基于超级程序员Agent层级架构，覆盖从需求到运维的全生命周期  
> **本流程自带进度追踪机制：每完成一个步骤自动更新进度文件**

---

## 进度追踪机制（强制执行）

### 进度文件位置

```
..\task\current_task.md
```

### 进度文件格式

采用标准Markdown表格，每完成一个步骤追加一行记录：

```markdown
| recard_time | total_target | agent | skill | workflow | stat | task | next_agent | next_skill | next_workflow | next_task |
|-------------|--------------|-------|-------|----------|------|------|------------|------------|---------------|-----------|
| 2026-05-25 14:00 | 开发网站+手机App系统 | 超级程序员_软件工程与开发全流程Agent | super_programmer_software_engineering_agent_skill | requirement_product_workflow.md | 完成 | Step 1: AI-Native用户研究完成，输出《用户研究报告》 | 超级程序员_软件工程与开发全流程Agent | super_programmer_software_engineering_agent_skill | requirement_product_workflow.md | Step 2: 需求结构化拆解与Story Mapping |
```

### 更新规则

1. **何时更新**：每完成一个工作流步骤并通过对应checklist验证后，**必须**追加记录
2. **谁更新**：执行该步骤的Agent自动更新
3. **状态定义**：
   - `完成`：步骤通过checklist，产出物已归档
   - `中断`：遇到阻塞，需人工介入
   - `进行中`：步骤已开始但未完成（用于长耗时步骤）
4. **项目收尾**：当`total_target`完全达成且用户确认后，**清空**进度文件（留空或仅保留注释）

### 进度文件初始化

项目启动时，由顶层路由Agent写入第一行记录：

```markdown
| recard_time | total_target | agent | skill | workflow | stat | task | next_agent | next_skill | next_workflow | next_task |
|-------------|--------------|-------|-------|----------|------|------|------------|------------|---------------|-----------|
| 2026-05-25 13:45 | 开发网站+手机App系统（全生命周期） | 超级程序员Agent | super_programmer_agent_skill | init | 完成 | 项目启动，初始化进度追踪文件，确认9阶段执行计划 | 超级程序员_软件工程与开发全流程Agent | super_programmer_software_engineering_agent_skill___requirement_product | requirement_product_workflow.md | Step 1: AI-Native用户研究与需求收集 |
```

---

## 执行顺序总览

```
阶段一：需求与产品定义
    ↓ [更新进度文件：Step 1完成]
阶段二：系统设计与技术选型
    ↓ [更新进度文件：Step 2完成]
阶段三：技术栈落地（前端+后端+数据库 并行开发）
    ↓ [更新进度文件：Step 3A/B/C/D完成]
阶段四：编码质量与版本控制
    ↓ [更新进度文件：Step 4完成]
阶段五：测试与质量保障
    ↓ [更新进度文件：Step 5完成]
阶段六：部署与发布管理
    ↓ [更新进度文件：Step 6完成]
阶段七：运维监控与故障响应
    ↓ [更新进度文件：Step 7完成]
阶段八：安全开发生命周期（贯穿全程）
    ↓ [更新进度文件：Step 8完成]
阶段九：文档与知识沉淀（贯穿全程）
    ↓ [更新进度文件：项目收尾，清空进度文件]
```

**关键原则**：
1. **安全左移**：安全编码规范从阶段四就开始，威胁建模在阶段二完成
2. **测试左移**：单元测试与代码同步编写（TDD），阶段五做全量验证
3. **文档同行**：ADR与架构决策同步，Runbook与运维同步
4. **渐进交付**：Feature Toggle从阶段四启用，阶段六做灰度放量
5. **进度同步**：每完成一个步骤，立即更新 `task/current_task.md`

**最少必要路径（MVP模式）**：阶段一→阶段二→阶段三（前后端+数据库）→阶段四→阶段五（轻量）→阶段六（滚动发布）

---

## 阶段一：需求与产品定义

**调用**：`超级程序员_软件工程与开发全流程Agent`  
**工作流**：`requirement_product_workflow.md`  
**进度标记**：每完成1个Step追加1行记录

| 步骤 | 产出物 | 进度更新触发点 |
|------|--------|---------------|
| Step 1: AI-Native用户研究 | 《用户研究报告》《用户画像卡片》 | 报告通过评审签字 |
| Step 2: 需求结构化拆解（Story Mapping） | 《用户故事地图》《需求规格说明书》 | Story Map冻结，MVP范围确认 |
| Step 3: 优先级组合排序（RICE+MoSCoW） | 《需求优先级矩阵》《版本范围说明书》 | 优先级评审会完成 |
| Step 4: 变更追踪RTM | 《变更控制流程》《需求追溯矩阵》 | CCB机制建立，RTM覆盖率≥95% |
| Step 5: PRD视觉化 | 《PRD文档》《数据埋点方案》 | PRD评审通过，原型确认签字 |

**本阶段进度示例**：
```markdown
| 2026-05-25 14:00 | 开发网站+手机App系统 | 超级程序员_软件工程与开发全流程Agent | super_programmer_software_engineering_agent_skill___requirement_product | requirement_product_workflow.md | 完成 | Step 1: AI-Native用户研究完成，输出《用户研究报告》 | 超级程序员_软件工程与开发全流程Agent | super_programmer_software_engineering_agent_skill___requirement_product | requirement_product_workflow.md | Step 2: 需求结构化拆解与Story Mapping |
| 2026-05-25 16:30 | 开发网站+手机App系统 | 超级程序员_软件工程与开发全流程Agent | super_programmer_software_engineering_agent_skill___requirement_product | requirement_product_workflow.md | 完成 | Step 2: Story Mapping完成，MVP范围确认 | 超级程序员_软件工程与开发全流程Agent | super_programmer_software_engineering_agent_skill___requirement_product | requirement_product_workflow.md | Step 3: 优先级组合排序 |
```

---

## 阶段二：系统设计与技术选型

**调用**：`超级程序员_软件工程与开发全流程Agent`  
**工作流**：`system_design_workflow.md`  
**进度标记**：每完成1个Step追加1行记录

| 步骤 | 产出物 | 进度更新触发点 |
|------|--------|---------------|
| Step 1: DDD战略设计+事件风暴 | 《领域模型图》《限界上下文映射》 | 事件风暴工作坊完成，术语表≥30个 |
| Step 2: DDD战术设计+架构选型 | 《系统架构设计书》《实体关系图》 | 架构评审会通过 |
| Step 3: ADR架构决策记录 | 《ADR目录》《C4模型图》 | ≥5个关键ADR完成，C4模型Structurizr化 |
| Step 4: 技术选型评估矩阵 | 《技术选型评估报告》《POC验证报告》 | POC测试通过，技术雷达更新 |
| Step 5: 数据库+API First设计 | 《数据库设计说明书》《OpenAPI Spec》 | OpenAPI Spec通过评审 |
| Step 6: UI/UX设计系统 | 《设计系统规范》《HEART度量指标库》 | 设计Token到组件库1:1映射完成 |

---

## 阶段三：技术栈落地（并行开发）

**本阶段特点**：4条并行线同时推进，每条线独立更新进度

### 3A. 前端开发（Web + App）
**调用**：`超级程序员_编程语言与基础开发Agent`  
**工作流**：`frontend_framework_workflow.md`  
**进度更新**：每完成一个功能模块或技术选型决策

覆盖：React/Vue/Angular选型、跨平台方案（Flutter/React Native/UniApp）、移动端适配、PWA、性能优化

### 3B. 后端开发
**调用**：`超级程序员_编程语言与基础开发Agent`  
**工作流**：`backend_programming_language_workflow.md`  
**进度更新**：每完成一个API模块或服务

覆盖：Java/Python/Go/Node.js选型、RESTful/gRPC API实现、业务逻辑开发

### 3C. 后端架构与中间件（如系统复杂）
**调用**：`超级程序员_后端架构与中间件Agent`  
**工作流**：`microservice_architecture_workflow.md` / `message_queue_workflow.md` / `cache_middleware_workflow.md`  
**进度更新**：每完成一个中间件部署或架构决策

覆盖：微服务拆分、MQ选型（Kafka/RocketMQ）、Redis缓存策略、服务治理

### 3D. 数据库与存储
**调用**：`超级程序员_数据库与数据存储Agent`  
**工作流**：`relational_database_workflow.md` / `nosql_database_workflow.md`  
**进度更新**：每完成一个数据库部署或 schema 变更

覆盖：MySQL/PostgreSQL主从架构、MongoDB/Redis选型、分库分表策略

**本阶段进度示例**：
```markdown
| 2026-05-26 10:00 | 开发网站+手机App系统 | 超级程序员_编程语言与基础开发Agent | super_programmer_programming_language_agent_skill___frontend | frontend_framework_workflow.md | 完成 | 前端技术栈选型完成：React 18 + React Native | 超级程序员_编程语言与基础开发Agent | super_programmer_programming_language_agent_skill___frontend | frontend_framework_workflow.md | 核心页面组件开发 |
| 2026-05-26 10:00 | 开发网站+手机App系统 | 超级程序员_编程语言与基础开发Agent | super_programmer_programming_language_agent_skill___backend | backend_programming_language_workflow.md | 完成 | 后端技术栈选型完成：Node.js + NestJS | 超级程序员_编程语言与基础开发Agent | super_programmer_programming_language_agent_skill___backend | backend_programming_language_workflow.md | API接口开发 |
| 2026-05-26 11:00 | 开发网站+手机App系统 | 超级程序员_数据库与数据存储Agent | super_programmer_database_storage_agent_skill___relational | relational_database_workflow.md | 完成 | PostgreSQL主从架构部署完成 | 超级程序员_数据库与数据存储Agent | super_programmer_database_storage_agent_skill___relational | relational_database_workflow.md | Schema设计与初始化 |
```

---

## 阶段四：编码质量与版本控制

**调用**：`超级程序员_软件工程与开发全流程Agent`  
**工作流**：`coding_version_control_workflow.md`  
**进度标记**：每完成1个Step追加1行记录

| 步骤 | 产出物 | 进度更新触发点 |
|------|--------|---------------|
| Step 1: Git工作流选型 | 《Git工作流规范》《Feature Toggle管理规范》 | main分支保护规则生效 |
| Step 2: AI辅助Code Review | 《Code Review规范》《审查清单》 | AI审查工具CI集成，Review SLA配置 |
| Step 3: Clean as You Code质量门禁 | 《质量门禁配置手册》（SonarQube） | 四级门禁全部配置，质量门通过 |
| Step 4: 规范即代码+AI Rules | `.cursorrules` / `CLAUDE.md` | 规范合规率≥88% |
| Step 5: 四层技术文档金字塔 | ADR + Design Doc + API文档 | ≥1个ADR + ≥1个Design Doc输出 |

---

## 阶段五：测试与质量保障

**调用**：`超级程序员_软件工程与开发全流程Agent`（主） + `超级程序员_软件测试与质量保障Agent`（辅）  
**工作流**：`testing_quality_workflow.md` + `automated_testing_workflow.md`  
**进度标记**：每完成1个Step追加1行记录

| 步骤 | 产出物 | 进度更新触发点 |
|------|--------|---------------|
| Step 1: 测试策略模型选型 | 《测试策略决策记录》（金字塔/钻石/奖杯） | 测试金字塔形状与各层比例确认 |
| Step 2: 四级测试体系 | 单元测试（≥80%覆盖）+ 集成测试 + E2E + UAT | 四级测试体系运行正常，CI/CD分层触发配置 |
| Step 3: 自动化测试工程化 | 《自动化测试规范》《Flaky Test Registry》 | Playwright工程化配置，Flaky Rate<2% |
| Step 4: 性能压测 | k6全链路压测、《性能基线报告》 | 全链路压测通过，关键指标达标 |
| Step 5: DORA度量 | Dashboard（部署频率/变更前置时间/失败率/MTTR） | DORA四大指标可自动采集，Dashboard上线 |

---

## 阶段六：部署与发布管理

**调用**：`超级程序员_软件工程与开发全流程Agent`（主） + `超级程序员_云计算与云原生Agent`（辅）  
**工作流**：`deployment_release_workflow.md` + `cloud_native_ecosystem_workflow.md` / `container_technology_workflow.md`  
**进度标记**：每完成1个Step追加1行记录

| 步骤 | 产出物 | 进度更新触发点 |
|------|--------|---------------|
| Step 1: 环境分层+IaC | Terraform/Pulumi定义四层环境 | 四层环境全部IaC化，临时环境PR自动创建 |
| Step 2: 部署策略实施 | 金丝雀/蓝绿/滚动部署（Argo Rollouts） | 部署策略选定并配置，发布门禁生效 |
| Step 3: Feature Toggle管理 | 灰度五步流程（0%→5%→25%→70%→100%） | Toggle管理后台上线，灰度流程配置 |
| Step 4: 数据库迁移即代码 | Flyway/Liquibase迁移脚本 | 迁移脚本与代码同仓库同PR，大表DDL在线变更 |
| Step 5: SemVer版本管理 | 自动Changelog + 发布产物版本化 | SemVer规范定义，发布审批流程建立 |

---

## 阶段七：运维监控与故障响应

**调用**：`超级程序员_软件工程与开发全流程Agent`（主） + `超级程序员_运维工程与系统架构Agent`（辅）  
**工作流**：`operations_monitoring_workflow.md` + `linux_operations_workflow.md`  
**进度标记**：每完成1个Step追加1行记录

| 步骤 | 产出物 | 进度更新触发点 |
|------|--------|---------------|
| Step 1: OpenTelemetry可观测性 | Metrics（RED/USE）+ Logs + Traces | OTel Collector部署，三支柱关联查询可用 |
| Step 2: SLO驱动告警 | Error Budget + P1-P4告警分级 | SLO/SLI/Error Budget定义，告警疲劳治理启动 |
| Step 3: START故障排查 | 《故障排查Runbook》《Post-Mortem模板》 | Runbook覆盖Top 10场景，Blameless文化推行 |
| Step 4: FinOps容量规划 | USL模型分析 + 成本优化路线图 | 容量规划四步法运行，成本降低20-40% |
| Step 5: 混沌工程 | 每月1次Chaos Mesh实验 | 混沌工程实验库建立，安全网机制生效 |

---

## 阶段八：安全开发生命周期（贯穿全程）

**调用**：`超级程序员_软件工程与开发全流程Agent`（主） + `超级程序员_网络安全与信息安全Agent`（辅）  
**工作流**：`security_lifecycle_workflow.md` + `application_security_workflow.md`  
**进度标记**：安全步骤与其他阶段穿插，完成即更新

| 步骤 | 产出物 | 进度更新触发点 | 穿插阶段 |
|------|--------|---------------|----------|
| Step 1: OWASP安全编码 | 《安全编码规范》+ 三层权限模型 | 规范发布，权限模型实施 | 阶段二（设计）+ 阶段四（编码） |
| Step 2: 五维安全扫描 | SAST（Semgrep）+ DAST（ZAP）+ SCA（Snyk） | 扫描矩阵配置，高危漏洞CI阻断 | 阶段四（CI/CD集成） |
| Step 3: STRIDE威胁建模 | 《威胁建模报告》+ DFD图 | 威胁建模评审通过，DREAD>7分威胁缓解 | 阶段二（设计评审） |
| Step 4: 漏洞响应SLA | Critical 72h / High 7天 | 漏洞分级标准发布，SLA达标率≥90% | 阶段七（运维） |
| Step 5: SBOM供应链安全 | 每次构建自动生成SBOM | SBOM与NVD/OSV关联，SLSA Level 3 | 阶段六（CI/CD） |

---

## 阶段九：文档与知识沉淀（贯穿全程）

**调用**：`超级程序员_软件工程与开发全流程Agent`  
**工作流**：`documentation_knowledge_workflow.md`  
**进度标记**：文档步骤与其他阶段同步，完成即更新

| 步骤 | 产出物 | 进度更新触发点 | 穿插阶段 |
|------|--------|---------------|----------|
| Step 1: 四层文档体系 | L1战略/L2设计/L3实现/L4运维 | 文档覆盖率≥90%，ADR目录≥5个 | 阶段二（ADR）+ 阶段四（技术文档） |
| Step 2: Docs as Code | Markdown + CI/CD文档流水线 | 文档仓库与代码仓库关联，CI流水线运行 | 阶段四（编码规范） |
| Step 3: API开发者门户 | Swagger UI + SDK自动生成 | API文档P0要素齐全，开发者门户上线 | 阶段三（API开发完成） |
| Step 4: SECI知识沉淀 | RAG知识库 + 巴士因子监控 | 知识贡献纳入绩效≥10%，RAG部署 | 阶段七（团队运营） |
| Step 5: Runbook标准化 | Top 20运维场景Runbook | Runbook体系实施，MTTR降至12min | 阶段七（运维） |

---

## 项目收尾

**触发条件**：全部9个阶段完成，用户确认验收

**收尾动作**：
1. 追加最后一条进度记录：
   ```markdown
   | 2026-XX-XX XX:XX | 开发网站+手机App系统 | 超级程序员Agent | super_programmer_agent_skill | init | 完成 | 项目正式收尾，全部9个阶段完成，系统上线运行 | - | - | - | 项目结束 |
   ```
2. **清空** `task/current_task.md`（留空或仅保留注释）
3. 输出《项目总结报告》：
   - 总耗时
   - 各阶段实际vs计划对比
   - 关键风险与应对措施
   - 经验教训（Post-Mortem）
   - 后续迭代建议

---

## 快速启动检查清单

### 最小可行团队配置

| 角色 | 人数 | 职责 |
|------|------|------|
| 产品经理 | 1 | 阶段一全部工作 |
| 架构师/ Tech Lead | 1 | 阶段二+阶段四+阶段八 |
| 前端开发 | 1-2 | 阶段三A（Web+App） |
| 后端开发 | 1-2 | 阶段三B/C/D |
| 测试工程师 | 0.5-1 | 阶段五（可与开发兼职） |
| DevOps/SRE | 0.5-1 | 阶段六+七（可与架构师兼职） |

### 第一周启动动作

1. **产品经理**：启动阶段一，输出《用户故事地图》和MVP范围
2. **架构师**：同步启动阶段二，完成技术选型矩阵和ADR
3. **全体**：确定Git工作流（GitHub Flow或Trunk-Based）
4. **DevOps**：搭建CI/CD基础流水线（GitHub Actions/GitLab CI）
5. **安全**：确定等保级别（如有合规要求），启动STRIDE威胁建模
6. **项目经理（或Tech Lead）**：创建 `task/current_task.md`，写入初始化记录

### 技术栈推荐（通用场景）

| 层级 | 推荐技术 |
|------|----------|
| 前端Web | React 18 + TypeScript + Vite + Tailwind CSS |
| 前端App | React Native / Flutter（跨平台） |
| 后端API | Node.js(Express/NestJS) 或 Go(Gin) 或 Java(Spring Boot) |
| 数据库 | PostgreSQL（主库）+ Redis（缓存） |
| 消息队列 | RabbitMQ（简单）/ Kafka（高吞吐） |
| 容器编排 | Docker + Kubernetes（或 Docker Compose起步） |
| CI/CD | GitHub Actions + ArgoCD |
| 监控 | Prometheus + Grafana + OpenTelemetry |
| 安全扫描 | Semgrep + Snyk + OWASP ZAP |

---

*生成时间：2026-05-25*  
*基于超级程序员Agent层级架构 v1.0*  
*进度追踪机制版本：v1.0（强制执行）*
