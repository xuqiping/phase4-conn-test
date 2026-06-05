# Deployment and Release Management Workflow

## Purpose

基于部署与发布管理知识框架，提供从环境管理到渐进式交付的标准化执行路径，覆盖渐进式交付、蓝绿/金丝雀/滚动部署策略、Feature Toggle解耦、数据库迁移即代码、SemVer版本管理五大核心能力。

## Prerequisites

- 已确定基础设施类型（K8s/VM/Serverless/混合云）
- 已确定发布频率目标（每日多次/每周/每两周）

## Steps

### Step 1: 环境分层与基础设施即代码

**Goal**: 建立标准化的环境分层模型，实现Environment as Code，支撑临时环境自动化
**Completion criterion**: 四层环境（Dev/Test/Staging/Prod）全部IaC化，临时环境（Ephemeral）每个PR自动创建，非生产环境自动启停节省50-70%成本

依据环境分层框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 部署与发布管理]：
1. 四层环境模型：
   - Dev（开发环境）：最宽松，开发者本地或共享开发集群
   - Test/QA（测试环境）：中等管控，自动化测试运行
   - Staging（预发布环境）：类生产验证，78%团队维护
   - Prod（生产环境）：最严格，直接面向用户
2. Staging"四同"标准：
   - 同配置：IaC同源（Terraform/Pulumi同一份代码不同变量）
   - 同版本：与生产版本一致或即将发布的版本
   - 同数据：脱敏生产数据，数据量同比例
   - 同流程：部署流程与生产完全一致
3. 临时环境（Ephemeral Environment）：
   - 每个PR自动创建独立环境
   - Environment as Code：Terraform/Pulumi一键创建/销毁
   - 生命周期：PR创建→环境创建→PR合并→环境销毁
4. FinOps成本优化：
   - 非生产环境占云支出30%
   - 自动启停：工作日8:00启动/20:00停止，节省50-70%
   - Spot实例：非生产环境使用抢占式实例，节省60-90%
执行：
- 输出《环境分层规范》：每层的用途/权限/数据/配置策略
- 配置IaC代码：Terraform/Pulumi定义全部环境
- 配置Ephemeral Environment Pipeline：PR webhook触发创建
- 配置自动启停策略（Kubernetes CronJob或云厂商Scheduler）

### Step 2: 部署策略选型与实施

**Goal**: 根据系统风险等级与基础设施成熟度选定并实施合适的部署策略
**Completion criterion**: 部署策略已选定并配置，六大策略有明确的决策树，金丝雀/蓝绿/滚动部署已Argo Rollouts化

依据部署策略选型框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 部署策略选型]：
1. 六大部署策略决策树：
   - 高风险+业务关键+有K8s服务网格 → 金丝雀（Canary）
   - 高风险+无服务网格 → 蓝绿（Blue-Green）
   - 低风险+快速迭代 → 滚动（Rolling）
   - 低流量+影子验证 → 暗发布（Dark Launch）
   - 数据库大变更+不可回滚 → 数据库优先部署
   - 紧急修复+确定性高 → 直接替换（Recreate）
2. 金丝雀部署三阶段：
   - 预热：1-5%流量，验证基础健康（启动/连接/日志无Error）
   - 验证：5-25%流量，自动质量检查（错误率<基线×1.5/P99延迟<基线×1.3）
   - 推进：25-100%流量，逐步放量，每步自动验证
3. 蓝绿部署：
   - 绿环境（当前生产）+ 蓝环境（新版本）
   - 验证通过后一键切换流量（DNS/Load Balancer）
   - 回滚秒级：切回绿环境
   - 资源成本：2倍容量（Green+Blue同时运行）
4. 滚动部署：
   - 逐个Pod替换，始终保持部分旧版本可用
   - 配置maxSurge/maxUnavailable控制替换节奏
   - 适用于无状态服务、低风险的日常发布
5. 全链路灰度核心：
   - 入口染色：Header X-Gray-Tag标记灰度用户
   - 链路透传：OpenTelemetry/gRPC拦截器透传灰度标记
   - 版本路由：Istio VirtualService按Header路由
   - 数据隔离：灰度用户数据独立存储或标记字段
执行：
- 输出《部署策略选型决策》（ADR格式）
- 配置Argo Rollouts：Canary/BlueGreen AnalysisTemplate
- 配置Prometheus监控指标作为发布门禁（错误率/P99/CPU/内存）
- 输出《部署操作手册》：每种策略的操作步骤+回滚命令+验证检查清单

### Step 3: Feature Toggle全生命周期管理

**Goal**: 建立Feature Toggle体系，实现代码部署与功能发布的解耦
**Completion criterion**: Toggle管理后台上线，四类Toggle（Release/Ops/Experiment/Permission）分类管理，过期告警机制运行，每Sprint预留20%清理时间

依据Feature Toggle框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > Feature Toggle解耦]：
1. Martin Fowler四类Toggle：
   - Release Toggle（1-2周）：新功能灰度，验证后移除
   - Ops Toggle（永久）：运维开关（熔断/限流/降级）
   - Experiment Toggle（A/B测试）：数据驱动功能决策
   - Permission Toggle（永久）：权限/付费功能控制
2. OpenFeature标准（CNCF沙箱）：
   - 消除供应商锁定（LaunchDarkly/Unleash/Flagsmith统一SDK）
   - Provider-agnostic SDK：同一代码切换不同后端
3. Toggle命名规范：
   - 格式：`{domain}.{feature}.{action}`，如`payment.stripe-rollout.enabled`
   - 禁止：无意义名称（feature1/newthing）、无上下文（flag_a）
