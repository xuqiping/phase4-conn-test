# Coding and Version Control Workflow

## Purpose

基于编码与版本控制知识框架，提供从版本控制策略到代码质量保障的标准化执行路径，覆盖Trunk-Based主干开发、AI辅助Code Review、Clean as You Code质量门禁、规范即代码+AI Rules、四层技术文档金字塔五大核心能力。

## Prerequisites

- 已确定团队规模（1人/1-5人/5-15人/15-50人/>50人）
- 已确定发布频率（每日多次/每周/每两周/每月）

## Steps

### Step 1: Git工作流选型与演进

**Goal**: 根据团队规模与发布频率选定并实施合适的Git工作流模型
**Completion criterion**: Git工作流已配置完成，分支保护规则生效，Feature Toggle与发布策略匹配

依据Git工作流选型框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 编码与版本控制]：
1. 四种主流模型对比：
   - Git Flow：有计划发布周期/多版本并行（适合桌面软件/嵌入式）
   - GitHub Flow：持续部署Web应用，main即生产
   - Trunk-Based Development（TBD）：高频发布/每天多次/自动化测试覆盖率>70%
   - GitLab Flow：多环境合规项目（dev→pre-prod→prod）
2. 创业团队演进路线：
   - 0-1人：直接main提交
   - 1-5人：GitHub Flow + PR
   - 5-15人：main保护 + Feature Toggle
   - 15-50人：TBD或GitLab Flow（每阶段只增加一条规则）
   - >50人：TBD + Release Branch + 自动化门禁
3. Feature Toggle四层管理：
   - Release Toggle（1-2周）：新功能灰度，验证后移除
   - Ops Toggle（永久）：运维开关（如熔断/限流）
   - Experiment Toggle（A/B测试）：数据驱动决策
   - Permission Toggle（永久）：权限控制（如付费功能开关）
执行：
- 输出《Git工作流选型决策》（ADR格式）
- 配置分支保护：main分支禁止直接推送，需PR+审查+CI通过
- 配置Feature Toggle基础设施：OpenFeature SDK + Flag管理后台
- 输出《Feature Toggle管理规范》：命名规范/生命周期/清理策略

### Step 2: AI辅助Code Review流程

**Goal**: 建立AI+人类协同的Code Review流程，提升审查效率与质量
**Completion criterion**: AI审查响应时间<2分钟，人类审查聚焦业务逻辑，PR规模≤400行（超800必须拆分）

依据AI辅助Code Review框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > AI辅助Code Review]：
1. Code Review四层次：
   - L1 风格一致性：命名/格式/注释（AI处理）
   - L2 功能正确性：逻辑/边界/异常（AI初筛+人类确认）
   - L3 设计合理性：耦合/内聚/模式（人类主导）
   - L4 架构影响：性能/安全/扩展性（人类+架构师）
2. AI辅助审查配置：
   - AI作第一道防线：编码规范/安全漏洞/性能反模式（<2分钟出结果）
   - 人类聚焦：业务逻辑正确性/架构决策/设计模式选择
   - 工具：GitHub Copilot Review / Amazon CodeGuru / CodeRabbit / PR-Agent
3. PR规范：
   - 规模：每个PR不超过400行，超过800行必须拆分
   - Review SLA：P0首次审查≤30分钟，P1≤4小时，P2≤24小时
   - 审查清单分层：L1通用层（命名/格式/单测）+ L2模块层（API/数据库/前端）+ L3专项层（安全/性能/合规）
执行：
- 集成AI审查工具到CI Pipeline
- 输出《Code Review规范》：PR模板 + 审查清单 + SLA承诺
- 配置审查检查清单（Markdown格式嵌入PR模板）

### Step 3: Clean as You Code质量门禁

**Goal**: 建立分层质量门禁体系，确保代码质量持续达标
**Completion criterion**: 四级门禁全部配置，SonarQube四大维度（Reliability/Security/Security Review/Maintainability）质量门通过

依据Clean as You Code框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > Clean as You Code]：
1. 分层扫描策略：
   - L1 IDE实时（<1秒）：ESLint/Prettier/SpotBugs实时高亮
   - L2 Pre-commit（<30秒）：husky + lint-staged，强制格式化+静态检查
   - L3 CI门禁（<10分钟）：SonarQube全量扫描 + 安全扫描 + 单元测试
   - L4 定期全量（每周）：技术债务分析 + 覆盖率趋势 + 架构规则检查
