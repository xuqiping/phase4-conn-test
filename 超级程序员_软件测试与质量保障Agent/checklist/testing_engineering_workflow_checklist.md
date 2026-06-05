# 测试工程体系 Workflow Checklist

Use this checklist after completing every step of `workflow/testing_engineering_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 测试左移与右移闭环

- [ ] 左移4D模型已落地：Define（需求可测试性评审）→Design（架构可测试性）→Develop（TDD/BDD）→Deliver（CI门禁）
- [ ] PRD可测试性Checklist覆盖边界/并发/安全，BDD场景（Given-When-Then）已绑定自动化测试
- [ ] 架构评审增加"测试友好度"维度，可测试性设计模式（DI/ISP/IoC/特性开关）已应用
- [ ] 右移四层技术栈部署完成：发布策略（蓝绿/金丝雀）→质量验证（Kayenta）→监控告警（Prometheus+Grafana）→可观测性（OpenTelemetry）
- [ ] SLO已定义且错误预算机制运行（耗尽则暂停非紧急发布）
- [ ] 混沌工程工具（Chaos Mesh/Litmus）已部署，GameDay实验计划已执行至少1次
- [ ] 分层实验覆盖L1基础设施/L2应用层/L3业务层至少各1个场景
- [ ] 全链路闭环验证通过：需求→开发→CI→CD→运维→反馈→度量
- [ ] 缺陷逃逸率下降目标量化（参考：某电商平台12%→4.5%）
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位

## Step 2: DevOps质量门禁建设

- [ ] 四层门禁全部上线运行：Gate1 PR（<5min）→Gate2主干（<20min）→Gate3预发布（<60min）→Gate4生产（持续）
- [ ] Gate1包含：代码风格+增量单测+SonarQube新代码扫描+快速安全扫描+差异覆盖率≥70%
- [ ] Gate2包含：全量单测+集成测试+契约测试+SonarQube全量扫描+深度安全扫描（SAST+SCA+密钥检测）
- [ ] Gate3包含：E2E测试+性能基线对比（退化>5%失败）+混沌验证+突变测试
- [ ] Gate4包含：金丝雀指标对比+业务指标监控+异常自动回滚
- [ ] 零容忍项达成率100%：安全漏洞/Critical Bug=0，不可绕过
- [ ] SonarQube规则集配置完整：Blocker/Critical/Major/Minor分级，AI CodeFix已启用
- [ ] DevSecOps四层安全扫描已部署：IDE实时→pre-commit密钥检测→CI阶段SAST+SCA+容器扫描→CD阶段动态测试
- [ ] 供应链安全：SBOM（CycloneDX/SPDX）自动生成，许可证合规扫描运行
- [ ] Flaky Test比例≤2%，突变测试得分≥70%
- [ ] 度量金字塔四层指标已采集：战略层（ROI）→管理层（DORA+逃逸率）→执行层（覆盖率+通过率）→过程层（CI时间+绕过率）
- [ ] 门禁通过率稳定在85-95%，发布周期缩短目标已量化

## Step 3: 测试团队架构与平台化

- [ ] 当前组织阶段评估完成（传统QA/敏捷QA/QE赋能/平台工程化），目标阶段选择合理
- [ ] 三种核心角色定义清晰：QE/SDET/安全冠军，日常时间配比已明确
- [ ] SDET技术栈已定义：基础层（Python/Go/Git/Docker/K8s）→测试层（pytest/Playwright/Locust/Pact）→平台层（FastAPI/PostgreSQL/Redis）→DevOps层（GitLab CI/Terraform/Prometheus）→AI层（Promptfoo/CodiumAI）
- [ ] 团队配比模型已确定，弹性模型（核心+外包+AI替代）已规划
- [ ] 转型路径明确：角色重定义→职责转移→考核调整→能力建设→文化塑造
- [ ] 质量度量体系上线，"度量公开、考核谨慎"原则已传达
- [ ] DORA四指标已度量：部署频率≥1次/天、变更前置≤1天、变更失败率≤5%、MTTR≤1小时
- [ ] 北极星指标（缺陷逃逸率）目标<10%，已建立看板追踪
- [ ] TaaS服务目录已定义：自动化测试/性能测试/安全扫描/测试数据服务
- [ ] 2026三大趋势对齐：Agentic AI/平台工程化/人机协同测试
- [ ] 管理层评审通过，培训计划已制定并启动

## Overall

- [ ] All steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位。