4. 反模式识别：
   - 嵌套Toggle：A开启后检查B，逻辑复杂难以追踪
   - 永久Release Toggle：本应短期移除却长期存在
   - 无文档Toggle：创建时未记录目的与Owner
5. 生命周期管理：
   - 创建时：填写元数据（Owner/目的/预期移除日期/影响范围）
   - 运行时：Dashboard展示Toggle状态与流量分布
   - 过期告警：预期移除日期前7天通知Owner
   - 每Sprint预留20%时间清理过期Toggle
6. 灰度五步流程：
   - 0%：部署代码，Toggle关闭（仅内部测试）
   - 1-5%：员工/种子用户白名单
   - 20-30%：随机灰度，监控错误率/P99/业务指标
   - 50-70%：扩大灰度，A/B测试验证（p<0.05）
   - 100%：全量开放，计划移除Toggle（将代码固化）
执行：
- 部署Toggle管理平台：Unleash（开源）/ LaunchDarkly（商业）/ 自研
- 输出《Feature Toggle管理规范》：命名规则/分类标准/生命周期/清理策略
- 配置过期告警：Slack/邮件通知Toggle Owner
- 建立Toggle清理SOP：识别→验证→移除→测试→上线

### Step 4: 数据库迁移即代码

**Goal**: 建立数据库迁移即代码体系，实现Schema版本化、可回滚、向前兼容
**Completion criterion**: Flyway/Liquibase已配置，迁移脚本与代码同仓库同PR，大表DDL用gh-ost在线变更，三层回滚体系覆盖应用-配置-数据

依据数据库迁移框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 数据库迁移即代码]：
1. 工具选型：
   - Flyway（Spring Boot首选）：直接写SQL，V1__init.sql / V2__add_user_email.sql
   - Liquibase（多数据库）：XML/YAML数据库无关格式，changeSet抽象
2. 核心原则：
   - 向前兼容：新代码可读旧Schema，旧代码不读新Schema
   - 只增不删：先增加新列/表，后续版本再移除旧列/表
   - 小步迁移：每个迁移只做一件事，快速失败快速修复
   - 幂等性：重复执行不产生副作用
3. 大表DDL在线变更：
   - >100万行表：gh-ost（GitHub开源）/ pt-online-schema-change（Percona）
   - 原理：创建影子表→增量同步→原子切换（Rename）
   - 零停机：业务无感知
4. 数据迁移三步走：
   - 全量：一次性导出导入
   - 增量追平：Debezium/Canal CDC实时同步
   - 灰度切换：双写验证→切读→切写→停旧
5. Schema-as-Code（2026趋势）：
   - Atlas声明式管理：HCL定义目标Schema，自动计算迁移路径
   - AI辅助迁移生成：AI分析变更需求自动生成迁移脚本
6. CI/CD集成：
   - 迁移脚本与代码同仓库同PR
   - CI验证：SQL语法 + 回滚可行性 + 危险SQL审核（DROP/DELETE/TRUNCATE告警）
   - 多环境流水线：CI验证 → 测试自动执行 → Staging自动执行 → 生产审批执行
执行：
- 输出《数据库迁移规范》：命名规则/版本号/回滚策略/审核清单
- 配置Flyway/Liquibase baseline（初始Schema版本）
- 配置gh-ost/pt-osc用于大表DDL
- 输出《三层回滚手册》：应用层（K8s Revision回退）/ 配置层（Nacos/Apollo历史版本）/ 数据层（undo脚本+双写验证）

### Step 5: SemVer版本管理与发布工程

**Goal**: 建立SemVer版本管理体系，实现发布流程标准化、发布产物可追溯
**Completion criterion**: 版本号规则已定义，Changelog自动生成，发布产物（镜像/二进制/文档）已版本化，发布审批流程建立

依据版本管理框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > SemVer版本管理]：
1. SemVer规范：MAJOR.MINOR.PATCH
   - MAJOR：不兼容的API变更
   - MINOR：向后兼容的功能新增
   - PATCH：向后兼容的问题修复
   - 预发布：1.0.0-alpha < 1.0.0-beta < 1.0.0-rc < 1.0.0
2. 版本自动化：
   - Git Tag触发：git tag v1.2.3 → CI自动构建+发布
   - 语义化提交（Conventional Commits）：feat/fix/docs/BREAKING CHANGE自动推断版本号
   - 工具：semantic-release / release-please / standard-version
3. Changelog自动生成：
   - 基于Conventional Commits分类生成：Features / Bug Fixes / Breaking Changes / Documentation
   - 包含提交哈希、作者、关联Issue/PR
4. 发布产物管理：
   - 容器镜像：registry/app:1.2.3 + registry/app:latest
   - 二进制：GitHub Releases / Artifactory / S3
   - 文档：版本化文档站点（/v1.2/ /v1.3/ /latest/）
   - SBOM：每个版本附带SPDX/CycloneDX格式SBOM
5. 发布审批流程：
   - 自动发布（Dev/Test）：CI/CD自动触发
   - 半自动发布（Staging）：CI构建后，一键审批部署
   - 手动发布（Prod）：多审批（开发负责人+运维负责人+产品经理）
执行：
- 配置semantic-release或release-please
- 制定Conventional Commits规范：提交类型/格式/示例
- 配置Changelog模板：分类/格式/生成规则
- 输出《发布管理手册》：版本号规则/发布流程/审批权限/回滚策略

## Post-Workflow

1. 读取 `checklist/deployment_release_workflow_checklist.md`
2. 交叉验证每个部署策略配置、每个Toggle状态、每条迁移脚本
3. 全部通过后输出《部署与发布管理总纲》并归档