2. SonarQube四大质量维度：
   - Reliability（可靠性）：Bug/异常处理/空指针
   - Security（安全性）：漏洞/SQL注入/XSS/敏感数据泄露
   - Security Review（安全审查）：热点代码需人工审查
   - Maintainability（可维护性）：代码异味/复杂度/重复率
3. 工具链推荐：
   - 前端：ESLint + Prettier + Biome（Rust编写，速度10x）
   - Java：Checkstyle + SpotBugs + ErrorProne
   - Python：Ruff + black + mypy
   - 安全专项：Semgrep + CodeQL
4. Clean as You Code原则：
   - 仅对新代码负责（New Code Quality Gate），不追溯历史债务
   - 增量改进：每次提交都比上次更好
执行：
- 配置SonarQube质量门禁：新代码覆盖率≥80%，无Critical/Bug，代码异味密度≤10/KLOC
- 配置CI Pipeline：PR时触发L3扫描，合并前必须通过
- 输出《代码质量门禁配置手册》

### Step 4: 规范即代码与AI Rules

**Goal**: 将编码规范转化为可执行的AI Rules文件，实现规范合规率≥88%
**Completion criterion**: AI Rules文件已配置（.cursorrules/CLAUDE.md/.customInstructions），规范合规率≥88%，Spec-driven Development流程建立

依据规范即代码框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 规范即代码+AI Rules]：
1. AI Rules文件体系：
   - .cursorrules（Cursor IDE）：项目级规则，定义编码规范/架构约束/安全红线
   - CLAUDE.md（Claude Code）：Anthropic官方格式，包含项目上下文/规范/工作流程
   - .customInstructions（GitHub Copilot）：自定义指令，指导AI生成符合规范的代码
2. 规范内容定义：
   - 命名规范（变量/函数/类/文件）
   - 架构约束（分层调用规则/依赖方向/禁止循环依赖）
   - 安全约束（输入验证/参数化查询/输出编码）
   - 性能约束（N+1查询禁止/大循环优化/缓存策略）
3. Spec-driven Development（AI时代新范式）：
   - 先写Spec：用自然语言+示例定义功能行为
   - AI按Spec生成：AI读取Spec+Rules生成代码
   - CI验证合规性：自动检查生成代码是否符合Rules
   - 规范合规率从40-60%提升至88%+
执行：
- 编写《AI Rules规范文件》：项目背景 + 技术栈 + 编码规范 + 架构约束 + 安全红线
- 配置IDE集成：Cursor/VS Code/Copilot读取Rules文件
- 建立Spec模板：功能描述 + 输入/输出示例 + 边界条件 + 异常场景
- 输出《Spec-driven Development流程指南》

### Step 5: 四层技术文档金字塔

**Goal**: 建立分层技术文档体系，确保关键决策有记录、核心方案可理解
**Completion criterion**: 输出≥1个ADR + ≥1个Design Doc，文档遵循"读者无需作者在场也能理解"原则

依据四层技术文档框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 四层技术文档金字塔]：
1. 四层金字塔：
   - L1 ADR（半页~1页）：架构决策记录，回答"为什么选A而非B"
   - L2 RFC（1-5页）：技术方案征求，回答"我们要做什么、怎么做"
   - L3 Design Doc（5-20页）：详细设计，包含架构图/数据流/接口定义/部署方案
   - L4 Tech Spec（10-50页）：完整技术规格，面向实施团队
2. 核心原则："让读者在没有作者在场的情况下也能理解方案"
   - 包含背景（Why）、目标（What）、方案（How）、风险（What if）
   - 包含架构图（C4模型）、时序图（关键流程）、状态图（复杂状态机）
3. 度量体系：
   - DORA四大指标：部署频率/变更前置时间/变更失败率/MTTR
   - SPACE框架：满意度与幸福感/绩效/活动/沟通与协作/效率与流程
   - 古德哈特定律防范：指标用于"发现问题"和"指导改进"，不用于"排名考核"
执行：
- 为每个关键架构决策创建ADR（参见工作流2 Step 3）
- 为每个Sprint/版本创建RFC或Design Doc
- 输出《技术文档编写规范》：模板 + 评审流程 + 维护责任
- 配置度量Dashboard：DORA指标自动采集与展示

## Post-Workflow

1. 读取 `checklist/coding_version_control_workflow_checklist.md`
2. 交叉验证每个分支保护规则、每条AI审查配置、每个质量门禁阈值
3. 全部通过后输出《编码与版本控制总纲》并归档
